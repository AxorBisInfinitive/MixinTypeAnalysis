package love.crysh.mixinta.analysis.container;

import org.objectweb.asm.tree.MethodInsnNode;

/** 接收者来源完成后解析的容器访问，summary 为空表示当前接收者无法满足契约。 */
public record ContainerAccess(ContainerFlow flow, int instruction, MethodInsnNode call,
        ContainerSummary summary, StorageReferences receivers) { }
