package love.crysh.mixinta.analysis.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 存在任一冲突时不提供可执行计划。 */
public final class PlanMergeResult {
    private final RepresentationPlan plan;
    private final List<PlanConflict> conflicts;

    private PlanMergeResult(RepresentationPlan plan, List<PlanConflict> conflicts) {
        this.plan = plan;
        this.conflicts = List.copyOf(conflicts);
    }

    static PlanMergeResult success(RepresentationPlan plan) {
        return new PlanMergeResult(Objects.requireNonNull(plan), List.of());
    }

    static PlanMergeResult failure(List<PlanConflict> conflicts) {
        if (conflicts.isEmpty()) throw new IllegalArgumentException("Missing plan conflicts");
        return new PlanMergeResult(null, conflicts);
    }

    public Optional<RepresentationPlan> plan() { return Optional.ofNullable(plan); }
    public List<PlanConflict> conflicts() { return conflicts; }
}
