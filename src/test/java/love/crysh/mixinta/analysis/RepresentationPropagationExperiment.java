package love.crysh.mixinta.analysis;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实验覆盖方法内 long 值传播和局部分配容器的元素迁移。
 * 规则由测试注册，结果通过独立 ClassLoader 执行后与人工目标对照。
 */
final class RepresentationPropagationExperiment {
    record Producer(MethodKey original, MethodKey replacement) {
    }

    record Rules(MethodKey target, MethodKey implementation, List<Producer> producers,
            MethodKey requireNonNull, MethodKey encode) {
        Rules {
            producers = List.copyOf(producers);
        }
    }

    record Change(MethodKey method, int instruction, String action) {
    }

    record Result(Map<String, byte[]> classes, List<Change> changes) {
        Result {
            classes = ClassFiles.snapshot(classes);
            changes = List.copyOf(changes);
        }

        @Override
        public Map<String, byte[]> classes() {
            return ClassFiles.snapshot(classes);
        }
    }

    private record Site(Flow flow, int index) {
        MethodKey method() {
            return flow.key;
        }

        String describe() {
            return method() + " instruction=" + index;
        }
    }

    private record Demand(AbstractInsnNode origin, String path) {
    }

    private static final class Flow {
        final MethodKey key;
        final MethodNode method;
        final AbstractInsnNode[] instructions;
        Frame<SourceValue>[] frames;

        Flow(String owner, MethodNode method) {
            this.key = new MethodKey(owner, method.name, method.desc);
            this.method = method;
            this.instructions = method.instructions.toArray();
        }

        SourceValue receiver(int index, MethodInsnNode call) {
            int arguments = Type.getArgumentTypes(call.desc).length;
            Frame<SourceValue> frame = frames[index];
            return frame.getStack(frame.getStackSize() - arguments - 1);
        }

        SourceValue argument(int index, MethodInsnNode call, int parameter) {
            int arguments = Type.getArgumentTypes(call.desc).length;
            Frame<SourceValue> frame = frames[index];
            return frame.getStack(frame.getStackSize() - arguments + parameter);
        }

        SourceValue top(int index) {
            Frame<SourceValue> frame = frames[index];
            return frame.getStack(frame.getStackSize() - 1);
        }
    }

    private final Rules rules;
    private final Map<String, ClassNode> classes;
    private final List<Flow> flows = new ArrayList<>();
    private final Map<AbstractInsnNode, Site> sites = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> migrated = new LinkedHashSet<>();
    private final Set<AbstractInsnNode> storages = new LinkedHashSet<>();
    private final Map<AbstractInsnNode, String> paths = new IdentityHashMap<>();
    private final ArrayDeque<Demand> pending = new ArrayDeque<>();
    private final Set<AbstractInsnNode> requested = new HashSet<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<String> diagnosticKeys = new HashSet<>();
    private final List<Change> changes = new ArrayList<>();
    private final Type representation;

    private RepresentationPropagationExperiment(Map<String, byte[]> input, Rules rules) {
        this.rules = rules;
        this.classes = ClassFiles.readAll(ClassFiles.snapshot(input), diagnostics);
        this.representation = Type.getArgumentTypes(rules.implementation.descriptor())[0];
    }

    static Result transform(Map<String, byte[]> input, Rules rules) {
        return new RepresentationPropagationExperiment(input, rules).run();
    }

    private Result run() {
        validateRules();
        ClassFiles.throwIfErrors(diagnostics);
        analyzeMethods();
        ClassFiles.throwIfErrors(diagnostics);
        seedTargets();
        int previous;
        do {
            previous = requested.size() + storages.size();
            solveDemands();
            propagateStorage();
            unifyLocalMerges();
        } while (!pending.isEmpty() || previous != requested.size() + storages.size());
        validateUses();
        ClassFiles.throwIfErrors(diagnostics);
        for (Flow flow : flows) {
            rewrite(flow);
        }
        installImplementation();
        return new Result(writeAndVerify(), changes);
    }

    private void validateRules() {
        Type[] original = Type.getArgumentTypes(rules.target.descriptor());
        Type[] replacement = Type.getArgumentTypes(rules.implementation.descriptor());
        if (original.length != 1 || !original[0].equals(Type.LONG_TYPE)
                || replacement.length != 1 || replacement[0].getSort() != Type.OBJECT
                || !Type.getReturnType(rules.target.descriptor()).equals(Type.getReturnType(rules.implementation.descriptor()))) {
            throw new IllegalArgumentException("Experiment requires one long parameter migrating to a reference and the original return type");
        }
        requireMethod(rules.target);
        requireStatic(rules.target);
        requireStatic(rules.implementation);
        String keepDescriptor = Type.getMethodDescriptor(representation, representation);
        if (!rules.requireNonNull.descriptor().equals(keepDescriptor)) {
            throw new IllegalArgumentException("requireNonNull must accept and return the representation");
        }
        requireStatic(rules.requireNonNull);
        if (rules.encode != null) {
            if (!rules.encode.descriptor().equals(Type.getMethodDescriptor(Type.LONG_TYPE, representation))) {
                throw new IllegalArgumentException("encode must convert the representation to long");
            }
            requireStatic(rules.encode);
        }
        Set<MethodKey> claimed = new HashSet<>();
        for (Producer producer : rules.producers) {
            if (!claimed.add(producer.original)) {
                throw new IllegalArgumentException("Duplicate producer " + producer.original);
            }
            MethodNode source = requireMethod(producer.original);
            if ((source.access & Opcodes.ACC_STATIC) != 0 || Type.getArgumentTypes(source.desc).length != 0
                    || !Type.getReturnType(source.desc).equals(Type.LONG_TYPE)
                    || !producer.original.owner().equals(representation.getInternalName())
                    || !producer.replacement.descriptor().equals(keepDescriptor)) {
                throw new IllegalArgumentException("Producer must replace a zero-argument reference receiver call returning long");
            }
            requireStatic(producer.replacement);
        }
    }

    private MethodNode requireMethod(MethodKey key) {
        ClassNode owner = classes.get(key.owner());
        MethodNode method = owner == null ? null : ClassFiles.findMethod(owner, key.name(), key.descriptor());
        if (method == null) {
            throw new IllegalArgumentException("Missing experiment method " + key);
        }
        return method;
    }

    private void requireStatic(MethodKey key) {
        if ((requireMethod(key).access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalArgumentException("Expected static method " + key);
        }
    }

    private void analyzeMethods() {
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || new MethodKey(owner.name, method.name, method.desc).equals(rules.target)) {
                    continue;
                }
                Flow flow = new Flow(owner.name, method);
                flows.add(flow);
                for (int index = 0; index < flow.instructions.length; index++) {
                    sites.put(flow.instructions[index], new Site(flow, index));
                }
                try {
                    flow.frames = new Analyzer<>(new Origins(flow)).analyze(owner.name, method);
                } catch (AnalyzerException failure) {
                    problem("INVALID_EXPERIMENT_INPUT", new Site(flow, -1), failure.getMessage());
                }
            }
        }
    }

    private final class Origins extends SourceInterpreter {
        private final Flow flow;
        private final Map<Integer, LabelNode> parameters = new HashMap<>();

        Origins(Flow flow) {
            super(Opcodes.ASM9);
            this.flow = flow;
        }

        @Override
        public SourceValue newParameterValue(boolean instance, int local, Type type) {
            LabelNode parameter = parameters.computeIfAbsent(local, slot -> {
                LabelNode label = new LabelNode();
                sites.put(label, new Site(flow, -1 - slot));
                return label;
            });
            return new SourceValue(type.getSize(), parameter);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            return value;
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
    }

    private void seedTargets() {
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call && is(call, rules.target)) {
                    request(flow.argument(index, call, 0), rules.target + " parameter=0");
                }
            }
        }
    }

    private void request(SourceValue value, String path) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            if (requested.add(origin)) {
                paths.put(origin, path);
                pending.add(new Demand(origin, path));
            }
        }
    }

    private void solveDemands() {
        while (!pending.isEmpty()) {
            Demand demand = pending.removeFirst();
            AbstractInsnNode origin = demand.origin;
            Site site = sites.get(origin);
            if (origin instanceof MethodInsnNode call) {
                if (producer(call) != null) {
                    migrated.add(origin);
                } else if (isBox(call)) {
                    migrated.add(origin);
                    request(site.flow.argument(site.index, call, 0), demand.path + " <- Long.valueOf at " + site.describe());
                } else if (isUnbox(call)) {
                    migrated.add(origin);
                    request(site.flow.receiver(site.index, call), demand.path + " <- Long.longValue at " + site.describe());
                } else if (isContainerRead(call)) {
                    migrated.add(origin);
                    requireStorage(site.flow.receiver(site.index, call), demand.path + " <- poll at " + site.describe());
                } else {
                    problem("MISSING_PRODUCER_RULE", site, demand.path + " <- " + symbol(call));
                }
            } else {
                problem("MISSING_PRODUCER_RULE", site, demand.path + " <- source at " + site.describe());
            }
        }
    }

    private void requireStorage(SourceValue value, String path) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && ContainerSummary.supportsAllocation(allocation.desc)) {
                if (storages.add(origin)) {
                    paths.put(origin, path);
                }
            } else {
                problem("UNRESOLVED_CONTAINER", sites.get(origin), path + " <- unknown storage at " + sites.get(origin).describe());
            }
        }
    }

    private void propagateStorage() {
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null || !(flow.instructions[index] instanceof MethodInsnNode call)
                        || call.getOpcode() == Opcodes.INVOKESTATIC) {
                    continue;
                }
                SourceValue receiver = flow.receiver(index, call);
                if (!touches(receiver, storages)) {
                    continue;
                }
                String path = storagePath(receiver) + " <- " + call.name + " at " + new Site(flow, index).describe();
                ContainerSummary summary = ContainerSummary.resolve(call);
                if (summary == null) {
                    continue;
                }
                if (isContainerWrite(call)) {
                    requireStorage(receiver, path);
                    request(flow.argument(index, call, summary.elementParameter()), path);
                } else if (isContainerRead(call)) {
                    requireStorage(receiver, path);
                    request(new SourceValue(1, call), path);
                }
            }
        }
        // 所有读取方都要跟随共享元素迁移，包含最终传入旧消费者的读取。
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && isUnbox(call) && touches(flow.receiver(index, call), migrated)) {
                    request(new SourceValue(2, call), pathFor(flow.receiver(index, call)));
                }
            }
        }
    }

    private void unifyLocalMerges() {
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null || !(flow.instructions[index] instanceof VarInsnNode variable)) {
                    continue;
                }
                SourceValue value = switch (variable.getOpcode()) {
                    case Opcodes.LLOAD -> flow.frames[index].getLocal(variable.var);
                    case Opcodes.LSTORE -> flow.top(index);
                    default -> null;
                };
                if (value != null && touches(value, migrated)) {
                    request(value, pathFor(value) + " <- local merge at " + new Site(flow, index).describe());
                }
            }
        }
    }

    private void validateUses() {
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null) {
                    continue;
                }
                AbstractInsnNode instruction = flow.instructions[index];
                Site site = new Site(flow, index);
                if (instruction instanceof MethodInsnNode call) {
                    validateCall(site, call);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    checkReference(dynamic.bsm, site);
                    for (Object argument : dynamic.bsmArgs) {
                        checkReference(argument, site);
                    }
                    int arguments = Type.getArgumentTypes(dynamic.desc).length;
                    Frame<SourceValue> frame = flow.frames[index];
                    for (int parameter = 0; parameter < arguments; parameter++) {
                        SourceValue value = frame.getStack(frame.getStackSize() - arguments + parameter);
                        if (touches(value, storages)) {
                            problem("UNKNOWN_CONTAINER_USE", site, storagePath(value) + " <- invokedynamic capture at " + site.describe());
                        }
                        if (touches(value, migrated)) {
                            problem("UNSUPPORTED_VALUE_USE", site, pathFor(value) + " <- invokedynamic argument at " + site.describe());
                        }
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    checkReference(constant.cst, site);
                } else if (instruction.getOpcode() == Opcodes.ARETURN || instruction.getOpcode() == Opcodes.PUTSTATIC
                        || instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.AASTORE) {
                    if (touches(flow.top(index), storages)) {
                        problem("ESCAPED_CONTAINER", site, storagePath(flow.top(index)) + " <- storage escapes at " + site.describe());
                    }
                    if (touches(flow.top(index), migrated)) {
                        problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.top(index)) + " <- reference sink at " + site.describe());
                    }
                } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                    requireEncoder(site);
                } else if (usesUnsupportedStackOperation(instruction) && touchesStack(flow.frames[index], migrated)) {
                    problem("UNSUPPORTED_VALUE_USE", site, "Migrated value reaches opcode=" + instruction.getOpcode());
                }
            }
        }
    }

    private void checkReference(Object constant, Site site) {
        if (constant instanceof Handle handle && handle.getOwner().equals(rules.target.owner())
                && handle.getName().equals(rules.target.name()) && handle.getDesc().equals(rules.target.descriptor())) {
            problem("UNSUPPORTED_METHOD_REFERENCE", site, "Target reference needs migration at " + site.describe());
        } else if (constant instanceof ConstantDynamic dynamic) {
            checkReference(dynamic.getBootstrapMethod(), site);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkReference(dynamic.getBootstrapMethodArgument(index), site);
            }
        }
    }

    private void validateCall(Site site, MethodInsnNode call) {
        Flow flow = site.flow;
        int index = site.index;
        boolean receiverPresent = call.getOpcode() != Opcodes.INVOKESTATIC;
        if (receiverPresent && touches(flow.receiver(index, call), storages) && ContainerSummary.resolve(call) == null) {
            problem("UNKNOWN_CONTAINER_USE", site, storagePath(flow.receiver(index, call)) + " <- " + symbol(call));
        }
        if (receiverPresent && touches(flow.receiver(index, call), migrated)
                && !(isUnbox(call) && migrated.contains(call))) {
            problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.receiver(index, call)) + " <- receiver of " + symbol(call));
        }
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int parameter = 0; parameter < arguments.length; parameter++) {
            SourceValue value = flow.argument(index, call, parameter);
            if (touches(value, storages)) {
                problem("UNKNOWN_CONTAINER_USE", site, storagePath(value) + " <- argument of " + symbol(call));
            }
            if (!touches(value, migrated)) {
                continue;
            }
            if (is(call, rules.target) || isBox(call) && migrated.contains(call)
                    || isContainerWrite(call) && ContainerSummary.resolve(call).elementParameter() == parameter
                    && touches(flow.receiver(index, call), storages)) {
                continue;
            }
            if (arguments[parameter].equals(Type.LONG_TYPE)) {
                requireEncoder(site);
            } else {
                problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(value) + " <- argument of " + symbol(call));
            }
        }
    }

    private boolean usesUnsupportedStackOperation(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return opcode >= Opcodes.IADD && opcode <= Opcodes.LXOR
                || opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG
                || opcode >= Opcodes.POP && opcode <= Opcodes.SWAP;
    }

    private boolean touchesStack(Frame<SourceValue> frame, Set<AbstractInsnNode> origins) {
        for (int index = 0; index < frame.getStackSize(); index++) {
            if (touches(frame.getStack(index), origins)) {
                return true;
            }
        }
        return false;
    }

    private void requireEncoder(Site site) {
        if (rules.encode == null) {
            problem("MISSING_BOUNDARY_CONVERSION", site, "Legacy long consumer needs an encoder at " + site.describe());
        }
    }

    private void rewrite(Flow flow) {
        for (int index = 0; index < flow.instructions.length; index++) {
            AbstractInsnNode instruction = flow.instructions[index];
            if (instruction instanceof FrameNode) {
                flow.method.instructions.remove(instruction);
            }
            if (flow.frames[index] == null) {
                continue;
            }
            if (instruction instanceof VarInsnNode variable) {
                if (variable.getOpcode() == Opcodes.LLOAD && touches(flow.frames[index].getLocal(variable.var), migrated)) {
                    replace(flow, index, new VarInsnNode(Opcodes.ALOAD, variable.var), "load-reference");
                } else if (variable.getOpcode() == Opcodes.LSTORE && touches(flow.top(index), migrated)) {
                    replace(flow, index, new VarInsnNode(Opcodes.ASTORE, variable.var), "store-reference");
                }
            } else if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("java/lang/Long") && touches(flow.top(index), migrated)) {
                cast.desc = representation.getInternalName();
                change(flow, index, "cast-reference");
            } else if (instruction instanceof MethodInsnNode call) {
                rewriteCall(flow, index, call);
            } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                flow.method.instructions.insertBefore(instruction, invoke(rules.encode));
                change(flow, index, "encode-return");
            }
        }
        updateLocalMetadata(flow);
    }

    private void rewriteCall(Flow flow, int index, MethodInsnNode call) {
        Producer producer = producer(call);
        if (producer != null && migrated.contains(call)) {
            replace(flow, index, invoke(producer.replacement), "replace-producer");
        } else if (isBox(call) && migrated.contains(call)) {
            replace(flow, index, new InsnNode(Opcodes.NOP), "remove-boxing");
        } else if (isUnbox(call) && migrated.contains(call)) {
            replace(flow, index, invoke(rules.requireNonNull), "replace-unboxing");
        } else if (is(call, rules.target)) {
            call.desc = rules.implementation.descriptor();
            change(flow, index, "migrate-invocation");
        } else {
            adaptLegacyArguments(flow, index, call);
        }
    }

    private void adaptLegacyArguments(Flow flow, int index, MethodInsnNode call) {
        Type[] original = Type.getArgumentTypes(call.desc);
        boolean[] encode = new boolean[original.length];
        boolean needed = false;
        for (int parameter = 0; parameter < original.length; parameter++) {
            encode[parameter] = original[parameter].equals(Type.LONG_TYPE)
                    && touches(flow.argument(index, call, parameter), migrated);
            needed |= encode[parameter];
        }
        if (!needed) {
            return;
        }
        Type[] actual = original.clone();
        int[] locals = new int[original.length];
        int next = flow.method.maxLocals;
        for (int parameter = 0; parameter < original.length; parameter++) {
            if (encode[parameter]) {
                actual[parameter] = representation;
            }
            locals[parameter] = next;
            next += actual[parameter].getSize();
        }
        InsnList conversion = new InsnList();
        for (int parameter = original.length - 1; parameter >= 0; parameter--) {
            conversion.add(new VarInsnNode(actual[parameter].getOpcode(Opcodes.ISTORE), locals[parameter]));
        }
        for (int parameter = 0; parameter < original.length; parameter++) {
            conversion.add(new VarInsnNode(actual[parameter].getOpcode(Opcodes.ILOAD), locals[parameter]));
            if (encode[parameter]) {
                conversion.add(invoke(rules.encode));
            }
        }
        flow.method.maxLocals = next;
        flow.method.instructions.insertBefore(call, conversion);
        change(flow, index, "encode-arguments");
    }

    private void updateLocalMetadata(Flow flow) {
        if (flow.method.localVariables == null) {
            return;
        }
        for (var variable : flow.method.localVariables) {
            int start = Arrays.asList(flow.instructions).indexOf(variable.start);
            int end = Arrays.asList(flow.instructions).indexOf(variable.end);
            for (int index = start; index < end; index++) {
                Frame<SourceValue> frame = flow.frames[index];
                if (frame == null || variable.index >= frame.getLocals()) {
                    continue;
                }
                SourceValue value = frame.getLocal(variable.index);
                if ((variable.desc.equals("J") || variable.desc.equals("Ljava/lang/Long;")) && touches(value, migrated)) {
                    variable.desc = representation.getDescriptor();
                    variable.signature = null;
                    break;
                }
                if (variable.signature != null && touches(value, storages)) {
                    variable.signature = variable.signature.replace("Ljava/lang/Long;", representation.getDescriptor());
                    break;
                }
            }
        }
    }

    private void installImplementation() {
        ClassNode owner = classes.get(rules.target.owner());
        MethodNode original = requireMethod(rules.target);
        MethodNode copy = new MethodNode(Opcodes.ASM9, original.access, rules.target.name(),
                rules.implementation.descriptor(), null, null);
        requireMethod(rules.implementation).accept(copy);
        copy.access = original.access;
        copy.name = original.name;
        owner.methods.remove(original);
        owner.methods.add(copy);
        changes.add(new Change(rules.target, -1, "install-implementation"));
    }

    private Map<String, byte[]> writeAndVerify() {
        Map<String, byte[]> output = new LinkedHashMap<>();
        ClassHierarchy hierarchy = new ClassHierarchy(classes, getClass().getClassLoader());
        for (ClassNode node : classes.values()) {
            try {
                var writer = hierarchy.writer();
                node.accept(writer);
                byte[] bytes = writer.toByteArray();
                ClassNode verified = ClassFiles.read(bytes);
                for (MethodNode method : verified.methods) {
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                        new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                    }
                }
                output.put(node.name, bytes);
            } catch (AnalyzerException | RuntimeException failure) {
                diagnostics.add(new Diagnostic("EXPERIMENT_OUTPUT_INVALID", null, -1, -1, node.name + " " + failure.getMessage()));
            }
        }
        ClassFiles.throwIfErrors(diagnostics);
        return output;
    }

    private void replace(Flow flow, int index, AbstractInsnNode replacement, String action) {
        flow.method.instructions.set(flow.instructions[index], replacement);
        change(flow, index, action);
    }

    private void change(Flow flow, int index, String action) {
        changes.add(new Change(flow.key, index, action));
    }

    private Producer producer(MethodInsnNode call) {
        return rules.producers.stream().filter(rule -> is(call, rule.original)).findFirst().orElse(null);
    }

    private static boolean isContainerWrite(MethodInsnNode call) {
        ContainerSummary summary = ContainerSummary.resolve(call);
        return summary != null && summary.writesElement();
    }

    private static boolean isContainerRead(MethodInsnNode call) {
        ContainerSummary summary = ContainerSummary.resolve(call);
        return summary != null && summary.readsElement();
    }

    private static boolean isBox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("valueOf") && call.desc.equals("(J)Ljava/lang/Long;");
    }

    private static boolean isUnbox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("longValue") && call.desc.equals("()J");
    }

    private static boolean is(MethodInsnNode call, MethodKey key) {
        return call.owner.equals(key.owner()) && call.name.equals(key.name()) && call.desc.equals(key.descriptor());
    }

    private static MethodKey symbol(MethodInsnNode call) {
        return new MethodKey(call.owner, call.name, call.desc);
    }

    private static MethodInsnNode invoke(MethodKey key) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, key.owner(), key.name(), key.descriptor(), false);
    }

    private static boolean touches(SourceValue value, Set<AbstractInsnNode> selected) {
        return value != null && value.insns.stream().anyMatch(selected::contains);
    }

    private List<AbstractInsnNode> ordered(Set<AbstractInsnNode> origins) {
        return origins.stream().sorted((left, right) -> {
            Site first = sites.get(left);
            Site second = sites.get(right);
            int method = first.method().toString().compareTo(second.method().toString());
            return method == 0 ? Integer.compare(first.index, second.index) : method;
        }).toList();
    }

    private String pathFor(SourceValue value) {
        return ordered(value.insns).stream().filter(paths::containsKey).map(paths::get).findFirst().orElse("value migration");
    }

    private String storagePath(SourceValue value) {
        return ordered(value.insns).stream().filter(storages::contains).map(paths::get).findFirst().orElse("container migration");
    }

    private void problem(String code, Site site, String message) {
        String key = code + " " + site.describe();
        if (diagnosticKeys.add(key)) {
            diagnostics.add(new Diagnostic(code, site.method(), Math.max(-1, site.index), -1, message));
        }
    }
}
