package love.crysh.mixinta.analysis;

/** instruction 对应固定 Mixin 后快照，-1 表示方法级动作。 */
public record PlanChange(MethodKey method, int instruction, String action, String migration, String rule) {
}
