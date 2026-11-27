package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.PrimitiveConversions;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static love.crysh.mixinta.analysis.plan.RepresentationPlan.*;

public final class RepresentationPlanApplier {
    private RepresentationPlanApplier() { }

    private static void applyEdit(Edit edit, ClassNode owner, MethodNode method, AbstractInsnNode instruction) {
        var action = edit.action();
        var helper = edit.helper();
        var representation = edit.representation();
        switch (action) {
            case LOAD_REFERENCE -> method.instructions.set(instruction,
                    new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) instruction).var));
            case STORE_REFERENCE -> method.instructions.set(instruction,
                    new VarInsnNode(Opcodes.ASTORE, ((VarInsnNode) instruction).var));
            case CAST_REFERENCE -> ((TypeInsnNode) instruction).desc = representation.getInternalName();
            case REMOVE_BOXING -> method.instructions.set(instruction, new InsnNode(Opcodes.NOP));
            case REPLACE_UNBOXING -> method.instructions.set(instruction, invoke(helper));
            case ENCODE_RETURN -> method.instructions.insertBefore(instruction, invoke(helper));
            case MIGRATE_INVOCATION -> {
                MethodInsnNode call = (MethodInsnNode) instruction;
                call.owner = helper.owner();
                call.name = helper.name();
                call.desc = helper.descriptor();
            }
            case REPLACE_PRODUCER -> replaceSource(edit, method, (MethodInsnNode) instruction);
            case OBSERVE_MAP_VALUES, OBSERVE_VIEW_ELEMENTS -> observeElements(edit, owner, method, (MethodInsnNode) instruction);
            default -> throw new IllegalStateException("Unknown representation action " + action);
        }
    }

    /** 保留 Map 的 null key 和 null value，非空表示通过编码恢复 Long 比较语义。 */
    private static void observeElements(Edit edit, ClassNode owner, MethodNode method, MethodInsnNode invocation) {
        var action = edit.action();
        var location = edit.location();
        var helper = edit.helper();
        var representation = edit.representation();
        var query = edit.query();
        boolean map = action == Action.OBSERVE_MAP_VALUES;
        boolean remove = invocation.name.equals("remove");
        String name = "mixinta$" + invocation.name + "$" + location.instruction();
        while (hasMethod(owner, name)) {
            name += "$";
        }
        String descriptor = map ? "(Ljava/util/Map;Ljava/lang/Object;)Z" : "(Ljava/util/Collection;Ljava/lang/Object;)Z";
        MethodNode observation = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, descriptor, null, null);
        InsnList code = observation.instructions;
        LabelNode loop = new LabelNode();
        LabelNode absent = new LabelNode();
        LabelNode found = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (map) code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, absent));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        encodeBoxed(code, helper, representation);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, found));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(absent);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(found);
        if (remove) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "remove", "()V", true));
        }
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        observation.maxLocals = 3;
        owner.methods.add(observation);
        if (query != null) {
            InsnList conversion = new InsnList();
            encodeBoxed(conversion, query.helper(), query.representation());
            method.instructions.insertBefore(invocation, conversion);
        }
        invocation.setOpcode(Opcodes.INVOKESTATIC);
        invocation.owner = owner.name;
        invocation.name = name;
        invocation.desc = descriptor;
        invocation.itf = false;
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name));
    }

    private static void replaceSource(Edit edit, MethodNode method, MethodInsnNode invocation) {
        var helper = edit.helper();
        var binding = edit.binding();
        var parameter = edit.parameter();
        Type[] arguments = Type.getArgumentTypes(invocation.desc);
        int[] slots = new int[arguments.length];
        int next = method.maxLocals;
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = next;
            next += arguments[index].getSize();
        }
        int receiver = next;
        boolean instance = invocation.getOpcode() != Opcodes.INVOKESTATIC;
        if (instance) {
            next++;
        }
        InsnList replacement = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), slots[index]));
        }
        if (instance) {
            replacement.add(new VarInsnNode(Opcodes.ASTORE, receiver));
        }
        if (binding == TypeRule.Binding.RECEIVER) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, receiver));
        } else {
            if (instance) {
                replacement.add(new VarInsnNode(Opcodes.ALOAD, receiver));
                replacement.add(invoke(new MethodKey("java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;")));
                replacement.add(new InsnNode(Opcodes.POP));
            }
            replacement.add(new VarInsnNode(arguments[parameter].getOpcode(Opcodes.ILOAD), slots[parameter]));
        }
        replacement.add(invoke(helper));
        method.maxLocals = next;
        method.instructions.insertBefore(invocation, replacement);
        method.instructions.remove(invocation);
    }

    private static void applyBridge(Bridge entry, ClassNode owner) {
        var method = entry.method();
        var helper = entry.helper();
        MethodNode existing = ClassFiles.findMethod(owner, method.name(), method.descriptor());
        if (existing != null) {
            long matches = java.util.Arrays.stream(existing.instructions.toArray()).filter(instruction ->
                    instruction instanceof MethodInsnNode call && call.owner.equals(helper.owner())
                            && call.name.equals(helper.name()) && call.desc.equals(helper.descriptor())).count();
            if ((existing.access & Opcodes.ACC_SYNTHETIC) != 0 && matches == 1) return;
            throw new IllegalStateException("Rule bridge collision " + method);
        }
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                method.name(), method.descriptor(), null, null);
        int slot = 0;
        for (Type parameter : Type.getArgumentTypes(method.descriptor())) {
            bridge.instructions.add(new VarInsnNode(parameter.getOpcode(Opcodes.ILOAD), slot));
            slot += parameter.getSize();
        }
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper.owner(), helper.name(),
                helper.descriptor(), (owner.access & Opcodes.ACC_INTERFACE) != 0));
        bridge.instructions.add(new InsnNode(Type.getReturnType(method.descriptor()).getOpcode(Opcodes.IRETURN)));
        bridge.maxLocals = slot;
        owner.methods.add(bridge);
    }

    public static void apply(RepresentationPlan plan, Map<String, ClassNode> nodes, Map<MethodKey, AbstractInsnNode[]> instructions) {
        var bridges = plan.bridges();
        var edits = plan.edits();
        var arguments = plan.arguments();
        var locals = plan.locals();
        for (Bridge bridge : bridges) {
            applyBridge(bridge, nodes.get(bridge.method().owner()));
        }
        for (Edit edit : edits) {
            MethodKey key = edit.location().method();
            MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
            applyEdit(edit, nodes.get(key.owner()), method, instructions.get(key)[edit.location().instruction()]);
        }
        Map<InstructionLocation, List<Argument>> grouped = new java.util.LinkedHashMap<>();
        for (Argument argument : arguments) {
            grouped.computeIfAbsent(argument.location(), ignored -> new java.util.ArrayList<>()).add(argument);
        }
        for (var entry : grouped.entrySet()) {
            InstructionLocation location = entry.getKey();
            MethodNode method = ClassFiles.findMethod(nodes.get(location.method().owner()), location.method().name(), location.method().descriptor());
            MethodInsnNode invocation = (MethodInsnNode) instructions.get(location.method())[location.instruction()];
            Argument first = entry.getValue().getFirst();
            Type[] original = Type.getArgumentTypes(first.original().descriptor());
            Type[] replacement = Type.getArgumentTypes(first.implementation().descriptor());
            int[] slots = new int[original.length];
            int next = method.maxLocals;
            Type[] current = original.clone();
            for (Argument argument : entry.getValue()) {
                current[argument.parameter()] = argument.representation();
            }
            for (int index = 0; index < original.length; index++) {
                slots[index] = next;
                next += current[index].getSize();
            }
            InsnList conversion = new InsnList();
            for (int index = original.length - 1; index >= 0; index--) {
                conversion.add(new VarInsnNode(current[index].getOpcode(Opcodes.ISTORE), slots[index]));
            }
            for (int index = 0; index < original.length; index++) {
                conversion.add(new VarInsnNode(current[index].getOpcode(Opcodes.ILOAD), slots[index]));
                int parameterIndex = index;
                Argument argument = entry.getValue().stream().filter(value -> value.parameter() == parameterIndex).findFirst().orElse(null);
                if (argument != null && argument.encoder() != null) {
                    if (original[index].getSort() == Type.OBJECT) {
                        encodeBoxed(conversion, argument.encoder(), argument.representation());
                    } else {
                        conversion.add(invoke(argument.encoder()));
                    }
                }
                Type actual = argument != null && argument.encoder() != null ? original[index] : current[index];
                for (int opcode : PrimitiveConversions.opcodes(actual, replacement[index])) {
                    conversion.add(new InsnNode(opcode));
                }
            }
            method.instructions.insertBefore(invocation, conversion);
            invocation.owner = first.implementation().owner();
            invocation.name = first.implementation().name();
            invocation.desc = first.implementation().descriptor();
            method.maxLocals = next;
        }
        for (Local local : locals) {
            MethodKey key = local.method();
            var variable = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor()).localVariables.get(local.entryIndex());
            variable.desc = local.descriptor();
            variable.signature = local.signature();
        }
    }

    private static void encodeBoxed(InsnList code, MethodKey encoder, Type representation) {
        LabelNode end = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, end));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, representation.getInternalName()));
        code.add(invoke(encoder));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        code.add(end);
    }

    static MethodInsnNode invoke(MethodKey key) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, key.owner(), key.name(), key.descriptor(), false);
    }
}
