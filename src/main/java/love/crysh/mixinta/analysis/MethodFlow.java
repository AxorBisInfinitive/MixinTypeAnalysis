package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 同一份稳定来源帧供表示求解、局部别名和方法摘要使用，参数来源使用独立的负指令索引。 */
final class MethodFlow {
    final MethodKey key;
    final MethodNode method;
    final AbstractInsnNode[] instructions;
    final Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
    final Map<AbstractInsnNode, StorageId> allocations = new IdentityHashMap<>();
    final Map<AbstractInsnNode, Set<AbstractInsnNode>> consumed = new IdentityHashMap<>();
    final Map<AbstractInsnNode, Map<Integer, Set<AbstractInsnNode>>> consumedInputs = new IdentityHashMap<>();
    Frame<SourceValue>[] frames;
    ContainerAliases aliases;

    MethodFlow(String owner, MethodNode method) {
        this(owner, method, new ContainerRules(java.util.List.of()));
    }

    MethodFlow(String owner, MethodNode method, ContainerRules containers) {
        key = new MethodKey(owner, method.name, method.desc);
        this.method = method;
        instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode instruction = instructions[index];
            indices.put(instruction, index);
            if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && containers.supportsAllocation(allocation.desc)) {
                allocations.put(instruction, new StorageId(key, index, Type.getObjectType(allocation.desc).getDescriptor()));
            }
        }
    }

    void analyze() throws AnalyzerException {
        frames = new Analyzer<>(new Origins()).analyze(key.owner(), method);
    }

    ProgramPoint point(int index) {
        String symbol = key.toString();
        if (index >= 0 && index < instructions.length) {
            AbstractInsnNode instruction = instructions[index];
            if (instruction instanceof MethodInsnNode call) {
                symbol = call.owner + "." + call.name + call.desc;
            } else if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                symbol = type.desc;
            } else if (instruction instanceof FieldInsnNode field) {
                symbol = field.owner + "." + field.name + ":" + field.desc;
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                symbol = "invokedynamic " + dynamic.name + dynamic.desc;
            }
        }
        return new ProgramPoint(key, index, symbol);
    }

    SourceValue receiver(int index, MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        Frame<SourceValue> frame = frames[index];
        return frame.getStack(frame.getStackSize() - arguments - 1);
    }

    SourceValue argument(int index, MethodInsnNode call, int parameter) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        Frame<SourceValue> frame = frames[index];
        return frame.getStack(frame.getStackSize() - arguments + parameter);
    }

    SourceValue top(int index) {
        Frame<SourceValue> frame = frames[index];
        return frame.getStack(frame.getStackSize() - 1);
    }

    StorageReferences receiverReferences(int index, MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        return aliases.stack(index, frames[index].getStackSize() - arguments - 1);
    }

    List<ConsumedInput> inputs(AbstractInsnNode instruction) {
        return consumedInputs.getOrDefault(instruction, Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ConsumedInput(entry.getKey(), Set.copyOf(entry.getValue())))
                .toList();
    }

    record ConsumedInput(int index, Set<AbstractInsnNode> origins) {
    }

    private final class Origins extends SourceInterpreter {
        private final Map<Integer, LabelNode> parameters = new HashMap<>();

        Origins() {
            super(Opcodes.ASM9);
        }

        @Override
        public SourceValue newParameterValue(boolean instance, int local, Type type) {
            LabelNode parameter = parameters.computeIfAbsent(local, slot -> {
                LabelNode label = new LabelNode();
                indices.put(label, -1 - slot);
                return label;
            });
            return new SourceValue(type.getSize(), parameter);
        }

        private void consume(AbstractInsnNode instruction, SourceValue... values) {
            Set<AbstractInsnNode> origins = consumed.computeIfAbsent(instruction, ignored -> new HashSet<>());
            for (SourceValue value : values) {
                origins.addAll(value.insns);
            }
        }

        private void consumeInputs(AbstractInsnNode instruction, SourceValue... values) {
            consume(instruction, values);
            Map<Integer, Set<AbstractInsnNode>> inputs = consumedInputs.computeIfAbsent(instruction, ignored -> new LinkedHashMap<>());
            for (int index = 0; index < values.length; index++) {
                inputs.computeIfAbsent(index, ignored -> new LinkedHashSet<>()).addAll(values[index].insns);
            }
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            consumeInputs(instruction, value);
            return value;
        }

        @Override
        public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second) {
            consumeInputs(instruction, first, second);
            return super.binaryOperation(instruction, first, second);
        }

        @Override
        public SourceValue ternaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second, SourceValue third) {
            consumeInputs(instruction, first, second, third);
            return super.ternaryOperation(instruction, first, second, third);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, SourceValue value, SourceValue expected) {
            consume(instruction, value);
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            consumeInputs(instruction, value);
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
    }
}
