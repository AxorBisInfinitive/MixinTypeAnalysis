package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.ProgramPoint;

import java.util.List;
import java.util.Set;

record BatchConflict(String code, ProgramPoint point, int parameter, String message,
        List<MigrationEvidence> contributors, Set<StorageSlot> storages, RuleUse ruleUse) {
    BatchConflict {
        contributors = MigrationEvidence.merge(List.of(contributors));
        storages = Set.copyOf(storages);
    }

    BatchConflict(String code, ProgramPoint point, int parameter, String message, List<MigrationEvidence> contributors) {
        this(code, point, parameter, message, contributors, Set.of(), null);
    }

    BatchConflict withStorages(Set<StorageSlot> storages) {
        return new BatchConflict(code, point, parameter, message, contributors, storages, ruleUse);
    }

    BatchConflict withRuleUse(RuleUse use) {
        return new BatchConflict(code, point, parameter, message, contributors, storages, use);
    }
}
