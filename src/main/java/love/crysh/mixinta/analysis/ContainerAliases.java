package love.crysh.mixinta.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 从已稳定的来源帧建立引用事实，按指令位置区分局部槽的不同生命周期。 */
final class ContainerAliases {
    enum Kind { LOCAL, STACK }

    record Location(int instruction, Kind kind, int slot) {
    }

    private final Map<Location, StorageReferences> locations = new HashMap<>();
    private final Map<SourceValue, StorageReferences> values = new IdentityHashMap<>();
    private final Map<AbstractInsnNode, StorageId> allocations;
    private final Function<AbstractInsnNode, ProgramPoint> points;

    ContainerAliases(Frame<SourceValue>[] frames, Map<AbstractInsnNode, StorageId> allocations,
            Function<AbstractInsnNode, ProgramPoint> points) {
        this.allocations = allocations;
        this.points = points;
        for (int instruction = 0; instruction < frames.length; instruction++) {
            Frame<SourceValue> frame = frames[instruction];
            if (frame == null) {
                continue;
            }
            for (int slot = 0; slot < frame.getLocals(); slot++) {
                locations.put(new Location(instruction, Kind.LOCAL, slot), references(frame.getLocal(slot)));
            }
            for (int slot = 0; slot < frame.getStackSize(); slot++) {
                locations.put(new Location(instruction, Kind.STACK, slot), references(frame.getStack(slot)));
            }
        }
    }

    StorageReferences local(int instruction, int slot) {
        return locations.get(new Location(instruction, Kind.LOCAL, slot));
    }

    StorageReferences stack(int instruction, int slot) {
        return locations.get(new Location(instruction, Kind.STACK, slot));
    }

    private StorageReferences references(SourceValue value) {
        return values.computeIfAbsent(value, source -> origins(source.insns));
    }

    StorageReferences origins(Set<AbstractInsnNode> origins) {
        Set<StorageId> storages = new LinkedHashSet<>();
        Set<ProgramPoint> unknown = new LinkedHashSet<>();
        boolean nullable = false;
        for (AbstractInsnNode origin : origins) {
            StorageId storage = allocations.get(origin);
            if (storage != null) {
                storages.add(storage);
            } else if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                nullable = true;
            } else {
                unknown.add(points.apply(origin));
            }
        }
        return new StorageReferences(storages, unknown, nullable);
    }
}
