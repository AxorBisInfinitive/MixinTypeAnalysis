package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在方法内稳定来源帧上提取符号参数、返回关系及容器访问，未知调用保留在摘要中。 */
final class ContainerMethodAnalyzer {
    private final MethodFlow flow;
    private final ContainerRules containers;
    private final Map<Integer, MethodContainerSummary.Origin> parameters = new HashMap<>();
    private final List<MethodContainerSummary.Parameter> declarations;
    private final List<MethodContainerSummary.Returned> returns = new ArrayList<>();
    private final List<MethodContainerSummary.Call> calls = new ArrayList<>();
    private final List<MethodContainerSummary.Effect> effects = new ArrayList<>();

    ContainerMethodAnalyzer(MethodFlow flow) {
        this(flow, new ContainerRules(List.of()));
    }

    ContainerMethodAnalyzer(MethodFlow flow, ContainerRules containers) {
        this.flow = flow;
        this.containers = containers;
        declarations = parameters(flow.key);
        int local = 0;
        if ((flow.method.access & Opcodes.ACC_STATIC) == 0) {
            parameters.put(local++, MethodContainerSummary.Receiver.INSTANCE);
        }
        for (MethodContainerSummary.Parameter parameter : declarations) {
            parameters.put(local, parameter);
            local += Type.getType(parameter.descriptor()).getSize();
        }
    }

    static MethodContainerSummary unavailable(MethodKey method, int access) {
        return new MethodContainerSummary(method, access, false, parameters(method), List.of(), List.of(), List.of(), List.of());
    }

    private static List<MethodContainerSummary.Parameter> parameters(MethodKey method) {
        Type[] arguments = Type.getArgumentTypes(method.descriptor());
        List<MethodContainerSummary.Parameter> result = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            result.add(new MethodContainerSummary.Parameter(index, arguments[index].getDescriptor()));
        }
        return List.copyOf(result);
    }

    MethodContainerSummary analyze() {
        for (int index = 0; index < flow.instructions.length; index++) {
            if (flow.frames[index] == null) {
                continue;
            }
            AbstractInsnNode instruction = flow.instructions[index];
            ProgramPoint point = flow.point(index);
            if (instruction instanceof MethodInsnNode call) {
                analyzeCall(index, call, point);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                Type[] arguments = Type.getArgumentTypes(dynamic.desc);
                for (int parameter = 0; parameter < arguments.length; parameter++) {
                    SourceValue argument = flow.frames[index].getStack(flow.frames[index].getStackSize() - arguments.length + parameter);
                    if (isReference(arguments[parameter])) {
                        effect(point, ContainerEffects.Kind.DYNAMIC_CAPTURE, parameter, value(argument, point));
                    }
                }
            } else {
                switch (instruction.getOpcode()) {
                    case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN ->
                            returns.add(new MethodContainerSummary.Returned(point, value(flow.top(index), point)));
                    default -> { }
                }
                ContainerEffects.Kind kind = switch (instruction.getOpcode()) {
                    case Opcodes.ARETURN -> ContainerEffects.Kind.RETURN;
                    case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> ContainerEffects.Kind.FIELD_STORE;
                    case Opcodes.AASTORE -> ContainerEffects.Kind.ARRAY_STORE;
                    default -> null;
                };
                if (kind != null) {
                    effect(point, kind, -1, value(flow.top(index), point));
                } else {
                    SourceValue reference = unmodeledReference(index, instruction);
                    if (reference != null) {
                        effect(point, ContainerEffects.Kind.UNSUPPORTED_USE, -1, value(reference, point));
                    }
                }
            }
        }
        List<StorageId> allocations = flow.allocations.values().stream()
                .filter(storage -> flow.frames[storage.instruction()] != null)
                .sorted(Comparator.comparingInt(StorageId::instruction)).toList();
        return new MethodContainerSummary(flow.key, flow.method.access, true, declarations, allocations, returns, calls, effects);
    }

    private void analyzeCall(int index, MethodInsnNode call, ProgramPoint point) {
        ContainerSummary intrinsic = containers.resolve(call);
        MethodContainerSummary.Value receiver = null;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            receiver = value(flow.receiver(index, call), point);
            if (intrinsic == null) {
                effect(point, ContainerEffects.Kind.UNKNOWN_OPERATION, -1, receiver);
            }
        }
        List<MethodContainerSummary.Value> arguments = new ArrayList<>();
        Type[] types = Type.getArgumentTypes(call.desc);
        for (int parameter = 0; parameter < types.length; parameter++) {
            var argument = value(flow.argument(index, call, parameter), point);
            arguments.add(argument);
            if (isReference(types[parameter])) {
                effect(point, ContainerEffects.Kind.CALL_ARGUMENT, parameter, argument);
            }
        }
        calls.add(new MethodContainerSummary.Call(point, new MethodKey(call.owner, call.name, call.desc),
                call.getOpcode(), call.itf, receiver, arguments, intrinsic));
    }

    private void effect(ProgramPoint point, ContainerEffects.Kind kind, int parameter, MethodContainerSummary.Value value) {
        effects.add(new MethodContainerSummary.Effect(point, kind, parameter, value));
    }

    MethodContainerSummary.Value value(SourceValue source, ProgramPoint use) {
        Set<MethodContainerSummary.Origin> origins = new LinkedHashSet<>();
        boolean nullable = false;
        for (AbstractInsnNode origin : source.insns) {
            StorageId allocation = flow.allocations.get(origin);
            if (allocation != null) {
                origins.add(new MethodContainerSummary.Allocation(allocation));
            } else if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                nullable = true;
            } else {
                int index = flow.indices.get(origin);
                MethodContainerSummary.Origin parameter = parameters.get(-1 - index);
                origins.add(index < 0 && parameter != null ? parameter : new MethodContainerSummary.Producer(flow.point(index)));
            }
        }
        if (source.insns.isEmpty()) {
            origins.add(new MethodContainerSummary.Producer(use));
        }
        return new MethodContainerSummary.Value(origins, nullable);
    }

    private SourceValue unmodeledReference(int index, AbstractInsnNode instruction) {
        var frame = flow.frames[index];
        return switch (instruction.getOpcode()) {
            case Opcodes.GETFIELD, Opcodes.ARRAYLENGTH, Opcodes.ATHROW -> flow.top(index);
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                    Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> frame.getStack(frame.getStackSize() - 2);
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                    Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> frame.getStack(frame.getStackSize() - 3);
            default -> null;
        };
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }
}
