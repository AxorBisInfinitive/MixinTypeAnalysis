package love.crysh.mixinta.bytecode;

import love.crysh.mixinta.model.MethodKey;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 收集直接调用及引导常量中的方法引用，指令位置保留 ASM 列表索引。 */
public final class MethodReferences {
    private MethodReferences() { }

    public static void visit(MethodNode method, BiConsumer<Integer, MethodKey> consumer) {
        var instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            var instruction = instructions[index];
            int location = index;
            Consumer<Handle> handle = value -> consumer.accept(location,
                    new MethodKey(value.getOwner(), value.getName(), value.getDesc()));
            if (instruction instanceof MethodInsnNode call) {
                consumer.accept(index, new MethodKey(call.owner, call.name, call.desc));
            } else if (instruction instanceof LdcInsnNode constant) {
                visitConstant(constant.cst, handle);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                visitConstant(dynamic.bsm, handle);
                for (Object argument : dynamic.bsmArgs) visitConstant(argument, handle);
            }
        }
    }

    public static void visitConstant(Object constant, Consumer<Handle> consumer) {
        if (constant instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
            consumer.accept(handle);
        } else if (constant instanceof ConstantDynamic dynamic) {
            visitConstant(dynamic.getBootstrapMethod(), consumer);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                visitConstant(dynamic.getBootstrapMethodArgument(index), consumer);
            }
        }
    }
}
