package love.crysh.mixinta.model;

/** 转换动作的稳定外部代码。 */
public enum PlanAction {
    LOAD_REFERENCE("load-reference"),
    STORE_REFERENCE("store-reference"),
    CAST_REFERENCE("cast-reference"),
    REMOVE_BOXING("remove-boxing"),
    REPLACE_UNBOXING("replace-unboxing"),
    ENCODE_RETURN("encode-return"),
    MIGRATE_INVOCATION("migrate-invocation"),
    REPLACE_PRODUCER("replace-producer"),
    OBSERVE_MAP_VALUES("observe-map-values"),
    OBSERVE_VIEW_ELEMENTS("observe-view-elements"),
    ENCODE_ARGUMENT("encode-argument"),
    REFERENCE_ARGUMENT("reference-argument"),
    GENERATE_RULE_BRIDGE("generate-rule-bridge");

    private final String code;

    PlanAction(String code) { this.code = code; }

    public String code() { return code; }
}
