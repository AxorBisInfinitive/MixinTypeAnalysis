package love.crysh.mixinta.analysis;

/** 传播边产生的操作类别。 */
public enum DependencyKind {
    SOURCE_RESULT,
    BOXING,
    CONTAINER_WRITE,
    CONTAINER_ALLOCATION,
    CONTAINER_READ,
    CONTAINER_OBSERVE,
    CONTAINER_CLEAR,
    CONTAINER_ESCAPE,
    CONTAINER_EXTERNAL_ACCESS,
    CONTAINER_CALL_ARGUMENT,
    CONTAINER_RETURN,
    CONTAINER_VIEW,
    UNBOXING,
    TARGET_ARGUMENT,
    LOCAL_MERGE,
    OTHER;

    static DependencyKind fromReason(String reason) {
        String value = reason == null ? "" : reason.toLowerCase();
        if (value.contains("box") && !value.contains("unbox")) return BOXING;
        if (value.contains("unbox")) return UNBOXING;
        if (value.contains("allocation")) return CONTAINER_ALLOCATION;
        if (value.contains("add") || value.contains("write")) return CONTAINER_WRITE;
        if (value.contains("poll") || value.contains("read")) return CONTAINER_READ;
        if (value.contains("target")) return TARGET_ARGUMENT;
        if (value.contains("local")) return LOCAL_MERGE;
        return SOURCE_RESULT;
    }
}
