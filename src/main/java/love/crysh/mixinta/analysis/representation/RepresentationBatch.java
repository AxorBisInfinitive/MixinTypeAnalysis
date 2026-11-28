package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.AnalysisMetrics;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.plan.RepresentationPlanMerger;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 所有声明先汇总事实与动作，再在固定快照上形成唯一可执行计划。 */
public final class RepresentationBatch {
    private static final class Session {
        final List<RepresentationMigration> declarations;
        PropagationResult analysis;
        ValidationResult validation;
        RepresentationInput input;

        RepresentationFacts facts() { return validation == null ? analysis.facts() : validation.facts(); }
        Map<RuleUse, RepresentationRule> rules() { return validation == null ? analysis.rules() : validation.rules(); }

        Session(List<RepresentationMigration> declarations) {
            this.declarations = List.copyOf(declarations);
        }

        List<MigrationEvidence> requirements() {
            return declarations.stream().map(value -> new MigrationEvidence(value.id(), value.target(), value.parameter(),
                    value.representation().getDescriptor(), facts().dependencies())).toList();
        }

        MigrationEvidence requirement(ProgramPoint point, RuleUse use) {
            return ElementFacts.requirement(declarations.getFirst(), facts(), List.of(subject(point, use)));
        }

        ConstraintGraph.Subject subject(ProgramPoint point, RuleUse use) {
            return use.kind() == TypeRule.Kind.SOURCE ? ConstraintGraph.value(point) : ConstraintGraph.use(point, use.parameter());
        }
    }

    public static RepresentationBatchResult analyze(List<RepresentationMigration> declarations, Map<String, ClassNode> classes,
            Map<MethodKey, Migration> targets, ClassHierarchy hierarchy, AnalysisMetrics metrics) {
        List<BatchConflict> conflicts = new ArrayList<>();
        List<Session> sessions = declarations.stream().sorted(Comparator.comparing((RepresentationMigration value) -> value.target().toString())
                .thenComparingInt(RepresentationMigration::parameter).thenComparing(RepresentationMigration::id))
                .map(value -> new Session(List.of(value))).toList();
        var containers = ContainerAnalysis.analyzeAll(classes, targets.keySet(), hierarchy,
                sessions.stream().map(session -> session.declarations.getFirst().containers()).toList(), metrics);
        ClassHierarchy queries = hierarchy.readOnlyQueries();
        long representationStarted = System.nanoTime();
        Map<java.util.Set<ContainerContract>, RepresentationInput> inputs = new LinkedHashMap<>();
        Map<love.crysh.mixinta.analysis.flow.MethodFlow, Long> pointCounts = new java.util.IdentityHashMap<>();
        containers.values().forEach(analysis -> analysis.flows().forEach(flow -> pointCounts.put(flow.source(), flow.source().pointCreations())));
        ElementFacts facts;
        List<Diagnostic> diagnostics;
        try {
            try {
                timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_INPUT, () -> {
                    containers.forEach((contracts, analysis) -> inputs.put(contracts, new RepresentationInput(analysis, targets.keySet())));
                    return null;
                });
                timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_PROPAGATION, () -> {
                    for (Session session : sessions) {
                        var contracts = session.declarations.getFirst().containers();
                        session.input = inputs.get(java.util.Set.copyOf(contracts));
                        session.analysis = new RepresentationSolver(session.declarations, targets, queries).solve(session.input);
                    }
                    return null;
                });
                ElementFacts initial = timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_INITIAL_FACTS, () -> facts(sessions));
                timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_VALIDATION, () -> {
                    for (Session session : sessions) {
                        session.validation = new RepresentationValidator(session.declarations.getFirst(), session.analysis,
                                session.input, targets, classes, queries, initial).validate();
                    }
                    return null;
                });
                facts = timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_FINAL_FACTS, () -> facts(sessions));
                timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_CONFLICTS, () -> {
                    validateConflicts(sessions, facts, conflicts);
                    return null;
                });
                diagnostics = timed(metrics, AnalysisMetrics.Stage.REPRESENTATION_DIAGNOSTICS, () ->
                        RepresentationDiagnostics.collect(sessions.stream().map(session -> new RepresentationDiagnostics(
                                session.declarations.getFirst(), session.input, session.validation, facts)).toList(), conflicts, facts));
            } finally {
                metrics.add(AnalysisMetrics.Stage.REPRESENTATION, System.nanoTime() - representationStarted);
            }
            if (!diagnostics.isEmpty()) return new RepresentationBatchResult(java.util.Optional.empty(), diagnostics);
            return timed(metrics, AnalysisMetrics.Stage.PLAN, () -> {
                var plans = sessions.stream().map(session -> new RepresentationPlanner(session.declarations, session.input,
                        session.validation, targets, classes, queries, facts).plan()).toList();
                var merged = RepresentationPlanMerger.merge(plans, classes);
                return new RepresentationBatchResult(merged.plan(), RepresentationDiagnostics.planConflicts(merged.conflicts()));
            });
        } finally {
            recordWork(inputs, sessions, metrics);
            pointCounts.forEach((flow, before) -> metrics.count(AnalysisMetrics.Count.POINT_CREATIONS, flow.pointCreations() - before));
            var work = queries.queryStats();
            metrics.count(AnalysisMetrics.Count.HIERARCHY_QUERIES, work.calls());
            metrics.count(AnalysisMetrics.Count.HIERARCHY_HITS, work.hits());
            metrics.count(AnalysisMetrics.Count.HIERARCHY_FAILURES, work.failures());
            metrics.count(AnalysisMetrics.Count.HIERARCHY_NODES, work.nodes());
        }
    }

    private static <T> T timed(AnalysisMetrics metrics, AnalysisMetrics.Stage stage, java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            metrics.add(stage, System.nanoTime() - started);
        }
    }

    private static void recordWork(Map<java.util.Set<ContainerContract>, RepresentationInput> inputs,
            List<Session> sessions, AnalysisMetrics metrics) {
        inputs.values().forEach(input -> {
            input.work.recordMetrics(metrics);
            if (input.containers().callGraph() != null) input.containers().callGraph().recordMetrics();
        });
        for (Session session : sessions) {
            if (session.analysis != null) session.facts().graph().recordMetrics(metrics);
        }
    }

    private static void validateConflicts(List<Session> sessions, ElementFacts facts, List<BatchConflict> conflicts) {
        validateIdentities(sessions, conflicts);
        for (var entry : facts.storages.entrySet()) {
            if (entry.getValue().state() == ElementState.CONFLICT) {
                StorageSlot slot = entry.getKey();
                conflicts.add(conflict("CONTAINER_FACT_CONFLICT", ConstraintGraph.storage(slot).point(), -1, entry.getValue().requirements(),
                        "Incompatible representations for one storage channel").withStorages(java.util.Set.of(slot)));
            }
        }
        for (var entry : facts.values.entrySet()) {
            if (entry.getValue().state() != ElementState.CONFLICT) continue;
            List<MigrationEvidence> requirements = entry.getValue().requirements();
            var point = sessions.stream().map(session -> session.facts().points().get(entry.getKey())).filter(Objects::nonNull).findFirst().orElseThrow();
            boolean covered = facts.storages.entrySet().stream().filter(storage -> storage.getValue().state() == ElementState.CONFLICT)
                    .anyMatch(storage -> requirements.stream().allMatch(requirement -> storage.getValue().requirements().stream()
                            .anyMatch(owner -> sameRequirement(owner, requirement))) && sessions.stream()
                            .filter(session -> session.facts().requested().contains(entry.getKey()))
                            .allMatch(session -> session.facts().graph().storages(List.of(ConstraintGraph.value(point))).contains(storage.getKey())));
            if (!covered) {
                conflicts.add(conflict("VALUE_FACT_CONFLICT", point, -1, requirements, "Incompatible representations for one value"));
            }
        }
        validateRules(sessions, facts, conflicts);
    }

    private static ElementFacts facts(List<Session> sessions) {
        ElementFacts facts = new ElementFacts();
        sessions.forEach(session -> facts.add(session.declarations, session.facts()));
        return facts;
    }

    private static boolean sameRequirement(MigrationEvidence first, MigrationEvidence second) {
        return first.target().equals(second.target()) && first.parameter() == second.parameter()
                && first.id().equals(second.id()) && first.representation().equals(second.representation());
    }

    private static void validateIdentities(List<Session> sessions, List<BatchConflict> conflicts) {
        Map<String, List<MigrationEvidence>> identities = new LinkedHashMap<>();
        sessions.forEach(session -> session.requirements().forEach(value ->
                identities.computeIfAbsent(value.id(), ignored -> new ArrayList<>()).add(value)));
        for (var entry : identities.entrySet()) {
            if (entry.getValue().stream().map(MigrationEvidence::representation).distinct().count() > 1) {
                var target = entry.getValue().getFirst().target();
                conflicts.add(conflict("MIGRATION_ID_CONFLICT", new ProgramPoint(target, -1, target.toString()), -1, entry.getValue(),
                        "Migration identity requires incompatible representations: " + entry.getKey()));
            }
        }
    }

    private static void validateRules(List<Session> sessions, ElementFacts facts, List<BatchConflict> conflicts) {
        Map<RuleUse, List<Session>> uses = new LinkedHashMap<>();
        sessions.forEach(session -> session.rules().keySet().forEach(use ->
                uses.computeIfAbsent(use, ignored -> new ArrayList<>()).add(session)));
        var orderedUses = uses.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<RuleUse, List<Session>> entry) -> entry.getKey().location().method().toString())
                .thenComparingInt(entry -> entry.getKey().location().instruction())
                .thenComparing(entry -> entry.getKey().kind().name()).thenComparingInt(entry -> entry.getKey().parameter())).toList();
        for (var entry : orderedUses) {
            List<Session> owners = entry.getValue();
            if (owners.size() < 2 || owners.stream().map(session -> session.declarations.getFirst().representation()).distinct().count() > 1) continue;
            var first = owners.getFirst().rules().get(entry.getKey());
            if (owners.stream().allMatch(session -> compatible(first, session.rules().get(entry.getKey())))) continue;
            var location = entry.getKey().location();
            ProgramPoint point = owners.getFirst().facts().points().get(location);
            if (point == null) point = new ProgramPoint(location.method(), location.instruction(), first.helper().toString());
            ProgramPoint usePoint = point;
            List<MigrationEvidence> requirements = owners.stream().map(session -> session.requirement(usePoint, entry.getKey())).toList();
            for (var storage : facts.storages.entrySet()) {
                if (owners.stream().anyMatch(session -> session.facts().graph().storages(List.of(session.subject(usePoint, entry.getKey())))
                        .contains(storage.getKey()))) {
                    storage.getValue().conflict();
                }
            }
            for (Session owner : owners) {
                for (var subject : owner.facts().graph().ancestors(List.of(owner.subject(usePoint, entry.getKey())))) {
                    if (subject.kind() != ConstraintGraph.Kind.VALUE) continue;
                    var value = facts.values.get(new InstructionLocation(subject.location().method(), subject.location().instruction()));
                    if (value != null) value.conflict();
                }
            }
            conflicts.add(conflict("REPRESENTATION_RULE_CONFLICT", new ProgramPoint(location.method(), location.instruction(),
                    usePoint.symbol()), entry.getKey().parameter(), requirements,
                    "Incompatible " + entry.getKey().kind() + " rules at one use parameter=" + entry.getKey().parameter())
                    .withRuleUse(entry.getKey()));
        }
    }

    private static boolean compatible(RepresentationRule first, RepresentationRule second) {
        return first.kind() == second.kind() && first.version() == second.version()
                && Objects.equals(first.source(), second.source()) && first.helper().equals(second.helper())
                && first.binding() == second.binding() && first.parameter() == second.parameter();
    }

    private static BatchConflict conflict(String code, ProgramPoint point, int parameter, List<MigrationEvidence> requirements, String message) {
        return new BatchConflict(code, point, parameter, message, requirements);
    }
}
