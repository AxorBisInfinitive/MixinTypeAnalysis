package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.IdentityHashMap;
import java.util.Map;

/** 在稳定来源帧上建立当前契约集合对应的容器分配与别名。 */
public final class ContainerFlow {
    private final MethodFlow source;
    private final Map<AbstractInsnNode, StorageId> allocations = new IdentityHashMap<>();
    private ContainerAliases aliases;

    ContainerFlow(MethodFlow source, ContainerRules containers) {
        this.source = source;
        for (int index = 0; index < source.instructionCount(); index++) {
            AbstractInsnNode instruction = source.instruction(index);
            if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && containers.supportsAllocation(allocation.desc)) {
                allocations.put(instruction, new StorageId(source.key(), index, Type.getObjectType(allocation.desc).getDescriptor()));
            }
        }
    }

    public MethodFlow source() { return source; }
    public Map<AbstractInsnNode, StorageId> allocations() { return java.util.Collections.unmodifiableMap(allocations); }
    public ContainerAliases aliases() { return aliases; }
    void initializeAliases(ContainerAliases aliases) {
        if (this.aliases != null) throw new IllegalStateException("Container aliases are already initialized");
        this.aliases = aliases;
    }

    public StorageReferences receiverReferences(int index, MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        return aliases.stack(index, source.stackSize(index) - arguments - 1);
    }
}
