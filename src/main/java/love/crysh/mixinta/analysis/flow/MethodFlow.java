package love.crysh.mixinta.analysis.flow;

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

/**
 * 构造时计算稳定来源帧，参数来源使用独立的负指令索引。
 * 来源集合不可修改，ASM 节点属于输入快照，调用方必须只读使用。
 */
public final class MethodFlow {
    private final MethodKey key;
    private final String defaultSymbol;
    private long pointCreations;
    private final MethodNode method;
    private final AbstractInsnNode[] instructions;
    private final Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
    private final Map<AbstractInsnNode, Integer> indexSnapshot;
    private final Map<AbstractInsnNode, Set<AbstractInsnNode>> consumed = new IdentityHashMap<>();
    private final Map<AbstractInsnNode, Map<Integer, Set<AbstractInsnNode>>> consumedInputs = new IdentityHashMap<>();
    private final Frame<SourceValue>[] frames;
    private final AnalyzerException failure;
    private final Map<SourceValue, OriginValue> origins = new IdentityHashMap<>();
    private final List<AbstractInsnNode> instructionView;

    public MethodFlow(String owner, MethodNode method) {
        key = new MethodKey(owner, method.name, method.desc);
        defaultSymbol = key.toString();
        this.method = method;
        instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) indices.put(instructions[index], index);
        Frame<SourceValue>[] analyzed = null;
        AnalyzerException problem = null;
        try {
            analyzed = new Analyzer<>(new Origins()).analyze(key.owner(), method);
        } catch (AnalyzerException invalid) {
            problem = invalid;
        }
        frames = analyzed;
        failure = problem;
        instructionView = List.of(instructions);
        if (frames != null) {
            for (var frame : frames) {
                if (frame == null) continue;
                for (int slot = 0; slot < frame.getLocals(); slot++) origin(frame.getLocal(slot));
                for (int slot = 0; slot < frame.getStackSize(); slot++) origin(frame.getStack(slot));
            }
        }
        indexSnapshot = Map.copyOf(indices);
    }

    public AnalyzerException failure() { return failure; }

    public MethodKey key() { return key; }
    public String defaultSymbol() { return defaultSymbol; }
    public int originCount() { return origins.size(); }
    public long pointCreations() { return pointCreations; }
    public MethodNode method() { return method; }
    public List<AbstractInsnNode> instructions() { return instructionView; }
    public int instructionCount() { return instructions.length; }
    public AbstractInsnNode instruction(int index) { return instructions[index]; }
    public int indexOf(AbstractInsnNode instruction) { return indices.getOrDefault(instruction, -1); }
    public Map<AbstractInsnNode, Integer> indices() { return indexSnapshot; }
    public boolean reachable(int index) { return frames != null && frames[index] != null; }
    public int localCount(int index) { return frames[index].getLocals(); }
    public int stackSize(int index) { return frames[index].getStackSize(); }
    public OriginValue local(int index, int slot) { return origins.get(frames[index].getLocal(slot)); }
    public OriginValue stack(int index, int slot) { return origins.get(frames[index].getStack(slot)); }
    public Set<AbstractInsnNode> consumed(AbstractInsnNode instruction) {
        return Set.copyOf(consumed.getOrDefault(instruction, Set.of()));
    }

    private OriginValue origin(SourceValue value) {
        return origins.computeIfAbsent(value, source -> new OriginValue(source.getSize(), source.insns));
    }

    public ProgramPoint point(int index) {
        pointCreations++;
        String symbol = defaultSymbol;
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

    public OriginValue receiver(int index, MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        Frame<SourceValue> frame = frames[index];
        return origins.get(frame.getStack(frame.getStackSize() - arguments - 1));
    }

    public OriginValue argument(int index, MethodInsnNode call, int parameter) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        Frame<SourceValue> frame = frames[index];
        return origins.get(frame.getStack(frame.getStackSize() - arguments + parameter));
    }

    public OriginValue top(int index) {
        Frame<SourceValue> frame = frames[index];
        return origins.get(frame.getStack(frame.getStackSize() - 1));
    }

    public List<ConsumedInput> inputs(AbstractInsnNode instruction) {
        return consumedInputs.getOrDefault(instruction, Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ConsumedInput(entry.getKey(), Set.copyOf(entry.getValue())))
                .toList();
    }

    public record ConsumedInput(int index, Set<AbstractInsnNode> origins) {
        public ConsumedInput { origins = Set.copyOf(origins); }
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
