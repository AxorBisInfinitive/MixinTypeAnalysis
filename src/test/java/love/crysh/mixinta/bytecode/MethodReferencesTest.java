package love.crysh.mixinta.bytecode;

import love.crysh.mixinta.model.MethodKey;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodReferencesTest {
    @Test
    void visitsCallsHandlesAndNestedBootstrapArgumentsWithTheirInstructionIndices() {
        MethodNode method = new MethodNode();
        Handle target = new Handle(Opcodes.H_INVOKESTATIC, "sample/Subject", "div", "(II)I", false);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "sample/Bootstrap", "value", "()V", false);
        var inner = new ConstantDynamic("inner", "Ljava/lang/Object;", bootstrap, target);
        var outer = new ConstantDynamic("outer", "Ljava/lang/Object;", bootstrap, inner);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Subject", "div", "(II)I", false));
        method.instructions.add(new LdcInsnNode(target));
        method.instructions.add(new InvokeDynamicInsnNode("run", "()V", bootstrap, target));
        method.instructions.add(new LdcInsnNode(outer));
        List<Integer> indices = new ArrayList<>();
        MethodReferences.visit(method, (index, reference) -> {
            if (reference.equals(new MethodKey("sample/Subject", "div", "(II)I"))) indices.add(index);
        });
        assertEquals(List.of(0, 1, 2, 3), indices);
    }
}
