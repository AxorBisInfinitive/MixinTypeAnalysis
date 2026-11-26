package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.diagnostic.ContainerContext;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.MigrationRequirement;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 所有声明先汇总事实与动作，再在固定快照上形成唯一可执行计划。 */
final class RepresentationBatch {
    private static final class Session {
        final List<RepresentationMigration> declarations;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final RepresentationSolver solver;
        RepresentationSolver.Analysis analysis;

        Session(List<RepresentationMigration> declarations, Map<String, ClassNode> classes,
                Map<MethodKey, Migration> targets, ClassHierarchy hierarchy) {
            this.declarations = List.copyOf(declarations);
            solver = new RepresentationSolver(classes, declarations, targets, hierarchy, diagnostics);
        }

        List<MigrationRequirement> requirements() {
            return declarations.stream().map(value -> new MigrationRequirement(value.id(), value.target(), value.parameter(),
                    value.representation().getDescriptor(), analysis.dependencies())).toList();
        }

        MigrationRequirement requirement(ProgramPoint point, RepresentationSolver.RuleUse use) {
            return ElementFacts.requirement(declarations.getFirst(), analysis, List.of(subject(point, use)));
        }

        ConstraintGraph.Subject subject(ProgramPoint point, RepresentationSolver.RuleUse use) {
            return use.kind() == TypeRule.Kind.SOURCE ? ConstraintGraph.value(point) : ConstraintGraph.use(point, use.parameter());
        }
    }

    static RepresentationPlan analyze(List<RepresentationMigration> declarations, Map<String, ClassNode> classes,
            Map<MethodKey, Migration> targets, ClassHierarchy hierarchy, List<Diagnostic> diagnostics) {
        List<Session> sessions = declarations.stream().sorted(Comparator.comparing((RepresentationMigration value) -> value.target().toString())
                .thenComparingInt(RepresentationMigration::parameter).thenComparing(RepresentationMigration::id))
                .map(value -> new Session(List.of(value), classes, targets, hierarchy)).toList();
        Map<List<ContainerRules.Definition>, RepresentationSolver.Input> inputs = new LinkedHashMap<>();
        for (Session session : sessions) {
            var contracts = session.declarations.getFirst().containers();
            session.analysis = session.solver.solve(inputs.get(contracts));
            if (session.solver.input() != null) inputs.putIfAbsent(contracts, session.solver.input());
        }
        ElementFacts initial = facts(sessions);
        for (Session session : sessions) {
            session.solver.validate(initial);
            session.analysis = session.solver.result();
        }
        ElementFacts facts = facts(sessions);
        validateIdentities(sessions, diagnostics);
        for (var entry : facts.storages.entrySet()) {
            if (entry.getValue().state() == ElementState.CONFLICT) {
                StorageSlot slot = entry.getKey();
                ContainerContext context = context(slot, sessions);
                diagnostics.add(conflict("CONTAINER_FACT_CONFLICT", context.access(), -1, entry.getValue().requirements(),
                        "Incompatible representations for one storage channel").withContainers(List.of(context)));
            }
        }
        for (var entry : facts.values.entrySet()) {
            if (entry.getValue().state() != ElementState.CONFLICT) continue;
            List<MigrationRequirement> requirements = entry.getValue().requirements();
            var point = sessions.stream().map(session -> session.analysis.points().get(entry.getKey())).filter(Objects::nonNull).findFirst().orElseThrow();
            boolean covered = facts.storages.entrySet().stream().filter(storage -> storage.getValue().state() == ElementState.CONFLICT)
                    .anyMatch(storage -> requirements.stream().allMatch(requirement -> storage.getValue().requirements().stream()
                            .anyMatch(owner -> sameRequirement(owner, requirement))) && sessions.stream()
                            .filter(session -> session.analysis.values().contains(entry.getKey()))
                            .allMatch(session -> session.analysis.graph().storages(List.of(ConstraintGraph.value(point))).contains(storage.getKey())));
            if (!covered) {
                diagnostics.add(conflict("VALUE_FACT_CONFLICT", point, -1, requirements, "Incompatible representations for one value"));
            }
        }
        validateRules(sessions, facts, diagnostics);
        List<Diagnostic> issues = sessions.stream().flatMap(session -> session.solver.diagnostics(facts).stream()).toList();
        diagnostics.addAll(mergeDiagnostics(issues));
        if (!diagnostics.isEmpty()) {
            return new RepresentationPlan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        sessions.forEach(session -> session.analysis = session.solver.plan());
        List<Diagnostic> planDiagnostics = new ArrayList<>();
        var plan = RepresentationPlan.merge(sessions.stream().map(session -> session.analysis.plan()).toList(), classes, planDiagnostics);
        diagnostics.addAll(planDiagnostics);
        return plan;
    }

    private static ElementFacts facts(List<Session> sessions) {
        ElementFacts facts = new ElementFacts();
        sessions.forEach(session -> facts.add(session.declarations, session.analysis));
        return facts;
    }

    private static boolean sameRequirement(MigrationRequirement first, MigrationRequirement second) {
        return first.target().equals(second.target()) && first.parameter() == second.parameter()
                && first.id().equals(second.id()) && first.representation().equals(second.representation());
    }

    private record IssueKey(String code, MethodKey method, int instruction, int parameter, String source, String target,
            String identity, String detail) { }

    private static List<Diagnostic> mergeDiagnostics(List<Diagnostic> diagnostics) {
        Map<IssueKey, Diagnostic> merged = new LinkedHashMap<>();
        for (Diagnostic diagnostic : diagnostics) {
            var key = new IssueKey(diagnostic.code(), diagnostic.method(), diagnostic.instruction(), diagnostic.parameter(),
                    diagnostic.sourceType(), diagnostic.targetType(), diagnostic.failureSymbol(), diagnostic.failureDetail());
            merged.merge(key, diagnostic, (first, second) -> new Diagnostic(first.code(), first.method(), first.instruction(), first.parameter(),
                    first.message(), first.migration(), java.util.stream.Stream.concat(first.propagation().stream(), second.propagation().stream()).distinct().toList(),
                    java.util.stream.Stream.concat(first.candidates().stream(), second.candidates().stream()).distinct().toList(),
                    first.target(), first.targetParameter(), first.sourceType(), first.targetType(), first.requirement(), first.dependencyPath(), first.failureSymbol(),
                    java.util.stream.Stream.concat(first.containers().stream(), second.containers().stream()).distinct().toList(),
                    mergeRequirements(first.migrations(), second.migrations()), first.failureDetail()));
        }
        return List.copyOf(merged.values());
    }

    private static List<MigrationRequirement> mergeRequirements(List<MigrationRequirement> first,
            List<MigrationRequirement> second) {
        return RepresentationPlan.mergeContributors(List.of(first, second));
    }

    private static void validateIdentities(List<Session> sessions, List<Diagnostic> diagnostics) {
        Map<String, List<MigrationRequirement>> identities = new LinkedHashMap<>();
        sessions.forEach(session -> session.requirements().forEach(value ->
                identities.computeIfAbsent(value.id(), ignored -> new ArrayList<>()).add(value)));
        for (var entry : identities.entrySet()) {
            if (entry.getValue().stream().map(MigrationRequirement::representation).distinct().count() > 1) {
                var target = entry.getValue().getFirst().target();
                diagnostics.add(conflict("MIGRATION_ID_CONFLICT", new ProgramPoint(target, -1, target.toString()), -1, entry.getValue(),
                        "Migration identity requires incompatible representations: " + entry.getKey()));
            }
        }
    }

    private static void validateRules(List<Session> sessions, ElementFacts facts, List<Diagnostic> diagnostics) {
        Map<RepresentationSolver.RuleUse, List<Session>> uses = new LinkedHashMap<>();
        sessions.forEach(session -> session.analysis.rules().keySet().forEach(use ->
                uses.computeIfAbsent(use, ignored -> new ArrayList<>()).add(session)));
        var orderedUses = uses.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<RepresentationSolver.RuleUse, List<Session>> entry) -> entry.getKey().location().method().toString())
                .thenComparingInt(entry -> entry.getKey().location().instruction())
                .thenComparing(entry -> entry.getKey().kind().name()).thenComparingInt(entry -> entry.getKey().parameter())).toList();
        for (var entry : orderedUses) {
            List<Session> owners = entry.getValue();
            if (owners.size() < 2 || owners.stream().map(session -> session.declarations.getFirst().representation()).distinct().count() > 1) continue;
            var first = owners.getFirst().analysis.rules().get(entry.getKey());
            if (owners.stream().allMatch(session -> compatible(first, session.analysis.rules().get(entry.getKey())))) continue;
            var location = entry.getKey().location();
            ProgramPoint point = owners.getFirst().analysis.points().get(location);
            if (point == null) point = new ProgramPoint(location.method(), location.instruction(), first.helper().toString());
            ProgramPoint usePoint = point;
            List<MigrationRequirement> requirements = owners.stream().map(session -> session.requirement(usePoint, entry.getKey())).toList();
            for (var storage : facts.storages.entrySet()) {
                if (owners.stream().anyMatch(session -> session.analysis.graph().storages(List.of(session.subject(usePoint, entry.getKey())))
                        .contains(storage.getKey()))) {
                    storage.getValue().conflict();
                }
            }
            List<ContainerContext> containers = owners.stream()
                    .flatMap(session -> session.solver.contextsAt(usePoint, entry.getKey(), facts).stream()).distinct().toList();
            for (Session owner : owners) {
                for (var subject : owner.analysis.graph().ancestors(List.of(owner.subject(usePoint, entry.getKey())))) {
                    if (subject.kind() != ConstraintGraph.Kind.VALUE) continue;
                    var value = facts.values.get(new RepresentationPlan.Location(subject.location().method(), subject.location().instruction()));
                    if (value != null) value.conflict();
                }
            }
            diagnostics.add(conflict("REPRESENTATION_RULE_CONFLICT", new ProgramPoint(location.method(), location.instruction(),
                    usePoint.symbol()), entry.getKey().parameter(), requirements,
                    "Incompatible " + entry.getKey().kind() + " rules at one use parameter=" + entry.getKey().parameter())
                    .withContainers(containers));
        }
    }

    private static boolean compatible(RepresentationMigration.Rule first, RepresentationMigration.Rule second) {
        return first.kind() == second.kind() && first.version() == second.version()
                && Objects.equals(first.source(), second.source()) && first.helper().equals(second.helper())
                && first.binding() == second.binding() && first.parameter() == second.parameter();
    }

    private static ContainerContext context(StorageSlot slot, List<Session> sessions) {
        ContainerContext context = sessions.stream().flatMap(session -> session.analysis.containers().stream())
                .filter(value -> value.storage().method().equals(slot.storage().method())
                        && value.storage().instruction() == slot.storage().instruction() && value.channel() == slot.channel())
                .min(Comparator.comparing((ContainerContext value) -> !value.operation().startsWith("READ"))
                        .thenComparing(value -> value.access().key())).orElseThrow();
        return new ContainerContext(context.storage(), context.descriptor(), context.channel(), context.access(),
                context.operation(), ElementState.CONFLICT);
    }

    private static Diagnostic conflict(String code, ProgramPoint point, int parameter, List<MigrationRequirement> requirements, String message) {
        List<MigrationRequirement> ordered = requirements.stream().distinct()
                .sorted(Comparator.comparing((MigrationRequirement value) -> value.target().toString())
                        .thenComparingInt(MigrationRequirement::parameter).thenComparing(MigrationRequirement::id)).toList();
        var first = ordered.getFirst();
        String original = Type.getArgumentTypes(first.target().descriptor())[first.parameter()].getDescriptor();
        return new Diagnostic(code, point.method(), point.instruction(), parameter, message)
                .withContext(first.target(), first.parameter(), original, first.representation(), null, List.of(), point.symbol())
                .withMigrations(ordered);
    }
}
