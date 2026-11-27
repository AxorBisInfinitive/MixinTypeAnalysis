package love.crysh.mixinta.analysis.container;

/** 标准容器操作的元素流摘要。 */
public record ContainerSummary(ContainerOperation operation, int channel, int elementParameter,
        int keyParameter, int observedParameter, ContainerView view) {
    public ContainerSummary(ContainerOperation operation, int channel, int elementParameter, int keyParameter, int observedParameter) {
        this(operation, channel, elementParameter, keyParameter, observedParameter, null);
    }

    public ContainerSummary(ContainerOperation operation, int channel, int elementParameter) {
        this(operation, channel, elementParameter, -1, -1);
    }

    public boolean readsElement() {
        return operation == ContainerOperation.READ || operation == ContainerOperation.READ_REMOVE
                || operation == ContainerOperation.READ_WRITE;
    }

    public boolean writesElement() {
        return operation == ContainerOperation.WRITE || operation == ContainerOperation.READ_WRITE;
    }

}
