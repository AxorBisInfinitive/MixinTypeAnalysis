package love.crysh.mixinta.analysis.flow;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

class MethodFlowTest {
    @Test
    void exposesStableReadOnlyOriginsAndWideParameterSlots() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "run", "(JLjava/lang/Object;)Ljava/lang/Object;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        var call = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/Receiver", "accept", "(JLjava/lang/Object;)V", false);
        method.instructions.add(call);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 3;
        method.maxStack = 4;
        MethodFlow flow = new MethodFlow("sample/Subject", method);

        assertNull(flow.failure());
        assertEquals(8, flow.instructionCount());
        assertTrue(flow.reachable(3));
        assertFalse(flow.reachable(6));
        assertSame(call, flow.instruction(3));
        assertEquals(3, flow.indexOf(call));
        assertEquals(2, flow.argument(3, call, 0).width());
        assertEquals(-1, flow.indexOf(flow.argument(3, call, 0).origins().iterator().next()));
        assertEquals(-3, flow.indexOf(flow.receiver(3, call).origins().iterator().next()));
        assertSame(flow.receiver(3, call), flow.argument(3, call, 1));
        assertSame(flow.local(3, 2), flow.stack(3, 0));
        assertEquals(flow.top(5).origins(), flow.consumed(flow.instruction(5)));
        assertThrows(UnsupportedOperationException.class, () -> flow.instructions().clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.indices().clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.top(5).origins().clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.consumed(flow.instruction(5)).clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.inputs(flow.instruction(4)).getFirst().origins().clear());
    }

    @Test
    void originValueCopiesTheSourceSet() {
        var source = new InsnNode(Opcodes.ACONST_NULL);
        var sources = new LinkedHashSet<org.objectweb.asm.tree.AbstractInsnNode>();
        sources.add(source);
        var value = new OriginValue(1, sources);
        sources.clear();

        assertEquals(java.util.Set.of(source), value.origins());
        assertThrows(IllegalArgumentException.class, () -> new OriginValue(0, sources));
    }
}
