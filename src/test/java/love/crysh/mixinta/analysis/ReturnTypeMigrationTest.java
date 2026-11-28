package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Return-type migration: a typed overwrite may widen a primitive return type. */
class ReturnTypeMigrationTest {

    @Test
    void widensPrimitiveReturnAndNarrowsAtUntouchedCallSites() throws Exception {
        var fixture = BytecodeFixtures.fixture("""
                public final int getX() { return 5; }
                public int run() { return getX() + 1; }
                """, """
                @love.crysh.mixinta.anno.TypedOverwrite(method = "getX()I")
                public long getX() { return 7L; }
                """);
        var prepared = fixture.prepare();
        var input = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var output = prepared.analyze(input).apply();
        BytecodeFixtures.verify(output);

        var subject = BytecodeFixtures.loader(output).loadClass("sample.Subject");
        var instance = subject.getConstructor().newInstance();
        assertEquals(7L, subject.getMethod("getX").invoke(instance));
        assertEquals(8, subject.getMethod("run").invoke(instance));

        var node = ClassFiles.read(output.get("sample/Subject"));
        assertTrue(node.methods.stream().anyMatch(method -> method.name.equals("getX") && method.desc.equals("()J")));
        assertFalse(node.methods.stream().anyMatch(method -> method.name.equals("getX") && method.desc.equals("()I")));

        var run = ClassFiles.findMethod(node, "run", "()I");
        var calls = Arrays.stream(run.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("getX"))
                .map(instruction -> (MethodInsnNode) instruction)
                .toList();
        assertEquals(1, calls.size());
        assertEquals("()J", calls.getFirst().desc);
        assertTrue(Arrays.stream(run.instructions.toArray())
                .anyMatch(instruction -> instruction.getOpcode() == Opcodes.L2I));
    }
}
