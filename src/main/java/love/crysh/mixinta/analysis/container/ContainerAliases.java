package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.analysis.flow.OriginValue;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;

/** 从已稳定的来源帧建立引用事实，按指令位置区分局部槽的不同生命周期。 */
public final class ContainerAliases {
    private final MethodFlow source;
    // OriginValue 具有值相等语义，缓存必须继续区分来源对象身份。
    private final IdentityHashMap<OriginValue, StorageReferences> values = new IdentityHashMap<>();
    private final long positionCount;

    ContainerAliases(MethodFlow source, Function<OriginValue, StorageReferences> resolver) {
        this.source = source;
        long positions = 0;
        Function<OriginValue, StorageReferences> resolve = value -> Objects.requireNonNull(resolver.apply(value));
        for (int instruction = 0; instruction < source.instructionCount(); instruction++) {
            if (!source.reachable(instruction)) {
                continue;
            }
            for (int slot = 0; slot < source.localCount(instruction); slot++) {
                values.computeIfAbsent(source.local(instruction, slot), resolve);
                positions++;
            }
            for (int slot = 0; slot < source.stackSize(instruction); slot++) {
                values.computeIfAbsent(source.stack(instruction, slot), resolve);
                positions++;
            }
        }
        positionCount = positions;
    }

    long positionCount() { return positionCount; }
    long valueCount() { return values.size(); }

    public StorageReferences local(int instruction, int slot) {
        if (!reachable(instruction) || slot < 0 || slot >= source.localCount(instruction)) return null;
        return values.get(source.local(instruction, slot));
    }

    public StorageReferences stack(int instruction, int slot) {
        if (!reachable(instruction) || slot < 0 || slot >= source.stackSize(instruction)) return null;
        return values.get(source.stack(instruction, slot));
    }

    private boolean reachable(int instruction) {
        return instruction >= 0 && instruction < source.instructionCount() && source.reachable(instruction);
    }
}
