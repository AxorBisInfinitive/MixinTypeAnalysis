package love.crysh.mixinta.model;

/** 存储表示要求的汇总状态，具体来源和未解决约束由对应事实保留。 */
public enum ElementState {
    UNKNOWN, ORIGINAL, MIGRATED, CONFLICT
}
