package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerCallGraph;
import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.analysis.container.ContainerReference;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.container.JdkContainerSummaries;
import love.crysh.mixinta.analysis.container.MethodContainerSummary;
import love.crysh.mixinta.analysis.container.StorageId;
import love.crysh.mixinta.analysis.container.StorageReferences;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static love.crysh.mixinta.analysis.representation.RepresentationProblems.formalParameter;
import static love.crysh.mixinta.analysis.representation.RepresentationQueries.*;

/** 从固定 Mixin 后快照求解值与存储传播，SOURCE 规则参与来源选择。 */
final class RepresentationSolver {
    private final List<RepresentationMigration> migrations;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final ClassHierarchy hierarchy;
    private final List<ContainerFlow> flows = new ArrayList<>();
    private final Map<AbstractInsnNode, AnalysisSite> sites = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> migrated = new LinkedHashSet<>();
    private final Set<StorageSlot> storages = new LinkedHashSet<>();
    private final List<ContainerUse> containerUses = new ArrayList<>();
    private final Map<MethodKey, MethodContainerSummary> methodSummaries = new LinkedHashMap<>();
    private ContainerCallGraph callGraph;
    private RepresentationInput input;
    private final ArrayDeque<AbstractInsnNode> pending = new ArrayDeque<>();
    private final Set<AbstractInsnNode> requested = new HashSet<>();
    private final ConstraintGraph graph = new ConstraintGraph();
    private final Set<PropagationEdge> dependencies = new LinkedHashSet<>();
    private final Map<RuleUse, RepresentationRule> rules = new LinkedHashMap<>();
    private final RepresentationProblems problems = new RepresentationProblems(sites, migrated, graph, List.of());

    RepresentationSolver(List<RepresentationMigration> migrations, Map<MethodKey, Migration> targets, ClassHierarchy hierarchy) {
        this.migrations = List.copyOf(migrations);
        migration = migrations.getFirst();
        this.targets = targets;
        this.hierarchy = hierarchy;
    }

    PropagationResult solve(RepresentationInput input) {
        this.input = input;
        flows.addAll(input.flows());
        methodSummaries.putAll(input.containers().summaries());
        callGraph = input.containers().callGraph();
        sites.putAll(input.sites());
        containerUses.addAll(input.containerUses());
        input.containers().failures().forEach(failure -> problems.problem("INVALID_PROPAGATION_INPUT", new AnalysisSite(failure.flow(), -1), failure.message()));
        if (callGraph == null) return result();
        dependencies.addAll(callGraph.dependencies());
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

    private PropagationResult result() {
        return new PropagationResult(RepresentationFacts.capture(input, requested, migrated, storages,
                dependencies, graph, problems.issues()), rules, problems.issues());
    }

    private List<String> summaryCandidates(AnalysisSite site) {
        if (site.index() < 0 || !(site.flow().source().instruction(site.index()) instanceof MethodInsnNode call)) {
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
        for (var candidate : input.targetCalls()) {
            AnalysisSite site = candidate.site();
            MethodInsnNode call = candidate.call();
            Migration target = target(call, targets, hierarchy);
            if (target == null) continue;
            for (RepresentationMigration declaration : migrations) {
                if (target.original().equals(declaration.target())) {
                    request(site.flow().source().argument(site.index(), call, declaration.parameter()),
                            site.point("parameter=" + declaration.parameter()), DependencyKind.TARGET_ARGUMENT);
                }
            }
        }
    }

    private void request(OriginValue value, ProgramPoint parent, DependencyKind kind) {
        ConstraintGraph.Subject subject = kind == DependencyKind.TARGET_ARGUMENT
                ? ConstraintGraph.use(parent, Integer.parseInt(parent.detail().substring("parameter=".length()))) : ConstraintGraph.value(parent);
        request(value, subject, kind);
    }

    private void request(OriginValue value, ConstraintGraph.Subject parent, DependencyKind kind) {
        for (AbstractInsnNode origin : ordered(value.origins())) {
            ProgramPoint point = sites.get(origin).point();
            dependencies.add(new PropagationEdge(parent.point(), point, kind));
            graph.link(parent, ConstraintGraph.value(point), kind);
            if (requested.add(origin)) pending.add(origin);
        }
    }

    private void solveDemands() {
        while (!pending.isEmpty()) {
            AbstractInsnNode origin = pending.removeFirst();
            AnalysisSite site = sites.get(origin);
            if (origin instanceof MethodInsnNode call) {
                List<RepresentationRule> candidates = migration.rules().stream()
                        .filter(rule -> rule.kind() == TypeRule.Kind.SOURCE && is(call, rule.source())).toList();
                if (!candidates.isEmpty()) {
                    migrated.add(origin);
                    if (candidates.size() == 1) {
                        rules.put(new RuleUse(location(site), TypeRule.Kind.SOURCE, -1), candidates.getFirst());
                    } else {
                        problems.candidateProblem("AMBIGUOUS_SOURCE_RULE", site, "Multiple applicable source rules " + symbol(call),
                                candidates.stream().map(rule -> rule.id() + " applicable").toList());
                    }
                } else if (isBox(call)) {
                    migrated.add(origin);
                    request(site.flow().source().argument(site.index(), call, 0), site.point(), DependencyKind.BOXING);
                } else if (isUnbox(call)) {
                    migrated.add(origin);
                    request(site.flow().source().receiver(site.index(), call), site.point(), DependencyKind.UNBOXING);
                } else if (input.summary(site, call) != null && input.summary(site, call).readsElement()) {
                    ContainerSummary summary = input.summary(site, call);
                    migrated.add(origin);
                    requireStorage(site.flow().receiverReferences(site.index(), call), site.point(), summary.channel());
                } else {
                    problems.candidateProblem("MISSING_PRODUCER_RULE", site, "Missing source relation " + symbol(call),
                            migration.rules().stream().filter(rule -> rule.kind() == TypeRule.Kind.SOURCE)
                                    .map(rule -> rule.id() + " symbol does not match " + symbol(call)).toList());
                }
            } else if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                migrated.add(origin);
            } else {
                problems.problem("MISSING_PRODUCER_RULE", site, "Missing source relation at " + site.describe());
            }
        }
    }

    private void requireStorage(StorageReferences references, ProgramPoint path, int channel) {
        for (StorageSlot slot : references.slots(channel)) {
            storages.add(slot);
            graph.link(ConstraintGraph.value(path), ConstraintGraph.storage(slot), DependencyKind.CONTAINER_ALLOCATION);
            dependencies.add(new PropagationEdge(path, storagePoint(slot.storage()), DependencyKind.CONTAINER_ALLOCATION));
            if (slot.channel() == 0 && JdkContainerSummaries.mapOwner(Type.getType(slot.storage().descriptor()).getInternalName())) {
                problems.candidateProblem("MAP_KEY_MIGRATION_NOT_IMPLEMENTED", site(path), -1,
                        "Map key representation requires equality and hash rules", List.of(), Set.of(slot),
                        Set.of(ConstraintGraph.value(path)), "channel=0");
            }
        }
        for (ProgramPoint origin : references.unknownOrigins()) {
            AnalysisSite site = site(origin);
            dependencies.add(new PropagationEdge(path, origin, DependencyKind.CONTAINER_ALLOCATION));
            graph.link(ConstraintGraph.value(path), ConstraintGraph.unknown(origin), DependencyKind.CONTAINER_ALLOCATION);
            problems.candidateProblem("UNRESOLVED_CONTAINER", site, formalParameter(site),
                    path.key() + " <- unknown storage at " + origin.symbol(), summaryCandidates(site),
                    references.slots(channel), Set.of(ConstraintGraph.unknown(origin)),
                    origin.method() + "#" + origin.instruction() + "#" + origin.symbol());
        }
    }

    private void propagateStorage() {
        for (ContainerUse use : containerUses) {
            ContainerSummary summary = use.summary();
            if (summary == null || !(summary.view() != null ? touchesStorage(use.receivers())
                    : touchesChannel(use.receivers(), summary.channel()))) {
                continue;
            }
            AnalysisSite site = use.site();
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
                request(site.flow().source().argument(site.index(), use.call(), summary.elementParameter()), write, DependencyKind.CONTAINER_WRITE);
            }
            if (summary.readsElement()) {
                for (StorageSlot slot : receiver.slots(summary.channel())) {
                    if (storages.contains(slot)) graph.link(ConstraintGraph.storage(slot), ConstraintGraph.value(site.point()), DependencyKind.CONTAINER_READ);
                }
                request(OriginValue.of(1, use.call()), site.point(), DependencyKind.CONTAINER_READ);
            }
            if (summary.observedParameter() >= 0) {
                for (StorageSlot slot : receiver.slots(summary.channel())) {
                    if (storages.contains(slot)) {
                        var observation = ConstraintGraph.use(site.point(), -1);
                        graph.link(ConstraintGraph.storage(slot), observation, DependencyKind.CONTAINER_OBSERVE);
                        graph.link(observation, ConstraintGraph.storage(slot), DependencyKind.CONTAINER_OBSERVE);
                    }
                }
            }
        }
        // 所有读取方都要跟随共享元素迁移，包含最终传入旧消费者的读取。
        for (var candidate : input.unboxes()) {
            AnalysisSite site = candidate.site();
            OriginValue receiver = site.flow().source().receiver(site.index(), candidate.call());
            if (input.work.touches(receiver, migrated)) {
                request(OriginValue.of(2, candidate.call()), firstMigratedPoint(receiver), DependencyKind.UNBOXING);
            }
        }
    }

    private void unifyLocalMerges() {
        for (var candidate : input.locals()) {
            input.work.localCandidates++;
            if (input.work.touches(candidate.value(), migrated)) {
                input.work.localRequests++;
                request(candidate.value(), candidate.site().point(), DependencyKind.LOCAL_MERGE);
            }
        }
    }

    private boolean touchesStorage(StorageReferences references) {
        return references != null && references.references().stream().anyMatch(reference -> {
            if (reference.kind() == ContainerReference.Kind.ROOT) return isSelectedStorage(reference.storage());
            int channel = JdkContainerSummaries.mapOwner(Type.getType(reference.storage().descriptor()).getInternalName()) ? 1 : 0;
            return storages.contains(reference.slot(channel));
        });
    }

    private boolean touchesChannel(StorageReferences references, int channel) {
        return references.slots(channel).stream().anyMatch(storages::contains);
    }

    private boolean isSelectedStorage(StorageId storage) {
        return storages.stream().anyMatch(slot -> slot.storage().equals(storage));
    }

    private List<AbstractInsnNode> ordered(Set<AbstractInsnNode> origins) {
        return origins.stream().sorted((left, right) -> {
            AnalysisSite first = sites.get(left);
            AnalysisSite second = sites.get(right);
            int method = first.flow().source().defaultSymbol().compareTo(second.flow().source().defaultSymbol());
            return method == 0 ? Integer.compare(first.index(), second.index()) : method;
        }).toList();
    }

    private ProgramPoint firstMigratedPoint(OriginValue value) {
        return ordered(value.origins()).stream().filter(migrated::contains).map(origin -> sites.get(origin).point())
                .findFirst().orElse(new ProgramPoint(null, -1, "value migration " + migration.id()));
    }
    private AnalysisSite site(ProgramPoint point) {
        ContainerFlow flow = flows.stream().filter(candidate -> candidate.source().key().equals(point.method())).findFirst().orElseThrow();
        return new AnalysisSite(flow, point.instruction());
    }
}
