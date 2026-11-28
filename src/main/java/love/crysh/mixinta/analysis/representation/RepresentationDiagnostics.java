package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerEffects;
import love.crysh.mixinta.analysis.container.ContainerRules;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.plan.PlanConflict;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.diagnostic.ContainerContext;
import love.crysh.mixinta.diagnostic.DependencyStep;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.MigrationRequirement;
import love.crysh.mixinta.diagnostic.RuleRequirement;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static love.crysh.mixinta.analysis.representation.RepresentationQueries.*;

/** 把阶段问题与语义证据转换为公开诊断。 */
final class RepresentationDiagnostics {
    private final RepresentationMigration migration;
    private final RepresentationWork work;
    private final Type representation;
    private final ContainerRules containers;
    private final List<ContainerUse> containerUses;
    private final ContainerEffects containerEffects;
    private final ConstraintGraph graph;
    private final Set<StorageSlot> storages;
    private final List<PropagationEdge> dependencies;
    private final List<RepresentationIssue> issues;
    private final ElementFacts batch;

    RepresentationDiagnostics(RepresentationMigration migration, RepresentationInput input, ValidationResult result, ElementFacts facts) {
        work = input.work;
        var analysis = result.facts();
        this.migration = migration;
        representation = migration.representation();
        containers = new ContainerRules(migration.containers());
        containerUses = input.containerUses();
        containerEffects = input.containers().effects();
        graph = analysis.graph();
        storages = analysis.storages();
        dependencies = analysis.dependencies();
        issues = result.issues();
        batch = facts;
    }

    private Set<ConstraintGraph.Subject> subjects(RepresentationIssue issue) {
        return issue.anchors();
    }

    private Set<StorageSlot> relatedStorages(RepresentationIssue issue) {
        Set<StorageSlot> related = new LinkedHashSet<>(issue.storages());
        related.addAll(graph.storages(subjects(issue)));
        related.retainAll(storages);
        return related;
    }

    private List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (RepresentationIssue issue : issues) {
            Set<ConstraintGraph.Subject> anchors = subjects(issue);
            List<PropagationEdge> relevant = graph.evidence(anchors);
            ProgramPoint failurePoint = anchors.stream().map(ConstraintGraph.Subject::point)
                    .filter(point -> point.method().equals(issue.site().method()) && point.instruction() == issue.site().index())
                    .filter(point -> !point.detail().isEmpty()).findFirst().orElse(issue.site().point());
            String failureDetail = switch (failurePoint.detail()) {
                case "receiver" -> "receiver";
                default -> failurePoint.detail().startsWith("operand=") || failurePoint.detail().startsWith("capture=")
                        ? failurePoint.detail() : "";
            };
            Type originalType = Type.getArgumentTypes(migration.target().descriptor())[migration.parameter()];
            var requirement = new MigrationEvidence(migration.id(), migration.target(), migration.parameter(),
                    representation.getDescriptor(), relevant, anchors.stream().map(ConstraintGraph.Subject::point).distinct().toList());
            int instruction = issue.site().index();
            Diagnostic diagnostic = new Diagnostic(issue.code(), issue.site().method(), instruction, issue.parameter(),
                    issue.message(), migration.id(), work.orderedEdges(java.util.stream.Stream.concat(relevant.stream(), referenceEvidence(relatedStorages(issue)).stream()).toList()), issue.candidates(),
                    migration.target(), migration.parameter(), originalType.getDescriptor(), representation.getDescriptor(), requirement(issue.code()),
                    dependencyPath(failurePoint, relevant), issue.site().symbol()).withFailureDetail(failureDetail);
            result.add(new Entry(diagnostic.withContainers(observationsAt( issue.site().point(), issue.parameter(),
                    relatedStorages(issue), issue.code()).stream().map(this::context).toList()), List.of(requirement), issue.identity()));
        }
        return List.copyOf(result);
    }

    private record Entry(Diagnostic diagnostic, List<MigrationEvidence> contributors, String identity) { }

    private record IssueKey(String code, MethodKey method, int instruction, int parameter,
            String source, String target, String symbol, String detail, String identity) { }

    static List<Diagnostic> collect(List<RepresentationDiagnostics> reports, List<BatchConflict> conflicts, ElementFacts facts) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        conflicts.forEach(conflict -> diagnostics.add(conflict(conflict, facts, reports)));
        Map<IssueKey, Entry> merged = new LinkedHashMap<>();
        for (RepresentationDiagnostics report : reports) {
            for (Entry entry : report.entries()) {
                Diagnostic diagnostic = entry.diagnostic();
                var key = new IssueKey(diagnostic.code(), diagnostic.method(), diagnostic.instruction(), diagnostic.parameter(),
                        diagnostic.sourceType(), diagnostic.targetType(), diagnostic.failureSymbol(), diagnostic.failureDetail(), entry.identity());
                merged.merge(key, entry, RepresentationDiagnostics::merge);
            }
        }
        merged.values().forEach(entry -> diagnostics.add(entry.diagnostic().withMigrations(
                MigrationRequirement.fromEvidence(MigrationEvidence.merge(List.of(entry.contributors()))))));
        return List.copyOf(diagnostics);
    }

    private static Entry merge(Entry left, Entry right) {
        Diagnostic first = left.diagnostic();
        Diagnostic second = right.diagnostic();
        Diagnostic merged = new Diagnostic(first.code(), first.method(), first.instruction(), first.parameter(),
                first.message(), first.migration(), java.util.stream.Stream.concat(first.propagation().stream(), second.propagation().stream()).distinct().toList(),
                java.util.stream.Stream.concat(first.candidates().stream(), second.candidates().stream()).distinct().toList(),
                first.target(), first.targetParameter(), first.sourceType(), first.targetType(), first.requirement(), first.dependencyPath(), first.failureSymbol(),
                java.util.stream.Stream.concat(first.containers().stream(), second.containers().stream()).distinct().toList(), List.of(), first.failureDetail());
        return new Entry(merged, MigrationEvidence.merge(List.of(left.contributors(), right.contributors())), left.identity());
    }

    static List<Diagnostic> planConflicts(List<PlanConflict> conflicts) {
        return conflicts.stream().map(conflict -> new Diagnostic("REPRESENTATION_PLAN_CONFLICT", conflict.location().method(),
                conflict.location().instruction(), -1, conflict.message())
                .withMigrations(MigrationRequirement.fromEvidence(conflict.contributors()))).toList();
    }

    private static Diagnostic conflict(BatchConflict conflict, ElementFacts facts, List<RepresentationDiagnostics> reports) {
        List<ContainerObservation> observations = new ArrayList<>();
        conflict.storages().stream().sorted(java.util.Comparator
                .comparing((StorageSlot slot) -> ConstraintGraph.storage(slot).point().key()))
                .forEach(slot -> observations.add(reports.stream().flatMap(report -> report.observations(slot).stream())
                        .min(java.util.Comparator.comparing((ContainerObservation value) -> !value.operation().startsWith("READ"))
                                .thenComparing(value -> value.access().key()))
                        .orElseGet(() -> new ContainerObservation(slot, storagePoint(slot.storage()), "ALLOCATE"))));
        if (conflict.ruleUse() != null) {
            reports.stream().filter(report -> conflict.contributors().stream().anyMatch(report::matches))
                    .flatMap(report -> report.observationsAt(conflict.point(), conflict.ruleUse()).stream()).forEach(observations::add);
        }
        var first = conflict.contributors().getFirst();
        var point = conflict.storages().isEmpty() ? conflict.point() : observations.getFirst().access();
        String original = Type.getArgumentTypes(first.target().descriptor())[first.parameter()].getDescriptor();
        return new Diagnostic(conflict.code(), point.method(), point.instruction(), conflict.parameter(), conflict.message())
                .withContext(first.target(), first.parameter(), original, first.representation(), null, List.of(), point.symbol())
                .withMigrations(MigrationRequirement.fromEvidence(conflict.contributors()))
                .withContainers(observations.stream().distinct().map(observation -> context(observation, facts)).toList());
    }

    private List<ContainerObservation> observations(StorageSlot slot) {
        return containerUses.stream().filter(use -> use.summary() != null)
                .filter(use -> use.receivers().slots(use.summary().channel()).contains(slot))
                .map(use -> new ContainerObservation(slot, use.site().point(), use.summary().operation().name())).toList();
    }

    private boolean matches(MigrationEvidence evidence) {
        return evidence.id().equals(migration.id()) && evidence.target().equals(migration.target())
                && evidence.parameter() == migration.parameter() && evidence.representation().equals(representation.getDescriptor());
    }

    private ContainerContext context(ContainerObservation observation) {
        return context(observation, batch);
    }

    private static ContainerContext context(ContainerObservation observation, ElementFacts batch) {
        var slot = observation.storage();
        ElementFacts.Fact fact = batch == null ? null : batch.storages.get(slot);
        ElementState state = fact == null ? ElementState.ORIGINAL : fact.state();
        return new ContainerContext(storagePoint(slot.storage()), slot.storage().descriptor(), slot.channel(),
                observation.access(), observation.operation(), state);
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

    private List<ContainerObservation> observationsAt(ProgramPoint point, RuleUse use) {
        var subject = use.kind() == TypeRule.Kind.SOURCE ? ConstraintGraph.value(point) : ConstraintGraph.use(point, use.parameter());
        return observationsAt(point, use.parameter(), graph.storages(List.of(subject)), use.kind() == TypeRule.Kind.SOURCE ? "PRODUCER" : "");
    }

    private List<ContainerObservation> observationsAt(ProgramPoint point, int parameter, Set<StorageSlot> slots, String code) {
        List<ContainerObservation> result = new ArrayList<>();
        Set<ProgramPoint> path = graph.evidence(graph.at(point, parameter)).stream()
                .flatMap(edge -> java.util.stream.Stream.of(edge.fromPoint(), edge.toPoint()))
                .collect(java.util.stream.Collectors.toSet());
        for (StorageSlot slot : slots.stream().sorted(java.util.Comparator.comparing(value -> ConstraintGraph.storage(value).point().key())).toList()) {
            if (code.equals("ESCAPED_CONTAINER") || code.equals("UNKNOWN_CONTAINER_USE")) {
                String operation = containerEffects.accesses().stream().filter(access -> access.point().equals(point)
                        && access.parameter() == parameter).map(access -> access.kind().name()).findFirst().orElse("UNKNOWN_OPERATION");
                result.add(new ContainerObservation(slot, point, operation));
                continue;
            }
            var matching = containerUses.stream().filter(use -> {
                ContainerSummary summary = use.summary() == null ? containers.resolve(use.call()) : use.summary();
                return summary != null && use.receivers().slots(summary.channel()).contains(slot);
            })
                    .sorted(java.util.Comparator.comparing((ContainerUse use) -> !use.site().point().equals(point))
                            .thenComparing(use -> {
                                ContainerSummary summary = use.summary() == null ? containers.resolve(use.call()) : use.summary();
                                return code.contains("PRODUCER") ? !summary.writesElement() : !summary.readsElement();
                            })
                            .thenComparing(use -> {
                                ContainerSummary summary = use.summary() == null ? containers.resolve(use.call()) : use.summary();
                                return !path.contains(ConstraintGraph.value(use.site().point()).point())
                                        && !path.contains(ConstraintGraph.use(use.site().point(), summary.elementParameter()).point());
                            })
                            .thenComparing(use -> use.site().point().key())).findFirst();
            matching.ifPresent(use -> {
                ContainerSummary summary = use.summary() == null ? containers.resolve(use.call()) : use.summary();
                result.add(new ContainerObservation(slot, use.site().point(), summary.operation().name()));
            });
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
                .filter(point -> java.util.Objects.equals(point.method(), start.method()) && point.instruction() == start.instruction()
                        && (start.detail().isEmpty() || start.detail().equals(point.detail())))
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
        return new DependencyStep(symbol, point.instruction(), descriptor, point.detail());
    }

}
