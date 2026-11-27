package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.PlanChange;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static love.crysh.mixinta.analysis.plan.RepresentationPlan.*;

/** 收齐每个位置的要求后再验证，冲突保留完整候选集合的证据。 */
public final class RepresentationPlanMerger {
    private static final Comparator<InstructionLocation> LOCATIONS = Comparator
            .comparing((InstructionLocation location) -> location.method().toString())
            .thenComparingInt(InstructionLocation::instruction);
    private static final Comparator<LocalVariableKey> LOCALS = Comparator
            .comparing((LocalVariableKey key) -> key.method().toString()).thenComparingInt(LocalVariableKey::entryIndex);

    private RepresentationPlanMerger() { }

    private record LocalVariableKey(MethodKey method, int entryIndex) { }

    public static PlanMergeResult merge(List<RepresentationPlan> plans, Map<String, ClassNode> classes) {
        List<PlanConflict> conflicts = new ArrayList<>();
        Map<InstructionLocation, List<Edit>> editGroups = plans.stream().flatMap(plan -> plan.edits().stream())
                .collect(Collectors.groupingBy(Edit::location, () -> new TreeMap<>(LOCATIONS), Collectors.toList()));
        Map<InstructionLocation, List<Argument>> argumentGroups = plans.stream().flatMap(plan -> plan.arguments().stream())
                .collect(Collectors.groupingBy(Argument::location, () -> new TreeMap<>(LOCATIONS), Collectors.toList()));
        Map<LocalVariableKey, List<Local>> localGroups = plans.stream().flatMap(plan -> plan.locals().stream())
                .collect(Collectors.groupingBy(local -> new LocalVariableKey(local.method(), local.entryIndex()),
                        () -> new TreeMap<>(LOCALS), Collectors.toList()));
        Map<MethodKey, List<Bridge>> bridgeGroups = plans.stream().flatMap(plan -> plan.bridges().stream())
                .collect(Collectors.groupingBy(Bridge::method, () -> new TreeMap<>(Comparator.comparing(MethodKey::toString)), Collectors.toList()));
        Map<InstructionLocation, Edit> edits = new TreeMap<>(LOCATIONS);
        for (var entry : editGroups.entrySet()) {
            var candidates = entry.getValue();
            Edit first = candidates.getFirst();
            var evidence = MigrationEvidence.merge(candidates.stream().map(Edit::contributors).toList());
            if (candidates.stream().allMatch(candidate -> sameEdit(first, candidate))) {
                edits.put(entry.getKey(), first.withContributors(evidence));
            } else conflicts.add(new PlanConflict(entry.getKey(), "Instruction requires different edits", evidence));
        }
        List<Argument> arguments = new ArrayList<>();
        for (var entry : argumentGroups.entrySet()) {
            var candidates = entry.getValue();
            var parameters = candidates.stream().collect(Collectors.groupingBy(Argument::parameter, TreeMap::new, Collectors.toList()));
            List<Argument> normalized = new ArrayList<>();
            for (var parameter : parameters.entrySet()) {
                var group = parameter.getValue();
                Argument first = group.stream().min(Comparator.comparing(Argument::migration)).orElseThrow();
                var evidence = MigrationEvidence.merge(group.stream().map(Argument::contributors).toList());
                if (group.stream().allMatch(candidate -> sameArgument(first, candidate))) {
                    normalized.add(first.withContributors(evidence));
                } else conflicts.add(new PlanConflict(entry.getKey(),
                        "Argument requires incompatible conversions parameter=" + parameter.getKey(), evidence));
            }
            boolean descriptorsAgree = candidates.stream().map(Argument::original).distinct().count() == 1
                    && candidates.stream().map(Argument::implementation).distinct().count() == 1;
            if (parameters.size() > 1 && !descriptorsAgree) {
                conflicts.add(new PlanConflict(entry.getKey(), "Invocation requires incompatible final descriptors",
                        MigrationEvidence.merge(candidates.stream().map(Argument::contributors).toList())));
            }
            List<Edit> replacements = editGroups.get(entry.getKey());
            if (replacements == null) {
                arguments.addAll(normalized);
                continue;
            }
            var evidence = MigrationEvidence.merge(java.util.stream.Stream.concat(replacements.stream().map(Edit::contributors),
                    candidates.stream().map(Argument::contributors)).toList());
            Edit edit = edits.get(entry.getKey());
            if (parameters.size() == 1 && normalized.size() == 1 && canFuse(edit, normalized.getFirst())) {
                Argument query = normalized.getFirst();
                edits.put(entry.getKey(), new Edit(edit.location(), edit.action(), edit.helper(), edit.representation(), edit.binding(),
                        edit.parameter(), new Encoding(query.representation(), query.encoder()), evidence));
            } else conflicts.add(new PlanConflict(entry.getKey(), "Invocation replacement overlaps argument conversion", evidence));
        }
        List<Local> locals = new ArrayList<>();
        for (var entry : localGroups.entrySet()) {
            var candidates = entry.getValue();
            Local first = candidates.getFirst();
            var evidence = MigrationEvidence.merge(candidates.stream().map(Local::contributors).toList());
            try {
                if (candidates.stream().map(Local::descriptor).distinct().count() > 1) {
                    throw new IllegalArgumentException("Local variable requires incompatible descriptors");
                }
                List<String> signatures = candidates.stream().map(Local::signature).distinct()
                        .sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
                String signature = signatures.getFirst();
                if (signatures.size() > 1) {
                    var original = ClassFiles.findMethod(classes.get(first.method().owner()), first.method().name(),
                            first.method().descriptor()).localVariables.get(first.entryIndex());
                    signature = mergeSignatures(original.signature, signatures);
                }
                locals.add(new Local(first.method(), first.entryIndex(), first.descriptor(), signature, evidence));
            } catch (IllegalArgumentException failure) {
                conflicts.add(new PlanConflict(new InstructionLocation(first.method(), -1),
                        failure.getMessage() + " local=" + first.entryIndex(), evidence));
            }
        }
        List<Bridge> bridges = new ArrayList<>();
        for (var entry : bridgeGroups.entrySet()) {
            var candidates = entry.getValue();
            Bridge first = candidates.getFirst();
            var evidence = MigrationEvidence.merge(candidates.stream().map(Bridge::contributors).toList());
            if (candidates.stream().allMatch(candidate -> first.helper().equals(candidate.helper()))) {
                bridges.add(first.withContributors(evidence));
            } else conflicts.add(new PlanConflict(new InstructionLocation(entry.getKey(), -1), "Rule bridge requires different helpers", evidence));
        }
        if (!conflicts.isEmpty()) return PlanMergeResult.failure(conflicts.stream()
                .sorted(Comparator.comparing(PlanConflict::location, LOCATIONS).thenComparing(PlanConflict::message)).toList());
        return PlanMergeResult.success(new RepresentationPlan(List.copyOf(edits.values()), arguments, locals, bridges,
                plans.stream().flatMap(plan -> plan.changes().stream()).distinct()
                        .sorted(Comparator.comparing((PlanChange change) -> change.method().toString()).thenComparingInt(PlanChange::instruction)
                                .thenComparing(PlanChange::action).thenComparing(PlanChange::migration).thenComparing(PlanChange::rule)).toList(),
                plans.stream().flatMap(plan -> plan.dependencies().stream()).distinct()
                        .sorted(Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                                .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList()));
    }

    private static boolean sameEdit(Edit first, Edit second) {
        return first.location().equals(second.location()) && first.action().equals(second.action())
                && Objects.equals(first.helper(), second.helper()) && first.representation().equals(second.representation())
                && first.binding() == second.binding() && first.parameter() == second.parameter()
                && Objects.equals(first.query(), second.query());
    }

    private static boolean sameArgument(Argument first, Argument second) {
        return first.original().equals(second.original()) && first.implementation().equals(second.implementation())
                && first.representation().equals(second.representation()) && Objects.equals(first.encoder(), second.encoder());
    }

    private static boolean canFuse(Edit edit, Argument query) {
        if (edit == null || !edit.observation() || query.parameter() != 0 || query.encoder() == null
                || !query.original().equals(query.implementation())) return false;
        return edit.query() == null || edit.query().equals(new Encoding(query.representation(), query.encoder()));
    }

    private static String mergeSignatures(String original, List<String> signatures) {
        List<String> changed = signatures.stream().filter(signature -> !Objects.equals(original, signature)).toList();
        if (changed.size() == 1) return changed.getFirst();
        if (original == null || changed.contains(null)) {
            throw new IllegalArgumentException("Local variable requires incompatible generic signatures");
        }
        String[] parts = original.split("Ljava/lang/Long;", -1);
        String pattern = java.util.Arrays.stream(parts).map(Pattern::quote)
                .collect(Collectors.joining("(L[^;<>]+;)"));
        Pattern signaturePattern = Pattern.compile(pattern);
        var matches = changed.stream().map(signaturePattern::matcher).toList();
        if (matches.stream().anyMatch(match -> !match.matches())) {
            throw new IllegalArgumentException("Generic signature changes have incompatible structure");
        }
        StringBuilder result = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            int position = index;
            var replacements = matches.stream().map(match -> match.group(position))
                    .filter(value -> !value.equals("Ljava/lang/Long;")).distinct().toList();
            if (replacements.size() > 1) {
                throw new IllegalArgumentException("Generic argument requires incompatible representations");
            }
            result.append(replacements.isEmpty() ? "Ljava/lang/Long;" : replacements.getFirst()).append(parts[index]);
        }
        return result.toString();
    }
}
