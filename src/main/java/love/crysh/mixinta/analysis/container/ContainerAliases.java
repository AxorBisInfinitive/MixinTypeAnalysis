package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.analysis.flow.OriginValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** 从已稳定的来源帧建立引用事实，按指令位置区分局部槽的不同生命周期。 */
public final class ContainerAliases {
    enum Kind { LOCAL, STACK }

    record Location(int instruction, Kind kind, int slot) {
    }

    private final Map<Location, StorageReferences> locations;

    ContainerAliases(MethodFlow source, Function<OriginValue, StorageReferences> resolver) {
        Map<Location, StorageReferences> locations = new HashMap<>();
        Map<OriginValue, StorageReferences> values = new IdentityHashMap<>();
        for (int instruction = 0; instruction < source.instructionCount(); instruction++) {
            if (!source.reachable(instruction)) {
                continue;
            }
            for (int slot = 0; slot < source.localCount(instruction); slot++) {
                locations.put(new Location(instruction, Kind.LOCAL, slot), values.computeIfAbsent(source.local(instruction, slot), resolver));
            }
            for (int slot = 0; slot < source.stackSize(instruction); slot++) {
                locations.put(new Location(instruction, Kind.STACK, slot), values.computeIfAbsent(source.stack(instruction, slot), resolver));
            }
        }
        this.locations = Map.copyOf(locations);
    }

    public StorageReferences local(int instruction, int slot) {
        return locations.get(new Location(instruction, Kind.LOCAL, slot));
    }

    public StorageReferences stack(int instruction, int slot) {
        return locations.get(new Location(instruction, Kind.STACK, slot));
    }

}
