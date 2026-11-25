package love.crysh.mixinta.analysis;

import love.crysh.mixinta.TypeRule;
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

/** 从固定 Mixin 后快照求解一组具有相同迁移身份的值与存储要求。 */
final class RepresentationSolver {
    record Analysis(RepresentationPlan plan, Set<RepresentationPlan.Location> values,
            Set<RepresentationPlan.Location> storages) {
    }

    private record Site(Flow flow, int index) {
        MethodKey method() {
            return flow.key;
        }

        String describe() {
            return method() + " instruction=" + index;
        }

        String symbol() {
            if (index < 0 || index >= flow.instructions.length) {
                return method().toString();
            }
            AbstractInsnNode instruction = flow.instructions[index];
            if (instruction instanceof MethodInsnNode call) {
                return call.owner + "." + call.name + call.desc;
            }
            return method().toString();
        }

        ProgramPoint point() {
            return new ProgramPoint(method(), index, symbol());
        }

        ProgramPoint point(String detail) {
            return new ProgramPoint(method(), index, symbol(), detail);
        }
    }

    private record Demand(AbstractInsnNode origin, ProgramPoint parent) {
    }

    private static final class Flow {
        final MethodKey key;
        final MethodNode method;
        final AbstractInsnNode[] instructions;
        Frame<SourceValue>[] frames;
        final Map<AbstractInsnNode, Set<AbstractInsnNode>> consumed = new IdentityHashMap<>();

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

    private final List<RepresentationMigration> migrations;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final List<Flow> flows = new ArrayList<>();
    private final Map<AbstractInsnNode, Site> sites = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> migrated = new LinkedHashSet<>();
    private final Set<AbstractInsnNode> storages = new LinkedHashSet<>();
    private final ArrayDeque<Demand> pending = new ArrayDeque<>();
    private final Set<AbstractInsnNode> requested = new HashSet<>();
    private final List<Diagnostic> diagnostics;
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> diagnosticKeys = new HashSet<>();
    private final Set<PropagationEdge> dependencies = new LinkedHashSet<>();
    private final Map<AbstractInsnNode, RepresentationMigration.Rule> selected = new IdentityHashMap<>();
    private final List<RepresentationPlan.Edit> edits = new ArrayList<>();
    private final List<RepresentationPlan.Argument> arguments = new ArrayList<>();
    private final List<RepresentationPlan.Local> locals = new ArrayList<>();
    private final List<PlanChange> changes = new ArrayList<>();
    private final Type representation;

    private record Issue(String code, Site site, String message, List<String> candidates) {
    }

    RepresentationSolver(Map<String, ClassNode> classes, List<RepresentationMigration> migrations,
            Map<MethodKey, Migration> targets, ClassHierarchy hierarchy, List<Diagnostic> diagnostics) {
        this.classes = classes;
        this.migrations = List.copyOf(migrations);
        this.migration = migrations.getFirst();
        this.targets = targets;
        this.hierarchy = hierarchy;
        this.diagnostics = diagnostics;
        this.representation = migration.representation();
    }

    Analysis analyze() {
        analyzeMethods();
        if (flows.stream().anyMatch(flow -> flow.frames == null)) {
            finishDiagnostics();
            return result();
        }
        seedTargets();
        int previous;
        do {
            previous = requested.size() + storages.size();
            solveDemands();
            propagateStorage();
            unifyLocalMerges();
        } while (!pending.isEmpty() || previous != requested.size() + storages.size());
        validateUses();
        finishDiagnostics();
        if (issues.isEmpty()) {
            for (Flow flow : flows) {
                plan(flow);
            }
        }
        return result();
    }

    private Analysis result() {
        return new Analysis(new RepresentationPlan(edits, arguments, locals, changes, orderedEdges(dependencies)),
                migrated.stream().map(origin -> location(sites.get(origin))).collect(java.util.stream.Collectors.toSet()),
                storages.stream().map(origin -> location(sites.get(origin))).collect(java.util.stream.Collectors.toSet()));
    }

    private void analyzeMethods() {
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || targets.containsKey(new MethodKey(owner.name, method.name, method.desc))) {
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
                    problem("INVALID_PROPAGATION_INPUT", new Site(flow, -1), failure.getMessage());
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

        private void consume(AbstractInsnNode instruction, SourceValue... values) {
            Set<AbstractInsnNode> origins = flow.consumed.computeIfAbsent(instruction, ignored -> new HashSet<>());
            for (SourceValue value : values) {
                origins.addAll(value.insns);
            }
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            consume(instruction, value);
            return value;
        }

        @Override
        public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second) {
            consume(instruction, first, second);
            return super.binaryOperation(instruction, first, second);
        }

        @Override
        public SourceValue ternaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second, SourceValue third) {
            consume(instruction, first, second, third);
            return super.ternaryOperation(instruction, first, second, third);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, SourceValue value, SourceValue expected) {
            consume(instruction, value);
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            consume(instruction, value);
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
    }

    private void seedTargets() {
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null || !(flow.instructions[index] instanceof MethodInsnNode call)) {
                    continue;
                }
                Migration target = target(call);
                if (target == null) {
                    continue;
                }
                for (RepresentationMigration declaration : migrations) {
                    if (target.original().equals(declaration.target())) {
                        request(flow.argument(index, call, declaration.parameter()),
                                new Site(flow, index).point("parameter=" + declaration.parameter()),
                                DependencyKind.TARGET_ARGUMENT);
                    }
                }
            }
        }
    }

    private void request(SourceValue value, ProgramPoint parent, DependencyKind kind) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            dependencies.add(new PropagationEdge(parent, sites.get(origin).point(), kind));
            if (requested.add(origin)) {
                pending.add(new Demand(origin, parent));
            }
        }
    }

    private void solveDemands() {
        while (!pending.isEmpty()) {
            Demand demand = pending.removeFirst();
            AbstractInsnNode origin = demand.origin;
            Site site = sites.get(origin);
            if (origin instanceof MethodInsnNode call) {
                List<RepresentationMigration.Rule> candidates = migration.rules().stream()
                        .filter(rule -> rule.kind() == TypeRule.Kind.SOURCE && is(call, rule.source())).toList();
                if (!candidates.isEmpty()) {
                    migrated.add(origin);
                    if (candidates.size() == 1) {
                        selected.put(origin, candidates.getFirst());
                    } else {
                        candidateProblem("AMBIGUOUS_SOURCE_RULE", site, "Multiple applicable source rules " + symbol(call),
                                candidates.stream().map(rule -> rule.id() + " applicable").toList());
                    }
                } else if (isBox(call)) {
                    migrated.add(origin);
                    request(site.flow.argument(site.index, call, 0), site.point(), DependencyKind.BOXING);
                } else if (isUnbox(call)) {
                    migrated.add(origin);
                    request(site.flow.receiver(site.index, call), site.point(), DependencyKind.UNBOXING);
                } else if (isPoll(call)) {
                    if (!migration.arrayDeque()) {
                        problem("MISSING_CONTAINER_SUMMARY", site, "Missing poll summary " + symbol(call));
                    } else {
                        migrated.add(origin);
                        requireStorage(site.flow.receiver(site.index, call), site.point());
                    }
                } else {
                    candidateProblem("MISSING_PRODUCER_RULE", site, "Missing source relation " + symbol(call),
                            migration.rules().stream().filter(rule -> rule.kind() == TypeRule.Kind.SOURCE)
                                    .map(rule -> rule.id() + " symbol does not match " + symbol(call)).toList());
                }
            } else {
                problem("MISSING_PRODUCER_RULE", site, "Missing source relation at " + site.describe());
            }
        }
    }

    private void requireStorage(SourceValue value, ProgramPoint path) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("java/util/ArrayDeque")) {
                storages.add(origin);
                dependencies.add(new PropagationEdge(path, sites.get(origin).point(), DependencyKind.CONTAINER_ALLOCATION));
            } else {
                problem("UNRESOLVED_CONTAINER", sites.get(origin), path.key() + " <- unknown storage at " + sites.get(origin).describe());
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
                Site site = new Site(flow, index);
                ProgramPoint path = site.point();
                for (AbstractInsnNode storage : ordered(receiver.insns)) {
                    if (storages.contains(storage)) {
                        dependencies.add(new PropagationEdge(sites.get(storage).point(), path,
                                isAdd(call) ? DependencyKind.CONTAINER_WRITE : DependencyKind.CONTAINER_READ));
                    }
                }
                if (isAdd(call)) {
                    requireStorage(receiver, path);
                    request(flow.argument(index, call, 0), site.point(), DependencyKind.CONTAINER_WRITE);
                } else if (isPoll(call)) {
                    requireStorage(receiver, path);
                    request(new SourceValue(1, call), site.point(), DependencyKind.CONTAINER_READ);
                }
            }
        }
        // 所有读取方都要跟随共享元素迁移，包含最终传入旧消费者的读取。
        for (Flow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && isUnbox(call) && touches(flow.receiver(index, call), migrated)) {
                    request(new SourceValue(2, call), firstMigratedPoint(flow.receiver(index, call)), DependencyKind.UNBOXING);
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
                    case Opcodes.LLOAD, Opcodes.ALOAD -> flow.frames[index].getLocal(variable.var);
                    case Opcodes.LSTORE, Opcodes.ASTORE -> flow.top(index);
                    default -> null;
                };
                if (value != null && touches(value, migrated)) {
                    for (AbstractInsnNode source : ordered(value.insns)) {
                        if (migrated.contains(source)) {
                            request(value, new Site(flow, index).point(), DependencyKind.LOCAL_MERGE);
                        }
                    }
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
                } else if (!(instruction instanceof VarInsnNode)
                        && !(instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals("java/lang/Long"))
                        && flow.consumed.getOrDefault(instruction, Set.of()).stream().anyMatch(migrated::contains)) {
                    problem("UNSUPPORTED_VALUE_USE", site, "Migrated value reaches opcode=" + instruction.getOpcode());
                }
                if (!(instruction instanceof MethodInsnNode) && !(instruction instanceof VarInsnNode)
                        && !(instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST)
                        && instruction.getOpcode() != Opcodes.DUP
                        && instruction.getOpcode() != Opcodes.IFNULL && instruction.getOpcode() != Opcodes.IFNONNULL
                        && instruction.getOpcode() != Opcodes.IF_ACMPEQ && instruction.getOpcode() != Opcodes.IF_ACMPNE
                        && flow.consumed.getOrDefault(instruction, Set.of()).stream().anyMatch(storages::contains)) {
                    problem("ESCAPED_CONTAINER", site, "Unmodeled storage use opcode=" + instruction.getOpcode());
                }
            }
        }
    }

    private void checkReference(Object constant, Site site) {
        if (constant instanceof Handle handle && targets.containsKey(new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc()))) {
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
        if (receiverPresent && touches(flow.receiver(index, call), storages) && !isAllowedQueueCall(call)) {
            problem("UNKNOWN_CONTAINER_USE", site, storagePath(flow.receiver(index, call)) + " <- " + symbol(call));
        }
        if (isUnbox(call) && migrated.contains(call)) {
            helper(TypeRule.Kind.READ, site);
        }
        if (receiverPresent && touches(flow.receiver(index, call), migrated)
                && !(isUnbox(call) && migrated.contains(call))) {
            problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.receiver(index, call)) + " <- receiver of " + symbol(call));
        }
        if (selected.containsKey(call) && ((receiverPresent && touches(flow.receiver(index, call), migrated))
                || java.util.stream.IntStream.range(0, Type.getArgumentTypes(call.desc).length)
                .anyMatch(parameter -> touches(flow.argument(index, call, parameter), migrated)))) {
            problem("MIGRATED_SOURCE_INPUT_NOT_IMPLEMENTED", site, "Source replacement consumes another migrated value");
        }
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int parameter = 0; parameter < arguments.length; parameter++) {
            int parameterIndex = parameter;
            SourceValue value = flow.argument(index, call, parameter);
            if (touches(value, storages)) {
                problem("UNKNOWN_CONTAINER_USE", site, storagePath(value) + " <- argument of " + symbol(call));
            }
            if (!touches(value, migrated)) {
                continue;
            }
            Migration target = target(call);
            boolean matchingTarget = target != null && migrations.stream().anyMatch(declaration ->
                    declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
            if (matchingTarget || isBox(call) && migrated.contains(call)
                    || isAdd(call) && touches(flow.receiver(index, call), storages)) {
                continue;
            }
            if (arguments[parameter].equals(Type.LONG_TYPE)) {
                requireEncoder(site);
            } else {
                problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(value) + " <- argument of " + symbol(call));
            }
        }
    }

    private RepresentationMigration.Rule helper(TypeRule.Kind kind, Site site) {
        List<RepresentationMigration.Rule> candidates = migration.rules().stream().filter(rule -> rule.kind() == kind).toList();
        if (candidates.size() != 1) {
            candidateProblem(candidates.isEmpty() ? "MISSING_" + kind + "_RULE" : "AMBIGUOUS_" + kind + "_RULE", site,
                    "Expected one " + kind + " helper", candidates.stream().map(rule -> rule.id() + " applicable").toList());
            return null;
        }
        return candidates.getFirst();
    }

    private void requireEncoder(Site site) {
        helper(TypeRule.Kind.ENCODE, site);
    }

    private void plan(Flow flow) {
        for (int index = 0; index < flow.instructions.length; index++) {
            if (flow.frames[index] == null) {
                continue;
            }
            AbstractInsnNode instruction = flow.instructions[index];
            Site site = new Site(flow, index);
            if (instruction instanceof VarInsnNode variable) {
                if (variable.getOpcode() == Opcodes.LLOAD && touches(flow.frames[index].getLocal(variable.var), migrated)) {
                    edit(site, "load-reference", null);
                } else if (variable.getOpcode() == Opcodes.LSTORE && touches(flow.top(index), migrated)) {
                    edit(site, "store-reference", null);
                }
            } else if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("java/lang/Long") && touches(flow.top(index), migrated)) {
                edit(site, "cast-reference", null);
            } else if (instruction instanceof MethodInsnNode call) {
                if (selected.containsKey(call)) {
                    edit(site, "replace-producer", selected.get(call));
                } else if (isBox(call) && migrated.contains(call)) {
                    edit(site, "remove-boxing", null);
                } else if (isUnbox(call) && migrated.contains(call)) {
                    edit(site, "replace-unboxing", helper(TypeRule.Kind.READ, site));
                } else {
                    Type[] parameters = Type.getArgumentTypes(call.desc);
                    Migration target = target(call);
                    for (int parameter = 0; parameter < parameters.length; parameter++) {
                        int parameterIndex = parameter;
                        boolean matchingTarget = target != null && migrations.stream().anyMatch(declaration ->
                                declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
                        if (!touches(flow.argument(index, call, parameter), migrated) && !matchingTarget) {
                            continue;
                        }
                        if (isAdd(call) && touches(flow.receiver(index, call), storages)) {
                            continue;
                        }
                        int slot = parameter;
                        var encoder = matchingTarget ? null : helper(TypeRule.Kind.ENCODE, site);
                        MethodKey replacement = new MethodKey(target.original().owner(), target.original().name(), target.implementation().descriptor());
                        arguments.add(new RepresentationPlan.Argument(location(site), target.original(), replacement, parameter,
                                representation, encoder == null ? null : encoder.helper(), migration.id()));
                        changes.add(new PlanChange(flow.key, index, matchingTarget ? "reference-argument" : "encode-argument",
                                migration.id(), encoder == null ? "" : encoder.id()));
                    }
                }
            } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                edit(site, "encode-return", helper(TypeRule.Kind.ENCODE, site));
            }
        }
        planLocalMetadata(flow);
    }

    private void edit(Site site, String action, RepresentationMigration.Rule rule) {
        edits.add(new RepresentationPlan.Edit(location(site), action, rule == null ? null : rule.helper(), representation,
                rule == null ? TypeRule.Binding.RECEIVER : rule.binding(), rule == null ? 0 : rule.parameter()));
        changes.add(new PlanChange(site.method(), site.index, action, migration.id(), rule == null ? "" : rule.id()));
    }

    private void planLocalMetadata(Flow flow) {
        if (flow.method.localVariables == null) {
            return;
        }
        for (int local = 0; local < flow.method.localVariables.size(); local++) {
            var variable = flow.method.localVariables.get(local);
            int start = Arrays.asList(flow.instructions).indexOf(variable.start);
            int end = Arrays.asList(flow.instructions).indexOf(variable.end);
            for (int index = start; index < end; index++) {
                Frame<SourceValue> frame = flow.frames[index];
                if (frame == null || variable.index >= frame.getLocals()) {
                    continue;
                }
                SourceValue value = frame.getLocal(variable.index);
                if ((variable.desc.equals("J") || variable.desc.equals("Ljava/lang/Long;")) && touches(value, migrated)) {
                    locals.add(new RepresentationPlan.Local(flow.key, local, representation.getDescriptor(), null));
                    break;
                }
                if (variable.signature != null && touches(value, storages)) {
                    locals.add(new RepresentationPlan.Local(flow.key, local, variable.desc,
                            variable.signature.replace("Ljava/lang/Long;", representation.getDescriptor())));
                    break;
                }
            }
        }
    }

    private Migration target(MethodInsnNode call) {
        if (targets.keySet().stream().noneMatch(key -> key.name().equals(call.name) && key.descriptor().equals(call.desc))) {
            return null;
        }
        return targets.get(hierarchy.resolve(call.owner, call.name, call.desc));
    }

    private static RepresentationPlan.Location location(Site site) {
        return new RepresentationPlan.Location(site.method(), site.index);
    }

    private boolean isAllowedQueueCall(MethodInsnNode call) {
        return migration.arrayDeque() && (isAdd(call) || isPoll(call)
                || queueOwner(call.owner) && call.name.equals("isEmpty") && call.desc.equals("()Z")
                || queueOwner(call.owner) && call.name.equals("size") && call.desc.equals("()I")
                || call.owner.equals("java/util/ArrayDeque") && call.name.equals("<init>") && call.desc.equals("()V"));
    }

    private static boolean queueOwner(String owner) {
        return owner.equals("java/util/Queue") || owner.equals("java/util/Deque") || owner.equals("java/util/ArrayDeque");
    }

    private static boolean isAdd(MethodInsnNode call) {
        return queueOwner(call.owner) && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z");
    }

    private static boolean isPoll(MethodInsnNode call) {
        return queueOwner(call.owner) && call.name.equals("poll") && call.desc.equals("()Ljava/lang/Object;");
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
        return ordered(value.insns).stream().filter(migrated::contains).map(origin -> sites.get(origin).describe())
                .findFirst().orElse("value migration " + migration.id());
    }

    private String storagePath(SourceValue value) {
        return ordered(value.insns).stream().filter(storages::contains).map(origin -> sites.get(origin).describe())
                .findFirst().orElse("container migration " + migration.id());
    }

    private ProgramPoint firstMigratedPoint(SourceValue value) {
        return ordered(value.insns).stream().filter(migrated::contains).map(origin -> sites.get(origin).point())
                .findFirst().orElse(new ProgramPoint(null, -1, "value migration " + migration.id()));
    }

    private void problem(String code, Site site, String message) {
        candidateProblem(code, site, message, List.of());
    }

    private void candidateProblem(String code, Site site, String message, List<String> candidates) {
        String key = code + " " + site.describe();
        if (diagnosticKeys.add(key)) {
            issues.add(new Issue(code, site, message, candidates));
        }
    }

    private void finishDiagnostics() {
        for (Issue issue : issues) {
            Set<ProgramPoint> affected = new HashSet<>();
            affected.add(issue.site.point());
            boolean changed;
            do {
                changed = false;
                for (PropagationEdge edge : dependencies) {
                    if (affected.contains(edge.toPoint())) {
                        changed |= affected.add(edge.fromPoint());
                    }
                }
            } while (changed);
            List<PropagationEdge> relevant = orderedEdges(dependencies.stream()
                    .filter(edge -> affected.contains(edge.toPoint()) && affected.contains(edge.fromPoint())).toList());
            Type originalType = Type.getArgumentTypes(migration.target().descriptor())[migration.parameter()];
            RuleRequirement requirement = requirement(issue.code);
            String sourceType = originalType.getDescriptor();
            String targetType = migration.representation().getDescriptor();
            diagnostics.add(new Diagnostic(issue.code, issue.site.method(), Math.max(-1, issue.site.index), -1,
                    issue.message, migration.id(), relevant, issue.candidates,
                    migration.target(), migration.parameter(), sourceType, targetType, requirement,
                    dependencyPath(issue.site.point(), relevant), issue.site.symbol()));
        }
    }

    private RuleRequirement requirement(String code) {
        return switch (code) {
            case "MISSING_PRODUCER_RULE" -> new RuleRequirement("SOURCE", "RECEIVER", representationType());
            case "MISSING_READ_RULE" -> new RuleRequirement("READ", "RECEIVER", representationType());
            case "MISSING_ENCODE_RULE", "MISSING_BOUNDARY_CONVERSION" -> new RuleRequirement("ENCODE", "RECEIVER", representationType());
            case "MISSING_CONTAINER_SUMMARY", "UNRESOLVED_CONTAINER" -> new RuleRequirement("CONTAINER_SUMMARY", "RECEIVER", "container");
            default -> null;
        };
    }

    private String representationType() {
        String name = representation.getClassName();
        int separator = name.lastIndexOf('.');
        name = separator < 0 ? name : name.substring(separator + 1);
        int nested = name.lastIndexOf('$');
        name = nested < 0 ? name : name.substring(nested + 1);
        return "(" + name + ")" + name;
    }

    private List<DependencyStep> dependencyPath(ProgramPoint start, List<PropagationEdge> edges) {
        List<DependencyStep> path = new ArrayList<>();
        Set<ProgramPoint> visited = new LinkedHashSet<>();
        ArrayDeque<ProgramPoint> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            ProgramPoint point = pending.removeFirst();
            if (!visited.add(point)) {
                continue;
            }
            path.add(step(point));
            edges.stream().filter(edge -> edge.toPoint().equals(point))
                    .sorted(java.util.Comparator.comparing(edge -> edge.fromPoint().key()))
                    .forEach(edge -> pending.addLast(edge.fromPoint()));
        }
        return List.copyOf(path);
    }

    private static DependencyStep step(ProgramPoint point) {
        String symbol = point.symbol();
        String descriptor = symbol == null ? "" : symbol.substring(Math.max(0, symbol.indexOf('(')));
        return new DependencyStep(symbol, point.instruction(), descriptor);
    }

    private static List<PropagationEdge> orderedEdges(java.util.Collection<PropagationEdge> edges) {
        return edges.stream().sorted(java.util.Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList();
    }
}
