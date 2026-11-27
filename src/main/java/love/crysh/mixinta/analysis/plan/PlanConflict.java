package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MigrationEvidence;

import java.util.List;

/** 合并失败的计划位置和全部贡献声明。 */
public record PlanConflict(InstructionLocation location, String message, List<MigrationEvidence> contributors) {
    public PlanConflict {
        contributors = MigrationEvidence.merge(List.of(contributors));
    }
}
