package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.model.ProgramPoint;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContainerAliasesTest {
    @Test
    void resolvesEachIdentityEagerlyAndPreservesEveryFramePosition() {
        var method = new MethodNode(Opcodes.ACC_STATIC, "use", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 3;
        method.maxStack = 1;
        var flow = new MethodFlow("sample/Subject", method);
        assertEquals(flow.local(0, 1), flow.local(0, 2));
        assertNotSame(flow.local(0, 1), flow.local(0, 2));
        var resolved = new IdentityHashMap<OriginValue, StorageReferences>();
        var calls = new AtomicInteger();
        var aliases = new ContainerAliases(flow, value -> {
            var facts = new StorageReferences(Set.of(), Set.of(new ProgramPoint(flow.key(), calls.incrementAndGet(), "origin")), false);
            assertNull(resolved.put(value, facts));
            return facts;
        });

        long positions = 0;
        for (int instruction = 0; instruction < flow.instructionCount(); instruction++) {
            for (int slot = 0; slot < flow.localCount(instruction); slot++) {
                assertSame(resolved.get(flow.local(instruction, slot)), aliases.local(instruction, slot));
                positions++;
            }
            for (int slot = 0; slot < flow.stackSize(instruction); slot++) {
                assertSame(resolved.get(flow.stack(instruction, slot)), aliases.stack(instruction, slot));
                positions++;
            }
        }
        assertNotSame(aliases.local(0, 1), aliases.local(0, 2));
        assertEquals(resolved.size(), calls.get());
        assertEquals(resolved.size(), aliases.valueCount());
        assertEquals(positions, aliases.positionCount());
        assertSame(aliases.local(0, 0), aliases.stack(1, 0));
    }

    @Test
    void followsSlotReuseAndBranchMerges() {
        var method = new MethodNode(Opcodes.ACC_STATIC, "use", "(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", null, null);
        var other = new LabelNode();
        var merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, other));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(other);
        method.instructions.add(merge);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 4;
        method.maxStack = 1;
        var flow = new MethodFlow("sample/Subject", method);
        var aliases = new ContainerAliases(flow, value -> new StorageReferences(Set.of(),
                value.origins().stream().map(origin -> flow.point(flow.indexOf(origin))).collect(java.util.stream.Collectors.toSet()), false));

        assertEquals(1, aliases.local(2, 3).unknownOrigins().size());
        assertEquals(2, aliases.local(9, 3).unknownOrigins().size());
        assertNotEquals(aliases.local(2, 3), aliases.local(6, 3));
        assertEquals(aliases.local(9, 3), aliases.stack(10, 0));
        assertNotSame(flow.local(9, 3), flow.stack(10, 0));
    }

    @Test
    void returnsNullForUnreachableAndInvalidQueries() {
        var method = new MethodNode(Opcodes.ACC_STATIC, "use", "(J)V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.maxLocals = 2;
        var aliases = new ContainerAliases(new MethodFlow("sample/Subject", method), value -> new StorageReferences(Set.of(), Set.of(), false));

        for (int instruction : new int[]{-1, 1, 2, Integer.MAX_VALUE}) {
            assertNull(aliases.local(instruction, 0));
            assertNull(aliases.stack(instruction, 0));
        }
        assertNotNull(aliases.local(0, 1));
        assertNull(aliases.local(0, -1));
        assertNull(aliases.local(0, 2));
        assertNull(aliases.stack(0, -1));
        assertNull(aliases.stack(0, 0));

        method.instructions.insert(new InsnNode(Opcodes.POP));
        var invalid = new MethodFlow("sample/Subject", method);
        assertNotNull(invalid.failure());
        var failed = new ContainerAliases(invalid, value -> fail("Invalid frames cannot be resolved"));
        assertNull(failed.local(0, 0));
        assertNull(failed.stack(0, 0));
        assertEquals(0, failed.valueCount());
    }
}
