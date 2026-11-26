package love.crysh.mixinta.analysis;

import love.crysh.mixinta.diagnostic.MigrationRequirement;
import love.crysh.mixinta.model.ElementState;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 批次中的值与存储要求保留每个声明的身份，旧类型来源由传播规则处理。 */
final class ElementFacts {
    static final class Fact {
        private final List<MigrationRequirement> requirements = new ArrayList<>();
        private boolean unresolved;
        private boolean conflict;
        private boolean nullOnly;

        void add(MigrationRequirement requirement, boolean resolved) {
            if (!requirements.contains(requirement)) requirements.add(requirement);
            unresolved |= !resolved;
        }

        void conflict() {
            conflict = true;
        }

        ElementState state() {
            if (conflict || !nullOnly && requirements.stream().map(MigrationRequirement::representation).distinct().count() > 1) {
                return ElementState.CONFLICT;
            }
            if (requirements.isEmpty()) return ElementState.ORIGINAL;
            return unresolved ? ElementState.UNKNOWN : ElementState.MIGRATED;
        }

        List<MigrationRequirement> requirements() {
            return requirements.stream().sorted(Comparator.comparing((MigrationRequirement value) -> value.target().toString())
                    .thenComparingInt(MigrationRequirement::parameter).thenComparing(MigrationRequirement::id)).toList();
        }

        boolean accepts(Type type) {
            return nullOnly || requirements.stream().anyMatch(requirement -> requirement.representation().equals(type.getDescriptor()));
        }
    }

    final Map<RepresentationPlan.Location, Fact> values = new LinkedHashMap<>();
    final Map<StorageSlot, Fact> storages = new LinkedHashMap<>();

    void add(List<RepresentationMigration> declarations, RepresentationSolver.Analysis analysis) {
        for (var context : analysis.containers()) {
            var point = context.storage();
            StorageSlot slot = new StorageSlot(new StorageId(point.method(), point.instruction(), context.descriptor()), context.channel());
            storages.computeIfAbsent(slot, ignored -> new Fact());
        }
        for (RepresentationMigration declaration : declarations) {
            analysis.values().forEach(location -> {
                Fact fact = values.computeIfAbsent(location, ignored -> new Fact());
                fact.nullOnly = analysis.nulls().contains(location);
                fact.add(requirement(declaration, analysis, List.of(ConstraintGraph.value(analysis.points().get(location)))),
                        !analysis.unresolvedValues().contains(location));
            });
            analysis.storages().forEach(slot -> storages.computeIfAbsent(slot, ignored -> new Fact()).add(
                    requirement(declaration, analysis, List.of(ConstraintGraph.storage(slot))), !analysis.unresolvedStorages().contains(slot)));
        }
    }

    static MigrationRequirement requirement(RepresentationMigration declaration, RepresentationSolver.Analysis analysis,
            java.util.Collection<ConstraintGraph.Subject> subjects) {
        return new MigrationRequirement(declaration.id(), declaration.target(), declaration.parameter(),
                declaration.representation().getDescriptor(), analysis.graph().evidence(subjects),
                subjects.stream().map(ConstraintGraph.Subject::point).distinct().toList());
    }

    boolean value(RepresentationPlan.Location location, Type representation) {
        Fact fact = values.get(location);
        return fact != null && fact.accepts(representation);
    }

    boolean storage(StorageSlot slot, Type representation) {
        Fact fact = storages.get(slot);
        return fact != null && !fact.requirements.isEmpty() && fact.accepts(representation);
    }
}
