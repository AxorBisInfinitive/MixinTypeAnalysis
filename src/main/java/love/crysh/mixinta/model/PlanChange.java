package love.crysh.mixinta.model;

/** instruction 对应固定 Mixin 后快照，-1 表示方法级动作。 */
public record PlanChange(MethodKey method, int instruction, String action, String migration, String rule) {
    public static PlanChange of(MethodKey method, int instruction, PlanAction action, String migration, String rule) {
        return new PlanChange(method, instruction, action.code(), migration, rule);
    }
}
