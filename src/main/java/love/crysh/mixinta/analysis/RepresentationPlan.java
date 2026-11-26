package love.crysh.mixinta.analysis;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.PrimitiveConversions;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.PlanChange;
import love.crysh.mixinta.model.PropagationEdge;

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

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/** 只保存固定输入的位置和生成参数，apply 每次在独立节点上执行。 */
record RepresentationPlan(List<Edit> edits, List<Argument> arguments, List<Local> locals,
        List<Bridge> bridges, List<PlanChange> changes, List<PropagationEdge> dependencies) {
    RepresentationPlan {
        edits = List.copyOf(edits);
        arguments = List.copyOf(arguments);
        locals = List.copyOf(locals);
        bridges = List.copyOf(bridges);
        changes = List.copyOf(changes);
        dependencies = List.copyOf(dependencies);
    }

    static RepresentationPlan merge(List<RepresentationPlan> plans, Map<String, ClassNode> classes, List<Diagnostic> diagnostics) {
        Map<Location, Edit> edits = new LinkedHashMap<>();
        Map<Location, Map<Integer, Argument>> arguments = new LinkedHashMap<>();
        Map<Location, Local> locals = new LinkedHashMap<>();
        Map<MethodKey, Bridge> bridges = new LinkedHashMap<>();
        for (RepresentationPlan plan : plans) {
            for (Edit edit : plan.edits()) {
                Edit previous = edits.putIfAbsent(edit.location(), edit);
                if (previous != null && !sameEdit(previous, edit)) {
                    conflict(edit.location(), "Instruction requires different edits", diagnostics,
                            List.of(previous.source(), edit.source()));
                }
            }
            for (Argument argument : plan.arguments()) {
                var parameters = arguments.computeIfAbsent(argument.location(), ignored -> new LinkedHashMap<>());
                Argument previous = parameters.putIfAbsent(argument.parameter(), argument);
                if (previous != null && (!previous.original().equals(argument.original())
                        || !previous.implementation().equals(argument.implementation())
                        || !previous.representation().equals(argument.representation())
                        || !Objects.equals(previous.encoder(), argument.encoder()))) {
                    conflict(argument.location(), "Argument requires incompatible conversions parameter=" + argument.parameter(), diagnostics,
                            List.of(previous.source(), argument.source()));
                }
            }
            for (Local local : plan.locals()) {
                Location key = new Location(local.method(), local.index());
                Local previous = locals.putIfAbsent(key, local);
                if (previous == null || previous.equals(local)) continue;
                try {
                    if (!previous.descriptor().equals(local.descriptor())) {
                        throw new IllegalArgumentException("Local variable requires incompatible descriptors");
                    }
                    var original = ClassFiles.findMethod(classes.get(local.method().owner()), local.method().name(),
                            local.method().descriptor()).localVariables.get(local.index());
                    locals.put(key, new Local(local.method(), local.index(), local.descriptor(),
                            mergeSignature(original.signature, previous.signature(), local.signature()), previous.source()));
                } catch (IllegalArgumentException failure) {
                    conflict(new Location(local.method(), -1), failure.getMessage() + " local=" + local.index(), diagnostics,
                            List.of(previous.source(), local.source()));
                }
            }
            for (Bridge bridge : plan.bridges()) {
                Bridge previous = bridges.putIfAbsent(bridge.method(), bridge);
                if (previous != null && !previous.method().equals(bridge.method())
                        || previous != null && !previous.helper().equals(bridge.helper())) {
                    conflict(new Location(bridge.method(), -1), "Rule bridge requires different helpers", diagnostics,
                            List.of(previous.source(), bridge.source()));
                }
            }
        }
        var argumentEntries = arguments.entrySet().iterator();
        while (argumentEntries.hasNext()) {
            var entry = argumentEntries.next();
            if (entry.getValue().values().stream().map(Argument::implementation).distinct().count() > 1) {
                conflict(entry.getKey(), "Invocation requires incompatible final descriptors", diagnostics,
                        entry.getValue().values().stream().map(Argument::source).toList());
            }
            if (edits.containsKey(entry.getKey())) {
                Edit edit = edits.get(entry.getKey());
                Argument query = entry.getValue().get(0);
                if (edit.observation() && entry.getValue().size() == 1 && query != null && query.encoder() != null
                        && query.original().equals(query.implementation())) {
                    edits.put(entry.getKey(), new Edit(edit.location(), edit.action(), edit.helper(), edit.representation(), edit.binding(),
                            edit.parameter(), new Encoding(query.representation(), query.encoder()), edit.source()));
                    argumentEntries.remove();
                } else conflict(entry.getKey(), "Invocation replacement overlaps argument conversion", diagnostics,
                        java.util.stream.Stream.concat(java.util.stream.Stream.of(edit.source()),
                                entry.getValue().values().stream().map(Argument::source)).toList());
            }
        }
        return new RepresentationPlan(List.copyOf(edits.values()), arguments.values().stream()
                .flatMap(parameters -> parameters.values().stream().sorted(java.util.Comparator.comparingInt(Argument::parameter))).toList(),
                List.copyOf(locals.values()), List.copyOf(bridges.values()),
                plans.stream().flatMap(plan -> plan.changes().stream()).distinct().toList(),
                plans.stream().flatMap(plan -> plan.dependencies().stream()).distinct().toList());
    }

    private static void conflict(Location location, String message, List<Diagnostic> diagnostics) {
        conflict(location, message, diagnostics, List.of());
    }

    private static boolean sameEdit(Edit first, Edit second) {
        return first.location().equals(second.location()) && first.action().equals(second.action())
                && Objects.equals(first.helper(), second.helper()) && first.representation().equals(second.representation())
                && first.binding() == second.binding() && first.parameter() == second.parameter()
                && Objects.equals(first.query(), second.query());
    }

    private static void conflict(Location location, String message, List<Diagnostic> diagnostics,
            List<love.crysh.mixinta.diagnostic.MigrationRequirement> requirements) {
        diagnostics.add(new Diagnostic("REPRESENTATION_PLAN_CONFLICT", location.method(), location.instruction(), -1, message)
                .withMigrations(requirements.stream().filter(Objects::nonNull).distinct().toList()));
    }

    private static String mergeSignature(String original, String first, String second) {
        if (Objects.equals(first, second)) return first;
        if (Objects.equals(original, first)) return second;
        if (Objects.equals(original, second)) return first;
        if (original == null || first == null || second == null) {
            throw new IllegalArgumentException("Local variable requires incompatible generic signatures");
        }
        String[] parts = original.split("Ljava/lang/Long;", -1);
        String pattern = java.util.Arrays.stream(parts).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("(L[^;<>]+;)"));
        var left = Pattern.compile(pattern).matcher(first);
        var right = Pattern.compile(pattern).matcher(second);
        if (!left.matches() || !right.matches()) {
            throw new IllegalArgumentException("Generic signature changes have incompatible structure");
        }
        StringBuilder result = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            String a = left.group(index);
            String b = right.group(index);
            if (!a.equals(b) && !a.equals("Ljava/lang/Long;") && !b.equals("Ljava/lang/Long;")) {
                throw new IllegalArgumentException("Generic argument requires incompatible representations");
            }
            result.append(a.equals("Ljava/lang/Long;") ? b : a).append(parts[index]);
        }
        return result.toString();
    }

    record Location(MethodKey method, int instruction) {
    }

    record Encoding(Type representation, MethodKey helper) { }

    record Edit(Location location, String action, MethodKey helper, Type representation,
            TypeRule.Binding binding, int parameter, Encoding query,
            love.crysh.mixinta.diagnostic.MigrationRequirement source) {
        Edit(Location location, String action, MethodKey helper, Type representation, TypeRule.Binding binding, int parameter) {
            this(location, action, helper, representation, binding, parameter, null, null);
        }

        Edit(Location location, String action, MethodKey helper, Type representation, TypeRule.Binding binding, int parameter,
                love.crysh.mixinta.diagnostic.MigrationRequirement source) {
            this(location, action, helper, representation, binding, parameter, null, source);
        }

        boolean observation() {
            return action.equals("observe-map-values") || action.equals("observe-view-elements");
        }

        void apply(ClassNode owner, MethodNode method, AbstractInsnNode instruction) {
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
                case "observe-map-values", "observe-view-elements" -> observeElements(owner, method, (MethodInsnNode) instruction);
                default -> throw new IllegalStateException("Unknown representation action " + action);
            }
        }

        private void observeElements(ClassNode owner, MethodNode method, MethodInsnNode invocation) {
            boolean map = ContainerSummary.mapOwner(invocation.owner);
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
            Type representation, MethodKey encoder, String migration,
            love.crysh.mixinta.diagnostic.MigrationRequirement source) {
    }

    record Local(MethodKey method, int index, String descriptor, String signature,
            love.crysh.mixinta.diagnostic.MigrationRequirement source) {
    }

    record Bridge(MethodKey method, MethodKey helper, love.crysh.mixinta.diagnostic.MigrationRequirement source) {
        void apply(ClassNode owner) {
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
    }

    void apply(Map<String, ClassNode> nodes, Map<MethodKey, AbstractInsnNode[]> instructions) {
        for (Bridge bridge : bridges) {
            bridge.apply(nodes.get(bridge.method().owner()));
        }
        for (Edit edit : edits) {
            MethodKey key = edit.location.method;
            MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
            edit.apply(nodes.get(key.owner()), method, instructions.get(key)[edit.location.instruction]);
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
            MethodKey key = local.method;
            var variable = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor()).localVariables.get(local.index);
            variable.desc = local.descriptor;
            variable.signature = local.signature;
        }
    }

    /** 保留 Map 的 null key 和 null value，非空表示通过编码恢复 Long 比较语义。 */
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
