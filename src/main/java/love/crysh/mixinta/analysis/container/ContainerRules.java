package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 自定义契约以精确方法键注册，内置摘要与自定义摘要共用同一求解路径。 */
public final class ContainerRules {

    private final Map<MethodKey, ContainerContract> definitions;

    public ContainerRules(List<ContainerContract> definitions) {
        Map<MethodKey, ContainerContract> index = new LinkedHashMap<>();
        for (ContainerContract definition : definitions) index.put(definition.method(), definition);
        this.definitions = Map.copyOf(index);
    }

    public ContainerSummary resolve(MethodInsnNode call) {
        ContainerContract definition = definitions.get(new MethodKey(call.owner, call.name, call.desc));
        if (definition != null && call.getOpcode() != Opcodes.INVOKESTATIC) return definition.summary();
        return JdkContainerSummaries.resolve(call);
    }

    public ContainerSummary resolve(MethodInsnNode call, StorageReferences receiver) {
        ContainerContract definition = definitions.get(new MethodKey(call.owner, call.name, call.desc));
        if (definition != null && call.getOpcode() != Opcodes.INVOKESTATIC) return definition.summary();
        return JdkContainerSummaries.resolve(call, receiver);
    }

    public boolean supportsAllocation(String owner) {
        return JdkContainerSummaries.supportsAllocation(owner) || definitions.values().stream()
                .anyMatch(definition -> definition.method().owner().equals(owner)
                        && definition.summary().operation() == ContainerOperation.ALLOCATE);
    }

    public boolean models(MethodKey method) {
        return definitions.containsKey(method);
    }

}
