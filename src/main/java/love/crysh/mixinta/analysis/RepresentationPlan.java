package love.crysh.mixinta.analysis;

import love.crysh.mixinta.TypeRule;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;

/** 只保存固定输入的位置和生成参数，apply 每次在独立节点上执行。 */
record RepresentationPlan(List<Edit> edits, List<Argument> arguments, List<Local> locals,
        List<PlanChange> changes, List<PropagationEdge> dependencies) {
    RepresentationPlan {
        edits = List.copyOf(edits);
        arguments = List.copyOf(arguments);
        locals = List.copyOf(locals);
        changes = List.copyOf(changes);
        dependencies = List.copyOf(dependencies);
    }

    record Location(MethodKey method, int instruction) {
    }

    record Edit(Location location, String action, MethodKey helper, Type representation,
            TypeRule.Binding binding, int parameter) {
        void apply(MethodNode method, AbstractInsnNode instruction) {
            switch (action) {
                case "load-reference" -> method.instructions.set(instruction,
                        new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) instruction).var));
                case "store-reference" -> method.instructions.set(instruction,
                        new VarInsnNode(Opcodes.ASTORE, ((VarInsnNode) instruction).var));
                case "cast-reference" -> ((TypeInsnNode) instruction).desc = representation.getInternalName();
                case "remove-boxing" -> method.instructions.set(instruction, new InsnNode(Opcodes.NOP));
                case "replace-unboxing" -> method.instructions.set(instruction, invoke(helper));
                case "encode-return" -> method.instructions.insertBefore(instruction, invoke(helper));
                case "migrate-invocation" -> {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    call.owner = helper.owner();
                    call.name = helper.name();
                    call.desc = helper.descriptor();
                }
                case "replace-producer" -> replaceSource(method, (MethodInsnNode) instruction);
                default -> throw new IllegalStateException("Unknown representation action " + action);
            }
        }

        private void replaceSource(MethodNode method, MethodInsnNode invocation) {
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
    }

    record Argument(Location location, MethodKey original, MethodKey implementation, int parameter,
            Type representation, MethodKey encoder, String migration) {
    }

    record Local(MethodKey method, int index, String descriptor, String signature) {
    }

    void apply(Map<String, ClassNode> nodes, Map<MethodKey, AbstractInsnNode[]> instructions) {
        for (Edit edit : edits) {
            MethodKey key = edit.location.method;
            MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
            edit.apply(method, instructions.get(key)[edit.location.instruction]);
        }
        Map<Location, List<Argument>> grouped = new java.util.LinkedHashMap<>();
        for (Argument argument : arguments) {
            grouped.computeIfAbsent(argument.location(), ignored -> new java.util.ArrayList<>()).add(argument);
        }
        for (var entry : grouped.entrySet()) {
            Location location = entry.getKey();
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
                    conversion.add(invoke(argument.encoder()));
                }
            }
            method.instructions.insertBefore(invocation, conversion);
            invocation.owner = first.implementation().owner();
            invocation.name = first.implementation().name();
            invocation.desc = first.implementation().descriptor();
            method.maxLocals = next;
        }
        for (Local local : locals) {
            MethodKey key = local.method;
            var variable = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor()).localVariables.get(local.index);
            variable.desc = local.descriptor;
            variable.signature = local.signature;
        }
    }

    static MethodInsnNode invoke(MethodKey key) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, key.owner(), key.name(), key.descriptor(), false);
    }
}
