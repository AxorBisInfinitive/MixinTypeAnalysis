package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassHierarchy;
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
        ElementFacts facts = new ElementFacts();
        for (Session session : sessions) {
            session.analysis = session.solver.finish();
            facts.add(session.declarations, session.analysis, session.diagnostics.isEmpty());
            diagnostics.addAll(session.diagnostics);
        }
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
            boolean covered = facts.storages.values().stream().filter(value -> value.state() == ElementState.CONFLICT)
                    .anyMatch(value -> value.requirements().containsAll(requirements));
            if (!covered) {
                var location = entry.getKey();
                diagnostics.add(conflict("VALUE_FACT_CONFLICT", new ProgramPoint(location.method(), location.instruction(),
                        location.method().toString()), -1, requirements, "Incompatible representations for one value"));
            }
        }
        validateRules(sessions, facts, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new RepresentationPlan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return RepresentationPlan.merge(sessions.stream().map(session -> session.analysis.plan()).toList(), classes, diagnostics);
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
            List<MigrationRequirement> requirements = owners.stream().flatMap(session -> session.requirements().stream()).toList();
            List<ContainerContext> containers = new ArrayList<>();
            for (var storage : facts.storages.entrySet()) {
                if (storage.getValue().requirements().containsAll(requirements)) {
                    storage.getValue().conflict();
                    containers.add(context(storage.getKey(), sessions));
                }
            }
            var value = facts.values.get(location);
            if (value != null) value.conflict();
            diagnostics.add(conflict("REPRESENTATION_RULE_CONFLICT", new ProgramPoint(location.method(), location.instruction(),
                    first.helper().toString()), entry.getKey().parameter(), requirements,
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
