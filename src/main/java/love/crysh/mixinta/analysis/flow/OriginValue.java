package love.crysh.mixinta.analysis.flow;

import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.Set;

/** 来源节点属于同一只读输入快照，width 为 JVM 值宽度。 */
public record OriginValue(int width, Set<AbstractInsnNode> origins) {
    public OriginValue {
        if (width < 1 || width > 2) throw new IllegalArgumentException("Invalid value width " + width);
        origins = Set.copyOf(origins);
    }

    public static OriginValue of(int width, AbstractInsnNode origin) {
        return new OriginValue(width, Set.of(origin));
    }
}
