package love.crysh.mixinta.analysis;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.diagnostic.DependencyStep;
import love.crysh.mixinta.diagnostic.ContainerContext;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.RuleRequirement;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.PlanChange;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
            Set<StorageSlot> storages, Map<RuleUse, RepresentationMigration.Rule> rules,
            List<PropagationEdge> dependencies, List<ContainerContext> containers, ConstraintGraph graph,
            Map<RepresentationPlan.Location, ProgramPoint> points, Set<RepresentationPlan.Location> nulls,
            Set<RepresentationPlan.Location> unresolvedValues, Set<StorageSlot> unresolvedStorages) {
    }

    record RuleUse(RepresentationPlan.Location location, TypeRule.Kind kind, int parameter) {
    }

    record Input(List<MethodFlow> flows, Map<MethodKey, MethodContainerSummary> summaries, ContainerCallGraph callGraph) {
        Input {
            flows = List.copyOf(flows);
            summaries = Map.copyOf(summaries);
        }
    }

    private record Site(MethodFlow flow, int index) {
        MethodKey method() {
            return flow.key;
        }

        String describe() {
            return method() + " instruction=" + index;
        }

        String symbol() {
            return flow.point(index).symbol();
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

    private record ContainerUse(Site site, MethodInsnNode call, ContainerSummary summary,
            StorageReferences receivers) {
    }

    private final List<RepresentationMigration> migrations;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final ContainerRules containers;
    private final List<MethodFlow> flows = new ArrayList<>();
    private final Map<AbstractInsnNode, Site> sites = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> migrated = new LinkedHashSet<>();
    private final Set<StorageSlot> storages = new LinkedHashSet<>();
    private final List<ContainerUse> containerUses = new ArrayList<>();
    private final ContainerEffects containerEffects = new ContainerEffects();
    private final Map<MethodKey, MethodContainerSummary> methodSummaries = new LinkedHashMap<>();
    private ContainerCallGraph callGraph;
    private final ArrayDeque<Demand> pending = new ArrayDeque<>();
    private final Set<AbstractInsnNode> requested = new HashSet<>();
    private final List<Diagnostic> diagnostics;
    private final List<Issue> issues = new ArrayList<>();
    private final ConstraintGraph graph = new ConstraintGraph();
    private ElementFacts batch;
    private final Set<String> diagnosticKeys = new HashSet<>();
    private final Set<PropagationEdge> dependencies = new LinkedHashSet<>();
    private final Map<AbstractInsnNode, RepresentationMigration.Rule> selected = new IdentityHashMap<>();
    private final Map<RuleUse, RepresentationMigration.Rule> rules = new LinkedHashMap<>();
    private final List<RepresentationPlan.Edit> edits = new ArrayList<>();
    private final List<RepresentationPlan.Argument> arguments = new ArrayList<>();
    private final List<RepresentationPlan.Local> locals = new ArrayList<>();
    private final List<PlanChange> changes = new ArrayList<>();
    private final Map<MethodKey, RepresentationPlan.Bridge> bridges = new LinkedHashMap<>();
    private final Type representation;

    private record Issue(String code, Site site, int parameter, String message, List<String> candidates, Set<StorageSlot> storages) {
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
        this.containers = new ContainerRules(migration.containers());
    }

    Analysis solve(Input input) {
        if (input == null) {
            analyzeMethods();
        } else {
            flows.addAll(input.flows());
            methodSummaries.putAll(input.summaries());
            callGraph = input.callGraph();
            flows.forEach(flow -> flow.indices.forEach((instruction, index) -> sites.put(instruction, new Site(flow, index))));
        }
        if (flows.stream().anyMatch(flow -> flow.frames == null)) {
            return result();
        }
        if (callGraph == null) {
            callGraph = new ContainerCallGraph(classes, flows, hierarchy, containers);
            callGraph.solve();
            methodSummaries.putAll(callGraph.summaries());
        }
        dependencies.addAll(callGraph.dependencies());
        indexContainerUses();
        indexContainerEffects();
        seedTargets();
        int previous;
        do {
            previous = requested.size() + storages.size();
            solveDemands();
            propagateStorage();
            unifyLocalMerges();
        } while (!pending.isEmpty() || previous != requested.size() + storages.size());
        return result();
    }

    Input input() {
        return callGraph == null ? null : new Input(flows, methodSummaries, callGraph);
    }

    void validate(ElementFacts facts) {
        batch = facts;
        if (callGraph != null) {
            validateUses();
            validateContainerEffects();
        }
    }

    Analysis plan() {
        if (issues.isEmpty()) flows.forEach(this::plan);
        return result();
    }

    Analysis result() {
        Set<StorageSlot> unresolvedStorages = new LinkedHashSet<>();
        Set<RepresentationPlan.Location> unresolvedValues = new LinkedHashSet<>();
        for (Issue issue : issues) {
            unresolvedStorages.addAll(relatedStorages(issue));
            graph.ancestors(subjects(issue)).stream().filter(subject -> subject.kind() == ConstraintGraph.Kind.VALUE)
                    .forEach(subject -> unresolvedValues.add(new RepresentationPlan.Location(subject.location().method(), subject.location().instruction())));
        }
        Map<RepresentationPlan.Location, ProgramPoint> points = new LinkedHashMap<>();
        sites.values().forEach(site -> points.put(location(site), site.point()));
        Set<RepresentationPlan.Location> values = requested.stream().map(origin -> location(sites.get(origin)))
                .collect(java.util.stream.Collectors.toSet());
        Set<RepresentationPlan.Location> nulls = requested.stream().filter(origin -> origin.getOpcode() == Opcodes.ACONST_NULL)
                .map(origin -> location(sites.get(origin))).collect(java.util.stream.Collectors.toSet());
        return new Analysis(new RepresentationPlan(edits, arguments, locals, List.copyOf(bridges.values()), changes, orderedEdges(dependencies)),
                Set.copyOf(values), Set.copyOf(storages), Map.copyOf(rules), orderedEdges(dependencies), containerContexts(),
                graph, Map.copyOf(points), nulls, Set.copyOf(unresolvedValues), Set.copyOf(unresolvedStorages));
    }

    private List<ContainerContext> containerContexts() {
        List<ContainerContext> contexts = new ArrayList<>();
        for (ContainerUse use : containerUses) {
            if (use.summary() == null) continue;
            for (StorageSlot slot : use.receivers().slots(use.summary().channel())) {
                contexts.add(context(slot, use.site(), use.summary().operation().name()));
            }
        }
        return contexts.stream().distinct().toList();
    }

    private ContainerContext context(StorageSlot slot, Site access, String operation) {
        ElementFacts.Fact fact = batch == null ? null : batch.storages.get(slot);
        ElementState state = fact == null ? ElementState.ORIGINAL : fact.state();
        return new ContainerContext(storagePoint(slot.storage()), slot.storage().descriptor(), slot.channel(), access.point(), operation, state);
    }

    private Set<ConstraintGraph.Subject> subjects(Issue issue) {
        if (issue.code().contains("PRODUCER") || issue.code().contains("SOURCE")
                || issue.parameter() < 0 && issue.site().index >= 0 && requested.contains(issue.site().flow.instructions[issue.site().index])) {
            return Set.of(ConstraintGraph.value(issue.site().point()));
        }
        return Set.of(ConstraintGraph.use(issue.site().point(), issue.parameter()));
    }

    private Set<StorageSlot> relatedStorages(Issue issue) {
        Set<StorageSlot> related = new LinkedHashSet<>(issue.storages());
        related.addAll(graph.storages(subjects(issue)));
        related.retainAll(storages);
        return related;
    }

    private void analyzeMethods() {
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                if (targets.containsKey(key) || containers.models(key)) {
                    continue;
                }
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    methodSummaries.put(key, ContainerMethodAnalyzer.unavailable(key, method.access));
                    continue;
                }
                MethodFlow flow = new MethodFlow(owner.name, method, containers);
                flows.add(flow);
                try {
                    flow.analyze();
                } catch (AnalyzerException failure) {
                    problem("INVALID_PROPAGATION_INPUT", new Site(flow, -1), failure.getMessage());
                }
                flow.indices.forEach((instruction, index) -> sites.put(instruction, new Site(flow, index)));
            }
        }
    }

    private void indexContainerUses() {
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && call.getOpcode() != Opcodes.INVOKESTATIC) {
                    StorageReferences receiver = flow.receiverReferences(index, call);
                    containerUses.add(new ContainerUse(new Site(flow, index), call, containers.resolve(call, receiver), receiver));
                }
            }
        }
    }

    private void indexContainerEffects() {
        for (MethodContainerSummary summary : methodSummaries.values()) {
            for (MethodContainerSummary.Effect effect : summary.effects()) {
                boolean ordinaryCall = effect.kind() == ContainerEffects.Kind.CALL_ARGUMENT
                        || effect.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION;
                if (ordinaryCall && callGraph.covered(effect.point())
                        || effect.kind() == ContainerEffects.Kind.RETURN && callGraph.internalReturn(summary.method())) {
                    continue;
                }
                if (effect.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION) {
                    Site site = site(effect.point());
                    if (site.flow.instructions[site.index] instanceof MethodInsnNode call && summary(site, call) != null) continue;
                }
                containerEffects.record(callGraph.resolve(summary.method(), effect.subject()).storages(),
                        effect.point(), effect.kind(), effect.parameter());
            }
        }
        for (ContainerUse use : containerUses) {
            if (use.summary() == null && containers.resolve(use.call()) != null) {
                containerEffects.record(use.receivers().storages(), use.site().point(), ContainerEffects.Kind.UNKNOWN_OPERATION, -1);
            }
        }
    }

    private void validateContainerEffects() {
        for (ContainerEffects.Access access : containerEffects.accesses()) {
            List<StorageId> affected = access.storages().stream().filter(this::isSelectedStorage)
                    .sorted(java.util.Comparator.comparing(storage -> storagePoint(storage).key())).toList();
            if (affected.isEmpty()) {
                continue;
            }
            DependencyKind kind = access.kind().escape == ContainerEffects.EscapeState.ESCAPED
                    ? DependencyKind.CONTAINER_ESCAPE : DependencyKind.CONTAINER_EXTERNAL_ACCESS;
            List<String> details = new ArrayList<>();
            for (StorageSlot slot : storages) {
                if (affected.contains(slot.storage())) graph.link(ConstraintGraph.storage(slot),
                        ConstraintGraph.use(access.point(), access.parameter()), kind);
            }
            for (StorageId storage : affected) {
                dependencies.add(new PropagationEdge(storagePoint(storage), access.point(), kind));
                List<Integer> channels = storages.stream().filter(slot -> slot.storage().equals(storage))
                        .map(StorageSlot::channel).sorted().toList();
                details.add(site(storagePoint(storage)).describe() + " allocation=" + storagePoint(storage).symbol()
                        + " channels=" + channels + " escape=" + containerEffects.state(storage));
            }
            String binding = access.parameter() >= 0 ? "argument=" + access.parameter()
                    : access.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION ? "receiver" : "value";
            candidateProblem(access.kind().code(), site(access.point()), access.parameter(),
                    access.kind() + " " + binding + " requires modeled container effects, storage=" + String.join(", ", details),
                    summaryCandidates(site(access.point())), storages.stream().filter(slot -> affected.contains(slot.storage()))
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private List<String> summaryCandidates(Site site) {
        if (site.index < 0 || !(site.flow.instructions[site.index] instanceof MethodInsnNode call)) {
            return List.of();
        }
        MethodContainerSummary summary = methodSummaries.get(symbol(call));
        if (summary == null) {
            return List.of();
        }
        long operations = summary.calls().stream().filter(value -> value.intrinsic() != null).count();
        return List.of("Callee summary " + summary.method() + " body=" + summary.hasBody()
                + " allocations=" + summary.allocations().size() + " returns=" + summary.returns().size()
                + " operations=" + operations + " calls=" + summary.calls().size() + " effects=" + summary.effects().size()
                + " composition=" + (callGraph.covered(site.point()) ? "resolved" : "unresolved")
                + " coverage=" + callGraph.coverage(site.point()));
    }

    private void seedTargets() {
        for (MethodFlow flow : flows) {
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
        ConstraintGraph.Subject subject = kind == DependencyKind.TARGET_ARGUMENT
                ? ConstraintGraph.use(parent, Integer.parseInt(parent.detail().substring("parameter=".length()))) : ConstraintGraph.value(parent);
        request(value, subject, kind);
    }

    private void request(SourceValue value, ConstraintGraph.Subject parent, DependencyKind kind) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            ProgramPoint point = sites.get(origin).point();
            dependencies.add(new PropagationEdge(parent.point(), point, kind));
            graph.link(parent, ConstraintGraph.value(point), kind);
            if (requested.add(origin)) pending.add(new Demand(origin, parent.point()));
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
                        rules.put(new RuleUse(location(site), TypeRule.Kind.SOURCE, -1), candidates.getFirst());
                        checkHelperAccess(candidates.getFirst().helper(), site);
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
                } else if (summary(site, call) != null && summary(site, call).readsElement()) {
                    ContainerSummary summary = summary(site, call);
                    migrated.add(origin);
                    requireStorage(site.flow.receiverReferences(site.index, call), site.point(), summary.channel());
                } else {
                    candidateProblem("MISSING_PRODUCER_RULE", site, "Missing source relation " + symbol(call),
                            migration.rules().stream().filter(rule -> rule.kind() == TypeRule.Kind.SOURCE)
                                    .map(rule -> rule.id() + " symbol does not match " + symbol(call)).toList());
                }
            } else if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                migrated.add(origin);
            } else {
                problem("MISSING_PRODUCER_RULE", site, "Missing source relation at " + site.describe());
            }
        }
    }

    private void requireStorage(StorageReferences references, ProgramPoint path, int channel) {
        for (StorageSlot slot : references.slots(channel)) {
            storages.add(slot);
            graph.link(ConstraintGraph.value(path), ConstraintGraph.storage(slot), DependencyKind.CONTAINER_ALLOCATION);
            dependencies.add(new PropagationEdge(path, storagePoint(slot.storage()), DependencyKind.CONTAINER_ALLOCATION));
            if (slot.channel() == 0 && ContainerSummary.mapOwner(Type.getType(slot.storage().descriptor()).getInternalName())) {
                problem("MAP_KEY_MIGRATION_NOT_IMPLEMENTED", site(path), "Map key representation requires equality and hash rules");
            }
        }
        for (ProgramPoint origin : references.unknownOrigins()) {
            Site site = site(origin);
            dependencies.add(new PropagationEdge(path, origin, DependencyKind.CONTAINER_ALLOCATION));
            candidateProblem("UNRESOLVED_CONTAINER", site,
                    path.key() + " <- unknown storage at " + origin.symbol(), summaryCandidates(site));
        }
    }

    private void propagateStorage() {
        for (ContainerUse use : containerUses) {
            ContainerSummary summary = use.summary();
            if (summary == null || !(summary.view() != null ? touchesStorage(use.receivers())
                    : touchesChannel(use.receivers(), summary.channel()))) {
                continue;
            }
            Site site = use.site();
            StorageReferences receiver = use.receivers();
            if (summary.readsElement() || summary.writesElement() || summary.observedParameter() >= 0) {
                requireStorage(receiver, site.point(), summary.channel());
            }
            for (StorageId storage : receiver.storages()) {
                if (isSelectedStorage(storage)) {
                    dependencies.add(new PropagationEdge(storagePoint(storage), site.point(),
                            dependencyKind(summary.operation())));
                }
            }
            if (summary.writesElement()) {
                var write = ConstraintGraph.use(site.point(), summary.elementParameter());
                for (StorageSlot slot : receiver.slots(summary.channel())) {
                    if (storages.contains(slot)) graph.link(ConstraintGraph.storage(slot), write, DependencyKind.CONTAINER_WRITE);
                }
                request(site.flow.argument(site.index, use.call(), summary.elementParameter()), write, DependencyKind.CONTAINER_WRITE);
            }
            if (summary.readsElement()) {
                for (StorageSlot slot : receiver.slots(summary.channel())) {
                    if (storages.contains(slot)) graph.link(ConstraintGraph.storage(slot), ConstraintGraph.value(site.point()), DependencyKind.CONTAINER_READ);
                }
                request(new SourceValue(1, use.call()), site.point(), DependencyKind.CONTAINER_READ);
            }
            if (summary.observedParameter() >= 0) {
                for (StorageSlot slot : receiver.slots(summary.channel())) {
                    if (storages.contains(slot)) {
                        var observation = ConstraintGraph.use(site.point(), -1);
                        graph.link(ConstraintGraph.storage(slot), observation, DependencyKind.CONTAINER_OBSERVE);
                        graph.link(observation, ConstraintGraph.storage(slot), DependencyKind.CONTAINER_OBSERVE);
                    }
                }
                helper(TypeRule.Kind.ENCODE, site);
            }
        }
        // 所有读取方都要跟随共享元素迁移，包含最终传入旧消费者的读取。
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && isUnbox(call) && touches(flow.receiver(index, call), migrated)) {
                    request(new SourceValue(2, call), firstMigratedPoint(flow.receiver(index, call)), DependencyKind.UNBOXING);
                }
            }
        }
    }

    private void unifyLocalMerges() {
        for (MethodFlow flow : flows) {
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
        for (MethodFlow flow : flows) {
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
                        if (touches(value, migrated) && !nullOnly(value)) {
                            problem("UNSUPPORTED_VALUE_USE", site, pathFor(value) + " <- invokedynamic argument at " + site.describe());
                        }
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    checkReference(constant.cst, site);
                } else if (instruction.getOpcode() == Opcodes.ARETURN || instruction.getOpcode() == Opcodes.PUTSTATIC
                        || instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.AASTORE) {
                    if (touches(flow.top(index), migrated) && !nullOnly(flow.top(index))) {
                        problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.top(index)) + " <- reference sink at " + site.describe());
                    }
                } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                    requireEncoder(site);
                } else if (!(instruction instanceof VarInsnNode)
                        && instruction.getOpcode() != Opcodes.POP
                        && !(instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals("java/lang/Long"))
                        && flow.consumed.getOrDefault(instruction, Set.of()).stream()
                                .anyMatch(origin -> origin.getOpcode() != Opcodes.ACONST_NULL && migrated.contains(origin))) {
                    problem("UNSUPPORTED_VALUE_USE", site, "Migrated value reaches opcode=" + instruction.getOpcode());
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
        MethodFlow flow = site.flow;
        int index = site.index;
        boolean receiverPresent = call.getOpcode() != Opcodes.INVOKESTATIC;
        if (receiverPresent) linkUse(site, -1, flow.receiver(index, call));
        if (isUnbox(call) && migrated.contains(call)) {
            helper(TypeRule.Kind.READ, site);
        }
        if (receiverPresent && touches(flow.receiver(index, call), migrated)
                && !nullOnly(flow.receiver(index, call))
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
            linkUse(site, parameter, value);
            if (!touches(value, migrated)) {
                continue;
            }
            if (nullOnly(value) && (arguments[parameter].getSort() == Type.OBJECT || arguments[parameter].getSort() == Type.ARRAY)) continue;
            Migration target = target(call);
            boolean matchingTarget = target != null && target.representations().stream().anyMatch(declaration ->
                    declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
            if (matchingTarget || isBox(call) && effectiveValue(call)
                    || compatibleWrite(site, call, parameter)) {
                continue;
            }
            if (arguments[parameter].equals(Type.LONG_TYPE) || isMapComparison(site, call, parameter)) {
                helper(TypeRule.Kind.ENCODE, site, parameter);
            } else {
                candidateProblem("UNSUPPORTED_REFERENCE_USE", site, parameter, pathFor(value) + " <- argument of " + symbol(call), List.of());
            }
        }
    }

    private boolean effectiveValue(AbstractInsnNode instruction) {
        return migrated.contains(instruction) || batch != null && batch.value(location(sites.get(instruction)), representation);
    }

    private boolean compatibleWrite(Site site, MethodInsnNode call, int parameter) {
        if (!isContainerWrite(site, call, parameter)) return false;
        SourceValue value = site.flow.argument(site.index, call, parameter);
        if (nullOnly(value)) return true;
        var summary = summary(site, call);
        var references = site.flow.receiverReferences(site.index, call);
        var slots = references.slots(summary.channel());
        return !slots.isEmpty() && references.unknownOrigins().isEmpty() && slots.stream()
                .allMatch(slot -> storages.contains(slot) || batch != null && batch.storage(slot, representation));
    }

    private static boolean nullOnly(SourceValue value) {
        return value != null && !value.insns.isEmpty() && value.insns.stream().allMatch(origin -> origin.getOpcode() == Opcodes.ACONST_NULL);
    }

    private void linkUse(Site site, int parameter, SourceValue value) {
        if (value == null) return;
        for (AbstractInsnNode origin : ordered(value.insns)) {
            var use = ConstraintGraph.use(site.point(), parameter);
            var producer = ConstraintGraph.value(sites.get(origin).point());
            graph.link(use, producer, DependencyKind.SOURCE_RESULT);
            if (migrated.contains(origin)) graph.link(producer, use, DependencyKind.SOURCE_RESULT);
        }
    }

    private RepresentationMigration.Rule helper(TypeRule.Kind kind, Site site) {
        return helper(kind, site, -1);
    }

    private RepresentationMigration.Rule helper(TypeRule.Kind kind, Site site, int parameter) {
        if (kind == TypeRule.Kind.READ) graph.link(ConstraintGraph.use(site.point(), parameter), ConstraintGraph.value(site.point()), DependencyKind.UNBOXING);
        else if (site.index >= 0 && site.flow.instructions[site.index] instanceof MethodInsnNode call && parameter >= 0) {
            linkUse(site, parameter, site.flow.argument(site.index, call, parameter));
        } else if (site.index >= 0) linkUse(site, parameter, site.flow.top(site.index));
        List<RepresentationMigration.Rule> candidates = migration.rules().stream().filter(rule -> rule.kind() == kind).toList();
        if (candidates.size() != 1) {
            candidateProblem(candidates.isEmpty() ? "MISSING_" + kind + "_RULE" : "AMBIGUOUS_" + kind + "_RULE", site, parameter,
                    "Expected one " + kind + " helper", candidates.stream().map(rule -> rule.id() + " applicable").toList());
            return null;
        }
        checkHelperAccess(candidates.getFirst().helper(), site, parameter);
        rules.put(new RuleUse(location(site), kind, parameter), candidates.getFirst());
        return candidates.getFirst();
    }

    private void checkHelperAccess(MethodKey helper, Site site) {
        checkHelperAccess(helper, site, -1);
    }

    private void checkHelperAccess(MethodKey helper, Site site, int parameter) {
        ClassNode owner = classes.get(helper.owner());
        if ((owner.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(owner.name).equals(packageName(site.method().owner()))) {
            candidateProblem("RULE_HELPER_NOT_ACCESSIBLE", site, parameter, "Rule helper owner is inaccessible from " + site.method() + ": " + helper, List.of());
        }
        ClassNode type = hierarchy.get(representation.getInternalName());
        if ((type.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(type.name).equals(packageName(site.method().owner()))) {
            candidateProblem("REPRESENTATION_TYPE_NOT_ACCESSIBLE", site, parameter, "Representation type is inaccessible from " + site.method() + ": " + type.name, List.of());
        }
    }

    private static String packageName(String owner) {
        return owner.substring(0, Math.max(0, owner.lastIndexOf('/')));
    }

    private void requireEncoder(Site site) {
        helper(TypeRule.Kind.ENCODE, site);
    }

    private void plan(MethodFlow flow) {
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
                if (!nullOnly(flow.top(index))) edit(site, "cast-reference", null);
            } else if (instruction instanceof MethodInsnNode call) {
                if (selected.containsKey(call)) {
                    edit(site, "replace-producer", selected.get(call));
                } else if (isBox(call) && effectiveValue(call)) {
                    if (migrated.contains(call)) edit(site, "remove-boxing", null);
                } else if (isUnbox(call) && migrated.contains(call)) {
                    edit(site, "replace-unboxing", helper(TypeRule.Kind.READ, site));
                } else if (isMigratedObservation(flow, index, call)) {
                    var encoder = helper(TypeRule.Kind.ENCODE, site);
                    String action = ContainerSummary.mapOwner(call.owner) ? "observe-map-values" : "observe-view-elements";
                    edits.add(new RepresentationPlan.Edit(location(site), action, accessibleHelper(encoder.helper(), site),
                            representation, TypeRule.Binding.RECEIVER, 0));
                    changes.add(new PlanChange(flow.key, index, action, migration.id(), encoder.id()));
                    int parameter = summary(site, call).observedParameter();
                    var value = flow.argument(index, call, parameter);
                    if (touches(value, migrated) && !nullOnly(value)) {
                        var queryEncoder = helper(TypeRule.Kind.ENCODE, site, parameter);
                        arguments.add(new RepresentationPlan.Argument(location(site), symbol(call), symbol(call), parameter,
                                representation, accessibleHelper(queryEncoder.helper(), site), migration.id()));
                        changes.add(new PlanChange(flow.key, index, "encode-argument", migration.id(), queryEncoder.id()));
                    }
                } else {
                    Type[] parameters = Type.getArgumentTypes(call.desc);
                    Migration target = target(call);
                    for (int parameter = 0; parameter < parameters.length; parameter++) {
                        int parameterIndex = parameter;
                        boolean ownTarget = target != null && migrations.stream().anyMatch(declaration ->
                                declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
                        boolean matchingTarget = target != null && target.representations().stream().anyMatch(declaration ->
                                declaration.parameter() == parameterIndex);
                        if (!touches(flow.argument(index, call, parameter), migrated) && !ownTarget) {
                            continue;
                        }
                        if (nullOnly(flow.argument(index, call, parameter))
                                && (parameters[parameter].getSort() == Type.OBJECT || parameters[parameter].getSort() == Type.ARRAY)) continue;
                        if (compatibleWrite(site, call, parameter)) {
                            continue;
                        }
                        var encoder = matchingTarget ? null : helper(TypeRule.Kind.ENCODE, site, parameter);
                        MethodKey original = symbol(call);
                        MethodKey replacement = target != null
                                ? new MethodKey(call.owner, call.name, target.implementation().descriptor()) : original;
                        arguments.add(new RepresentationPlan.Argument(location(site), original, replacement, parameter,
                                representation, encoder == null ? null : accessibleHelper(encoder.helper(), site), migration.id()));
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
        edits.add(new RepresentationPlan.Edit(location(site), action, rule == null ? null : accessibleHelper(rule.helper(), site), representation,
                rule == null ? TypeRule.Binding.RECEIVER : rule.binding(), rule == null ? 0 : rule.parameter()));
        changes.add(new PlanChange(site.method(), site.index, action, migration.id(), rule == null ? "" : rule.id()));
    }

    private MethodKey accessibleHelper(MethodKey helper, Site site) {
        ClassNode owner = classes.get(helper.owner());
        MethodNode method = ClassFiles.findMethod(owner, helper.name(), helper.descriptor());
        if (helper.owner().equals(site.method().owner()) || (method.access & Opcodes.ACC_PUBLIC) != 0) {
            return helper;
        }
        return bridges.computeIfAbsent(helper, key -> {
            String name = "mixinta$rule$" + key.name() + "$" + owner.methods.indexOf(method);
            Set<String> names = new HashSet<>();
            owner.methods.forEach(existing -> names.add(existing.name));
            bridges.values().stream().filter(bridge -> bridge.method().owner().equals(owner.name))
                    .forEach(bridge -> names.add(bridge.method().name()));
            while (names.contains(name)) name += "$";
            MethodKey bridge = new MethodKey(owner.name, name, key.descriptor());
            changes.add(new PlanChange(bridge, -1, "generate-rule-bridge", migration.id(), key.toString()));
            return new RepresentationPlan.Bridge(bridge, key);
        }).method();
    }

    private void planLocalMetadata(MethodFlow flow) {
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
                    if (nullOnly(value)) continue;
                    locals.add(new RepresentationPlan.Local(flow.key, local, representation.getDescriptor(), null));
                    break;
                }
                if (variable.signature != null && touchesStorage(flow.aliases.local(index, variable.index))) {
                    StorageReferences references = flow.aliases.local(index, variable.index);
                    Set<Integer> channels = references.references().stream()
                            .filter(reference -> reference.kind() == ContainerReference.Kind.ROOT
                                    && !ContainerSummary.supportsAllocation(Type.getType(reference.storage().descriptor()).getInternalName()))
                            .flatMap(reference -> storages.stream().filter(slot -> slot.storage().equals(reference.storage())))
                            .map(StorageSlot::channel).collect(java.util.stream.Collectors.toSet());
                    locals.add(new RepresentationPlan.Local(flow.key, local, variable.desc,
                            storageSignature(variable.signature, channels.isEmpty() ? null : channels)));
                    break;
                }
            }
        }
    }

    private String storageSignature(String signature, Set<Integer> channels) {
        int open = signature.indexOf('<');
        if (open < 0) return signature;
        String owner = signature.substring(1, open);
        if (channels == null && !ContainerSummary.mapOwner(owner) && !owner.equals("java/util/Map$Entry")) {
            int entry = signature.indexOf("Ljava/util/Map$Entry<");
            if (entry >= 0) {
                int depth = 0;
                int end = entry;
                for (; end < signature.length(); end++) {
                    if (signature.charAt(end) == '<') depth++;
                    if (signature.charAt(end) == '>') depth--;
                    if (signature.charAt(end) == ';' && depth == 0) break;
                }
                return signature.substring(0, entry) + storageSignature(signature.substring(entry, end + 1), null)
                        + signature.substring(end + 1);
            }
            return signature.replace("Ljava/lang/Long;", representation.getDescriptor());
        }
        int start = open + 1;
        int argument = 0;
        int depth = 0;
        StringBuilder result = new StringBuilder(signature.substring(0, start));
        for (int index = start; index < signature.length(); index++) {
            char symbol = signature.charAt(index);
            if (symbol == '<') depth++;
            if (symbol == '>') depth--;
            if (depth == 0 && (symbol == ';' || symbol == '*')) {
                String value = signature.substring(start, index + 1);
                if (channels == null ? argument == 1 : channels.contains(argument)) {
                    if (value.equals("Ljava/lang/Long;") || value.equals("+Ljava/lang/Long;")
                            || value.equals("-Ljava/lang/Long;")) {
                        value = value.replace("Ljava/lang/Long;", representation.getDescriptor());
                    }
                }
                result.append(value);
                argument++;
                start = index + 1;
            }
        }
        return result.append(signature.substring(start)).toString();
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

    private ContainerSummary summary(Site site, MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        return containers.resolve(call, site.flow.receiverReferences(site.index, call));
    }

    private boolean isContainerWrite(Site site, MethodInsnNode call, int parameter) {
        ContainerSummary summary = summary(site, call);
        return summary != null && summary.writesElement() && summary.elementParameter() == parameter;
    }

    private boolean isMapComparison(Site site, MethodInsnNode call, int parameter) {
        ContainerSummary summary = summary(site, call);
        return summary != null && (summary.keyParameter() == parameter || summary.observedParameter() == parameter);
    }

    private boolean isMigratedObservation(MethodFlow flow, int index, MethodInsnNode call) {
        ContainerSummary summary = summary(new Site(flow, index), call);
        return summary != null && summary.observedParameter() >= 0 && touchesChannel(flow.receiverReferences(index, call), summary.channel());
    }

    private static DependencyKind dependencyKind(ContainerOperation operation) {
        return switch (operation) {
            case ALLOCATE -> DependencyKind.CONTAINER_ALLOCATION;
            case VIEW -> DependencyKind.CONTAINER_VIEW;
            case WRITE, READ_WRITE -> DependencyKind.CONTAINER_WRITE;
            case READ, READ_REMOVE -> DependencyKind.CONTAINER_READ;
            case OBSERVE -> DependencyKind.CONTAINER_OBSERVE;
            case REMOVE_MATCH -> DependencyKind.CONTAINER_WRITE;
            case CLEAR -> DependencyKind.CONTAINER_CLEAR;
        };
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

    private boolean touchesStorage(StorageReferences references) {
        return references != null && references.references().stream().anyMatch(reference -> {
            if (reference.kind() == ContainerReference.Kind.ROOT) return isSelectedStorage(reference.storage());
            int channel = ContainerSummary.mapOwner(Type.getType(reference.storage().descriptor()).getInternalName()) ? 1 : 0;
            return storages.contains(reference.slot(channel));
        });
    }

    private boolean touchesChannel(StorageReferences references, int channel) {
        return references.slots(channel).stream().anyMatch(storages::contains);
    }

    private boolean isSelectedStorage(StorageId storage) {
        return storages.stream().anyMatch(slot -> slot.storage().equals(storage));
    }

    private Site site(ProgramPoint point) {
        MethodFlow flow = flows.stream().filter(candidate -> candidate.key.equals(point.method())).findFirst().orElseThrow();
        return new Site(flow, point.instruction());
    }

    private ProgramPoint storagePoint(StorageId storage) {
        return new ProgramPoint(storage.method(), storage.instruction(), Type.getType(storage.descriptor()).getInternalName());
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

    private ProgramPoint firstMigratedPoint(SourceValue value) {
        return ordered(value.insns).stream().filter(migrated::contains).map(origin -> sites.get(origin).point())
                .findFirst().orElse(new ProgramPoint(null, -1, "value migration " + migration.id()));
    }

    private void problem(String code, Site site, String message) {
        candidateProblem(code, site, message, List.of());
    }

    private void candidateProblem(String code, Site site, String message, List<String> candidates) {
        candidateProblem(code, site, -1, message, candidates);
    }

    private void candidateProblem(String code, Site site, int parameter, String message, List<String> candidates) {
        candidateProblem(code, site, parameter, message, candidates, Set.of());
    }

    private void candidateProblem(String code, Site site, int parameter, String message, List<String> candidates, Set<StorageSlot> slots) {
        var use = ConstraintGraph.use(site.point(), parameter);
        if (site.index >= 0 && !graph.contains(use)) {
            for (AbstractInsnNode origin : site.flow.consumed.getOrDefault(site.flow.instructions[site.index], Set.of())) {
                if (migrated.contains(origin)) graph.link(use, ConstraintGraph.value(sites.get(origin).point()), DependencyKind.SOURCE_RESULT);
            }
        }
        String key = code + " " + site.describe() + " parameter=" + parameter;
        if (diagnosticKeys.add(key)) {
            issues.add(new Issue(code, site, parameter, message, candidates, Set.copyOf(slots)));
        }
    }

    List<Diagnostic> diagnostics(ElementFacts facts) {
        batch = facts;
        List<Diagnostic> result = new ArrayList<>(diagnostics);
        for (Issue issue : issues) {
            Set<ConstraintGraph.Subject> anchors = subjects(issue);
            List<PropagationEdge> relevant = graph.evidence(anchors);
            Type originalType = Type.getArgumentTypes(migration.target().descriptor())[migration.parameter()];
            var requirement = new love.crysh.mixinta.diagnostic.MigrationRequirement(migration.id(), migration.target(), migration.parameter(),
                    representation.getDescriptor(), relevant);
            Diagnostic diagnostic = new Diagnostic(issue.code, issue.site.method(), Math.max(-1, issue.site.index), issue.parameter,
                    issue.message, migration.id(), orderedEdges(java.util.stream.Stream.concat(relevant.stream(), referenceEvidence(relatedStorages(issue)).stream()).toList()), issue.candidates,
                    migration.target(), migration.parameter(), originalType.getDescriptor(), representation.getDescriptor(), requirement(issue.code),
                    dependencyPath(issue.site.point(), relevant), issue.site.symbol());
            result.add(diagnostic.withContainers(contextsAt(issue.site.point(), issue.parameter(), relatedStorages(issue), issue.code()))
                    .withMigrations(List.of(requirement)));
        }
        return List.copyOf(result);
    }

    private List<PropagationEdge> referenceEvidence(Set<StorageSlot> slots) {
        Set<ProgramPoint> affected = slots.stream().map(slot -> storagePoint(slot.storage()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PropagationEdge> references = dependencies.stream().filter(edge -> edge.kind() == DependencyKind.CONTAINER_CALL_ARGUMENT
                || edge.kind() == DependencyKind.CONTAINER_RETURN || edge.kind() == DependencyKind.CONTAINER_VIEW).toList();
        boolean changed;
        do {
            changed = false;
            for (PropagationEdge edge : references) if (affected.contains(edge.toPoint())) changed |= affected.add(edge.fromPoint());
        } while (changed);
        return references.stream().filter(edge -> affected.contains(edge.fromPoint()) && affected.contains(edge.toPoint())).toList();
    }

    List<ContainerContext> contextsAt(ProgramPoint point, RuleUse use, ElementFacts facts) {
        batch = facts;
        var subject = use.kind() == TypeRule.Kind.SOURCE ? ConstraintGraph.value(point) : ConstraintGraph.use(point, use.parameter());
        return contextsAt(point, use.parameter(), graph.storages(List.of(subject)), use.kind() == TypeRule.Kind.SOURCE ? "PRODUCER" : "");
    }

    private List<ContainerContext> contextsAt(ProgramPoint point, int parameter, Set<StorageSlot> slots, String code) {
        List<ContainerContext> result = new ArrayList<>();
        Set<ProgramPoint> path = graph.evidence(graph.at(point, parameter)).stream()
                .flatMap(edge -> java.util.stream.Stream.of(edge.fromPoint(), edge.toPoint()))
                .collect(java.util.stream.Collectors.toSet());
        for (StorageSlot slot : slots.stream().sorted(java.util.Comparator.comparing(value -> ConstraintGraph.storage(value).point().key())).toList()) {
            if (code.equals("ESCAPED_CONTAINER") || code.equals("UNKNOWN_CONTAINER_USE")) {
                String operation = containerEffects.accesses().stream().filter(access -> access.point().equals(point)
                        && access.parameter() == parameter).map(access -> access.kind().name()).findFirst().orElse("UNKNOWN_OPERATION");
                result.add(context(slot, site(point), operation));
                continue;
            }
            var matching = containerUses.stream().filter(use -> use.summary() != null && use.receivers().slots(use.summary().channel()).contains(slot))
                    .sorted(java.util.Comparator.comparing((ContainerUse use) -> !use.site().point().equals(point))
                            .thenComparing(use -> code.contains("PRODUCER") ? !use.summary().writesElement() : !use.summary().readsElement())
                            .thenComparing(use -> !path.contains(ConstraintGraph.value(use.site().point()).point())
                                    && !path.contains(ConstraintGraph.use(use.site().point(), use.summary().elementParameter()).point()))
                            .thenComparing(use -> use.site().point().key())).findFirst();
            matching.ifPresent(use -> result.add(context(slot, use.site(), use.summary().operation().name())));
        }
        return List.copyOf(result);
    }

    private RuleRequirement requirement(String code) {
        return switch (code) {
            case "MISSING_PRODUCER_RULE" -> new RuleRequirement("SOURCE", "RECEIVER", representationType());
            case "MISSING_READ_RULE" -> new RuleRequirement("READ", "RECEIVER", representationType());
            case "MISSING_ENCODE_RULE", "MISSING_BOUNDARY_CONVERSION" -> new RuleRequirement("ENCODE", "RECEIVER", representationType());
            case "UNRESOLVED_CONTAINER" -> new RuleRequirement("CONTAINER_SUMMARY", "RECEIVER", "container");
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
        var goals = edges.stream().flatMap(edge -> java.util.stream.Stream.of(edge.fromPoint(), edge.toPoint()))
                .filter(point -> java.util.Objects.equals(point.method(), start.method()) && point.instruction() == start.instruction())
                .sorted(java.util.Comparator.comparing(ProgramPoint::key)).toList();
        var roots = edges.stream().filter(edge -> edge.kind() == DependencyKind.TARGET_ARGUMENT)
                .map(PropagationEdge::fromPoint).distinct().sorted(java.util.Comparator.comparing(ProgramPoint::key)).toList();
        ArrayDeque<ProgramPoint> pending = new ArrayDeque<>(roots);
        Set<ProgramPoint> visited = new HashSet<>(roots);
        Map<ProgramPoint, ProgramPoint> parents = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            ProgramPoint point = pending.removeFirst();
            if (goals.contains(point)) {
                List<DependencyStep> path = new ArrayList<>();
                for (ProgramPoint current = point; current != null; current = parents.get(current)) path.add(step(current));
                return List.copyOf(path);
            }
            edges.stream().filter(edge -> edge.fromPoint().equals(point)).map(PropagationEdge::toPoint)
                    .sorted(java.util.Comparator.comparing(ProgramPoint::key)).forEach(next -> {
                        if (visited.add(next)) {
                            parents.put(next, point);
                            pending.addLast(next);
                        }
                    });
        }
        return List.of();
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
