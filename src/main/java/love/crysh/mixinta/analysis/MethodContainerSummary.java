package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import java.util.List;
import java.util.Set;

/** 方法体的直接事实。普通调用保留符号绑定，调用图上的组合必须另行求解。 */
record MethodContainerSummary(MethodKey method, int access, boolean hasBody, List<Parameter> parameters,
        List<StorageId> allocations, List<Returned> returns, List<Call> calls, List<Effect> effects) {
    MethodContainerSummary {
        parameters = List.copyOf(parameters);
        allocations = List.copyOf(allocations);
        returns = List.copyOf(returns);
        calls = List.copyOf(calls);
        effects = List.copyOf(effects);
    }

    sealed interface Origin permits Receiver, Parameter, Allocation, Producer {
    }

    enum Receiver implements Origin { INSTANCE }

    record Parameter(int index, String descriptor) implements Origin {
    }

    record Allocation(StorageId storage) implements Origin {
    }

    /** 调用、字段或数组读取等来源保留真实位置，后续可将其关联到调用摘要或边界。 */
    record Producer(ProgramPoint point) implements Origin {
    }

    /** nullable 保留显式 null 来源，参数与 Producer 的空值性仍由调用上下文或后续契约确定。 */
    record Value(Set<Origin> origins, boolean nullable) {
        Value {
            origins = Set.copyOf(origins);
        }

        Set<StorageId> storages() {
            return origins.stream().filter(Allocation.class::isInstance).map(Allocation.class::cast)
                    .map(Allocation::storage).collect(java.util.stream.Collectors.toSet());
        }
    }

    record Returned(ProgramPoint point, Value value) {
    }

    record Call(ProgramPoint point, MethodKey target, int opcode, boolean interfaceOwner,
            Value receiver, List<Value> arguments, ContainerSummary intrinsic) {
        Call {
            arguments = List.copyOf(arguments);
        }
    }

    record Effect(ProgramPoint point, ContainerEffects.Kind kind, int parameter, Value subject) {
    }
}
