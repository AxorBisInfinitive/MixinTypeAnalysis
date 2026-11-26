package love.crysh.mixinta.analysis;

import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** 从已稳定的来源帧建立引用事实，按指令位置区分局部槽的不同生命周期。 */
final class ContainerAliases {
    enum Kind { LOCAL, STACK }

    record Location(int instruction, Kind kind, int slot) {
    }

    private final Map<Location, StorageReferences> locations = new HashMap<>();
    private final Map<SourceValue, StorageReferences> values = new IdentityHashMap<>();
    private final Function<SourceValue, StorageReferences> resolver;

    ContainerAliases(Frame<SourceValue>[] frames, Function<SourceValue, StorageReferences> resolver) {
        this.resolver = resolver;
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
        return values.computeIfAbsent(value, resolver);
    }
}
