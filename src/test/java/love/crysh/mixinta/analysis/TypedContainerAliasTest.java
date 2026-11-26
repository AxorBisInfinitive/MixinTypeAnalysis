package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static love.crysh.mixinta.analysis.TypedContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedContainerAliasTest {
    static Stream<String> aliasPrograms() {
        return Stream.of(
                """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                Object object = queue;
                java.util.Collection<Long> collection = (java.util.Collection<Long>) object;
                collection.add(position(12).asLong());
                java.util.Queue<Long> alias = (java.util.Queue<Long>) collection;
                if (alias != queue || !(object instanceof java.util.Queue)) throw new AssertionError();
                return lookup(alias.poll());
                """,
                """
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.LinkedList<>();
                (flag ? first : second).add(position(12).asLong());
                return lookup((flag ? first : second).poll());
                """,
                """
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.LinkedList<>();
                first.add(position(12).asLong());
                second.add(position(3).asLong());
                java.util.Queue<Long> alias = first;
                int result = 0;
                for (int index = 0; index < (flag ? 3 : 4); index++) {
                    result += lookup(alias.peek());
                    alias = alias == first ? second : first;
                }
                return result;
                """,
                """
                java.util.Queue<Long> queue = null;
                for (int index = 0; index < (flag ? 1 : 2); index++) {
                    queue = new java.util.ArrayDeque<>();
                    queue.add(position(12).asLong());
                }
                return lookup(queue.poll());
                """,
                """
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.LinkedList<>();
                first.add(position(12).asLong());
                second.add(position(3).asLong());
                java.util.Queue<Long> alias = first;
                try {
                    if (flag) throw new IllegalStateException();
                    alias = second;
                } catch (IllegalStateException expected) {
                    alias = first;
                }
                return lookup(alias.poll());
                """,
                """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                java.util.Queue<Long> alias = flag ? queue : null;
                if (alias == null) return 0;
                alias.add(position(12).asLong());
                return lookup(queue.poll());
                """,
                """
                java.util.Map<Long, Long> first = new java.util.HashMap<>();
                java.util.Map<Long, Long> second = new java.util.LinkedHashMap<>();
                java.util.Map<Long, Long> alias = flag ? first : second;
                Object object = alias;
                alias.put(7L, position(12).asLong());
                return lookup(((java.util.Map<Long, Long>) object).get(7L));
                """
        );
    }

    @ParameterizedTest
    @MethodSource("aliasPrograms")
    void propagatesAcrossCastsBranchesLoopsAndExceptionHandlers(String body) throws Exception {
        var fixture = fixture(body);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertTrue(Arrays.stream(run(output).instructions.toArray()).noneMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Long")
                        && call.name.equals("longValue")));
    }

    @Test
    void keepsReusedLocalSlotLifetimesIndependent() throws Exception {
        var fixture = fixture("""
                int result;
                {
                    java.util.Queue<Long> migratedQueue = new java.util.ArrayDeque<>();
                    migratedQueue.add(position(12).asLong());
                    result = lookup(migratedQueue.poll());
                }
                {
                    java.util.Queue<Long> originalQueue = new java.util.ArrayDeque<>();
                    originalQueue.add(7L);
                    return result + originalQueue.poll();
                }
                """);
        var originalLocals = run(fixture.originals()).localVariables;
        int first = originalLocals.stream().filter(local -> local.name.equals("migratedQueue")).findFirst().orElseThrow().index;
        int second = originalLocals.stream().filter(local -> local.name.equals("originalQueue")).findFirst().orElseThrow().index;
        assertEquals(first, second, "Fixture must reuse the local slot");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        var locals = run(output).localVariables;
        assertEquals("Ljava/util/Queue<Lsample/BlockPos;>;",
                locals.stream().filter(local -> local.name.equals("migratedQueue")).findFirst().orElseThrow().signature);
        assertEquals("Ljava/util/Queue<Ljava/lang/Long;>;",
                locals.stream().filter(local -> local.name.equals("originalQueue")).findFirst().orElseThrow().signature);
    }

    static Stream<String> unknownPrograms() {
        return Stream.of(
                """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                Object alias = queue;
                for (int index = 0; index < (flag ? 1 : 2); index++) alias = flag ? alias : external();
                return lookup(((java.util.Queue<Long>) alias).poll());
                """,
                """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                java.util.Queue<Long> alias;
                try {
                    if (flag) throw new IllegalStateException();
                    alias = queue;
                } catch (IllegalStateException expected) {
                    alias = external();
                }
                return lookup(alias.poll());
                """,
                """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                java.util.Queue<Long> alias = flag ? queue : null;
                if (alias == null) alias = external();
                return lookup(alias.poll());
                """
        );
    }

    @ParameterizedTest
    @MethodSource("unknownPrograms")
    void retainsUnknownOriginsAfterRepeatedAliasMerges(String body) {
        var fixture = fixture(body);
        Map<String, byte[]> before = ClassFiles.snapshot(fixture.originals());
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")
                && diagnostic.failureSymbol().contains("external")));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
    }

    @Test
    void retainsUnknownContainerParameterAlongsideKnownAllocation() {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                return lookup(queue.poll());
                }
                public static int other(java.util.Queue<Long> input, boolean flag) {
                    java.util.Queue<Long> local = new java.util.ArrayDeque<>();
                    local.add(position(3).asLong());
                    Object alias = flag ? local : input;
                    return lookup(((java.util.Queue<Long>) alias).poll());
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")
                && diagnostic.method().name().equals("other") && diagnostic.instruction() == -1));
    }

    @Test
    void retainsFieldAndArrayReadBoundaries() {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                return lookup(queue.poll());
                }
                public static java.util.Queue<Long> field;
                public static int other(java.util.Queue<Long>[] array, boolean flag) {
                    java.util.Queue<Long> local = new java.util.ArrayDeque<>();
                    local.add(position(3).asLong());
                    java.util.Queue<Long> alias = flag ? local : field;
                    int result = lookup(alias.poll());
                    alias = flag ? local : array[0];
                    return result + lookup(alias.poll());
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        var unresolved = failure.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")).toList();
        assertEquals(2, unresolved.size());
        assertTrue(unresolved.stream().anyMatch(diagnostic -> diagnostic.failureSymbol().contains(".field:")));
    }

    record Copies(int[] before, int[] after) {
    }

    static Stream<Copies> stackCopies() {
        return Stream.of(
                new Copies(new int[0], new int[]{Opcodes.DUP, Opcodes.POP}),
                new Copies(new int[]{Opcodes.ICONST_0}, new int[]{Opcodes.DUP_X1, Opcodes.POP, Opcodes.POP}),
                new Copies(new int[]{Opcodes.LCONST_0}, new int[]{Opcodes.DUP_X2, Opcodes.POP, Opcodes.POP2}),
                new Copies(new int[0], new int[]{Opcodes.ICONST_0, Opcodes.DUP2, Opcodes.POP2, Opcodes.POP}),
                new Copies(new int[]{Opcodes.ICONST_0}, new int[]{Opcodes.ICONST_1, Opcodes.DUP2_X1, Opcodes.POP2, Opcodes.POP, Opcodes.POP}),
                new Copies(new int[]{Opcodes.LCONST_0}, new int[]{Opcodes.ICONST_0, Opcodes.DUP2_X2, Opcodes.POP2, Opcodes.POP2, Opcodes.POP}),
                new Copies(new int[0], new int[]{Opcodes.ICONST_0, Opcodes.SWAP, Opcodes.SWAP, Opcodes.POP})
        );
    }

    @ParameterizedTest
    @MethodSource("stackCopies")
    void preservesAliasesAcrossOperandStackCopies(Copies copies) throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                return lookup(queue.poll());
                """);
        var subject = ClassFiles.read(fixture.originals().get("sample/Subject"));
        var method = ClassFiles.findMethod(subject, "run", "(Z)J");
        var load = Arrays.stream(method.instructions.toArray()).filter(instruction ->
                instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD).findFirst().orElseThrow();
        method.instructions.insertBefore(load, instructions(copies.before()));
        method.instructions.insert(load, instructions(copies.after()));
        method.maxStack += 6;
        fixture.originals().put(subject.name, ClassFiles.write(subject));
        BytecodeFixtures.verify(fixture.originals());
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    private static InsnList instructions(int[] opcodes) {
        InsnList result = new InsnList();
        for (int opcode : opcodes) {
            result.add(new InsnNode(opcode));
        }
        return result;
    }
}
