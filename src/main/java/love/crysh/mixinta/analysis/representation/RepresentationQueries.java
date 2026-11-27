package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerOperation;
import love.crysh.mixinta.analysis.container.ContainerRules;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.container.StorageId;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 不依赖阶段可变状态的调用、容器与位置查询。 */
final class RepresentationQueries {
    static Migration target(MethodInsnNode call, Map<MethodKey, Migration> targets, ClassHierarchy hierarchy) {
        if (targets.keySet().stream().noneMatch(key -> key.name().equals(call.name) && key.descriptor().equals(call.desc))) {
            return null;
        }
        return targets.get(hierarchy.resolve(call.owner, call.name, call.desc));
    }

    static InstructionLocation location(AnalysisSite site) {
        return new InstructionLocation(site.method(), site.index());
    }

    static ContainerSummary summary(AnalysisSite site, MethodInsnNode call, ContainerRules containers) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        return containers.resolve(call, site.flow().receiverReferences(site.index(), call));
    }

    static boolean isContainerWrite(AnalysisSite site, MethodInsnNode call, int parameter, ContainerRules containers) {
        ContainerSummary summary = summary(site, call, containers);
        return summary != null && summary.writesElement() && summary.elementParameter() == parameter;
    }

    static boolean isMapComparison(AnalysisSite site, MethodInsnNode call, int parameter, ContainerRules containers) {
        ContainerSummary summary = summary(site, call, containers);
        return summary != null && (summary.keyParameter() == parameter || summary.observedParameter() == parameter);
    }

    static DependencyKind dependencyKind(ContainerOperation operation) {
        return switch (operation) {
            case ALLOCATE -> DependencyKind.CONTAINER_ALLOCATION;
            case VIEW -> DependencyKind.CONTAINER_VIEW;
            case WRITE, READ_WRITE -> DependencyKind.CONTAINER_WRITE;
            case READ, READ_REMOVE -> DependencyKind.CONTAINER_READ;
            case OBSERVE -> DependencyKind.CONTAINER_OBSERVE;
            case REMOVE_MATCH -> DependencyKind.CONTAINER_WRITE;
            case CLEAR -> DependencyKind.CONTAINER_CLEAR;
        };
    }

    static boolean isBox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("valueOf") && call.desc.equals("(J)Ljava/lang/Long;");
    }

    static boolean isUnbox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("longValue") && call.desc.equals("()J");
    }

    static boolean is(MethodInsnNode call, MethodKey key) {
        return call.owner.equals(key.owner()) && call.name.equals(key.name()) && call.desc.equals(key.descriptor());
    }

    static MethodKey symbol(MethodInsnNode call) {
        return new MethodKey(call.owner, call.name, call.desc);
    }

    static boolean touches(OriginValue value, Set<AbstractInsnNode> selected) {
        return value != null && value.origins().stream().anyMatch(selected::contains);
    }

    static String packageName(String owner) {
        return owner.substring(0, Math.max(0, owner.lastIndexOf('/')));
    }

    static ProgramPoint storagePoint(StorageId storage) {
        return new ProgramPoint(storage.method(), storage.instruction(), Type.getType(storage.descriptor()).getInternalName());
    }

    static List<PropagationEdge> orderedEdges(java.util.Collection<PropagationEdge> edges) {
        return edges.stream().sorted(java.util.Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList();
    }
}
