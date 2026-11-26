package love.crysh.mixinta.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按分配位置汇总潜在外部访问。元素表示选定后，全部相关访问必须满足同一覆盖要求。 */
final class ContainerEffects {
    /** UNKNOWN 表示调用效果未确定，ESCAPED 表示存在未建模的存储、返回或捕获边界。状态按枚举顺序合并。 */
    enum EscapeState { LOCAL, UNKNOWN, ESCAPED }

    enum Kind {
        RETURN(EscapeState.ESCAPED),
        FIELD_STORE(EscapeState.ESCAPED),
        ARRAY_STORE(EscapeState.ESCAPED),
        CALL_ARGUMENT(EscapeState.UNKNOWN),
        DYNAMIC_CAPTURE(EscapeState.ESCAPED),
        UNKNOWN_OPERATION(EscapeState.UNKNOWN),
        UNSUPPORTED_USE(EscapeState.UNKNOWN);

        final EscapeState escape;

        Kind(EscapeState escape) {
            this.escape = escape;
        }

        String code() {
            return escape == EscapeState.ESCAPED ? "ESCAPED_CONTAINER" : "UNKNOWN_CONTAINER_USE";
        }
    }

    record Access(ProgramPoint point, Kind kind, int parameter, Set<StorageId> storages) {
        Access {
            storages = Set.copyOf(storages);
        }
    }

    private final List<Access> accesses = new ArrayList<>();
    private final Map<StorageId, EscapeState> escapes = new LinkedHashMap<>();

    void record(Set<StorageId> storages, ProgramPoint point, Kind kind, int parameter) {
        if (storages.isEmpty()) {
            return;
        }
        accesses.add(new Access(point, kind, parameter, storages));
        for (StorageId storage : storages) {
            escapes.merge(storage, kind.escape, (first, second) -> first.compareTo(second) >= 0 ? first : second);
        }
    }

    List<Access> accesses() {
        return List.copyOf(accesses);
    }

    EscapeState state(StorageId storage) {
        return escapes.getOrDefault(storage, EscapeState.LOCAL);
    }
}
