package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按分配位置汇总潜在外部访问。元素表示选定后，全部相关访问必须满足同一覆盖要求。 */
public final class ContainerEffects {
    /** UNKNOWN 表示调用效果未确定，ESCAPED 表示存在未建模的存储、返回或捕获边界。状态按枚举顺序合并。 */
    public enum EscapeState { LOCAL, UNKNOWN, ESCAPED }

    public enum Kind {
        RETURN(EscapeState.ESCAPED),
        FIELD_STORE(EscapeState.ESCAPED),
        ARRAY_STORE(EscapeState.ESCAPED),
        CALL_ARGUMENT(EscapeState.UNKNOWN),
        DYNAMIC_CAPTURE(EscapeState.ESCAPED),
        UNKNOWN_OPERATION(EscapeState.UNKNOWN),
        UNSUPPORTED_USE(EscapeState.UNKNOWN);

        private final EscapeState escape;

        public EscapeState escape() { return escape; }

        Kind(EscapeState escape) {
            this.escape = escape;
        }

        public String code() {
            return escape == EscapeState.ESCAPED ? "ESCAPED_CONTAINER" : "UNKNOWN_CONTAINER_USE";
        }
    }

    public record Access(ProgramPoint point, Kind kind, int parameter, Set<StorageId> storages) {
        public Access {
            storages = Set.copyOf(storages);
        }
    }

    private final List<Access> accesses;
    private final Map<StorageId, EscapeState> escapes;

    private ContainerEffects(List<Access> accesses) {
        this.accesses = List.copyOf(accesses);
        Map<StorageId, EscapeState> states = new LinkedHashMap<>();
        for (Access access : accesses) {
            for (StorageId storage : access.storages()) {
                states.merge(storage, access.kind().escape(), (first, second) -> first.compareTo(second) >= 0 ? first : second);
            }
        }
        escapes = Map.copyOf(states);
    }

    static ContainerEffects collect(List<ContainerFlow> flows, Map<MethodKey, MethodContainerSummary> summaries,
            ContainerCallGraph graph, ContainerRules containers, List<ContainerAccess> uses) {
        List<Access> accesses = new ArrayList<>();
        if (graph == null) return new ContainerEffects(accesses);
        Map<MethodKey, ContainerFlow> methods = new LinkedHashMap<>();
        flows.forEach(flow -> methods.put(flow.source().key(), flow));
        for (MethodContainerSummary summary : summaries.values()) {
            for (MethodContainerSummary.Effect effect : summary.effects()) {
                boolean ordinaryCall = effect.kind() == Kind.CALL_ARGUMENT || effect.kind() == Kind.UNKNOWN_OPERATION;
                if (ordinaryCall && graph.covered(effect.point())
                        || effect.kind() == Kind.RETURN && graph.internalReturn(summary.method())) continue;
                if (effect.kind() == Kind.UNKNOWN_OPERATION) {
                    var flow = methods.get(effect.point().method());
                    int index = effect.point().instruction();
                    if (flow.source().instruction(index) instanceof MethodInsnNode call
                            && containers.resolve(call, flow.receiverReferences(index, call)) != null) continue;
                }
                record(accesses, graph.resolve(summary.method(), effect.subject()).storages(),
                        effect.point(), effect.kind(), effect.parameter());
            }
        }
        for (ContainerAccess use : uses) {
            if (use.summary() == null && containers.resolve(use.call()) != null) {
                record(accesses, use.receivers().storages(), use.flow().source().point(use.instruction()), Kind.UNKNOWN_OPERATION, -1);
            }
        }
        return new ContainerEffects(accesses);
    }

    private static void record(List<Access> accesses, Set<StorageId> storages, ProgramPoint point, Kind kind, int parameter) {
        if (!storages.isEmpty()) accesses.add(new Access(point, kind, parameter, storages));
    }

    public List<Access> accesses() {
        return List.copyOf(accesses);
    }

    public EscapeState state(StorageId storage) {
        return escapes.getOrDefault(storage, EscapeState.LOCAL);
    }
}
