package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.container.StorageReferences;

import org.objectweb.asm.tree.MethodInsnNode;

record ContainerUse(AnalysisSite site, MethodInsnNode call, ContainerSummary summary,
        StorageReferences receivers) {
}
