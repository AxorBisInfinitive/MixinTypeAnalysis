package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TypedContainerTest {
    static Stream<String> sequentialOperations() {
        return Stream.of(
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(4); q.offer(position(12).asLong()); return lookup(q.poll());",
                "java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return lookup(q.peek()) + lookup(q.remove());",
                "java.util.Deque<Long> q = new java.util.LinkedList<>(); q.push(position(12).asLong()); return lookup(q.element()) + lookup(q.pop());",
                "java.util.Deque<Long> q = new java.util.ArrayDeque<>(); q.offerFirst(position(12).asLong()); q.offerLast(position(3).asLong()); return lookup(q.peekFirst()) + lookup(q.peekLast()) + lookup(q.pollFirst()) + lookup(q.pollLast());",
                "java.util.LinkedList<Long> q = new java.util.LinkedList<>(); q.addFirst(position(12).asLong()); q.addLast(position(3).asLong()); return lookup(q.getFirst()) + lookup(q.getLast()) + lookup(q.removeFirst()) + lookup(q.removeLast());",
                "java.util.List<Long> q = new java.util.ArrayList<>(4); q.add(position(12).asLong()); q.add(0, position(3).asLong()); return lookup(q.get(0)) + lookup(q.remove(1));",
                "java.util.ArrayList<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong()); return lookup(q.get(0)) + lookup(q.remove(0));",
                "java.util.List<Long> q = new java.util.LinkedList<>(); q.add(position(12).asLong()); long previous = q.set(0, position(3).asLong()); return lookup(previous) + lookup(q.get(0));",
                "java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong()); q.set(0, position(3).asLong()); return lookup(q.get(0));",
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); int size = q.size(); boolean empty = q.isEmpty(); return lookup(q.poll()) + size + (empty ? 1 : 0);",
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); q.clear(); if (flag) q.add(position(3).asLong()); return lookup(q.poll());",
                "java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong()); q.clear(); if (flag) q.add(position(3).asLong()); return lookup(q.get(0));",
                "java.util.Queue<Long> q = new java.util.LinkedList<>(); q.add(flag ? position(12).asLong() : null); return lookup(q.poll());",
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(flag ? position(12).asLong() : null); return lookup(q.poll());",
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(); if (flag) q.add(position(12).asLong()); return lookup(q.remove());",
                "java.util.Deque<Long> q = new java.util.ArrayDeque<>(); if (flag) q.push(position(12).asLong()); return lookup(q.pop());",
                "java.util.Queue<Long> q = new java.util.ArrayDeque<>(); if (flag) q.add(position(12).asLong()); return lookup(q.element());"
        );
    }

    @ParameterizedTest
    @MethodSource("sequentialOperations")
    void preservesSequentialOperationResultsExceptionsAndSourceEvaluations(String body) throws Exception {
        var fixture = fixture(body);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        MethodNode run = run(output);
        assertEquals(0, longCalls(run, "valueOf"));
        assertEquals(0, longCalls(run, "longValue"));
        assertTrue(Arrays.stream(run.instructions.toArray()).anyMatch(instruction -> instruction instanceof TypeInsnNode cast
                && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals("sample/BlockPos")));
        assertNotNull(ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")),
                "lookup", "(Lsample/BlockPos;)I"));
    }

    @Test
    void keepsIndependentStorageAndMigratesMergedReceiverAliases() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.LinkedList<>();
                java.util.Queue<Long> unrelated = new java.util.ArrayDeque<>();
                unrelated.add(7L);
                java.util.Queue<Long> alias = flag ? first : second;
                alias.add(position(12).asLong());
                return lookup(alias.poll()) + unrelated.poll();
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertEquals(1, longCalls(run(output), "valueOf"));
        assertEquals(1, longCalls(run(output), "longValue"));
    }

    @Test
    void expandsSharedReadDemandAcrossAllPossibleReceivers() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.ArrayDeque<>();
                first.add(position(12).asLong());
                second.add(position(3).asLong());
                int value = lookup(first.peek());
                java.util.Queue<Long> alias = flag ? first : second;
                return value + legacy(alias.poll());
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertEquals(0, longCalls(run(output), "valueOf"));
    }

    @Test
    void containerNullReceiverKeepsItsException() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = flag ? new java.util.ArrayDeque<>() : null;
                queue.add(position(12).asLong());
                return lookup(queue.poll());
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void rejectsUnknownElementObservationWithoutChangingInput() {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                boolean contains = queue.contains(12L);
                return lookup(queue.poll()) + (contains ? 1 : 0);
                """);
        Map<String, byte[]> before = ClassFiles.snapshot(fixture.originals());
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("UNKNOWN_CONTAINER_USE")));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
    }

    @Test
    void retainsUnknownOriginsInMergedContainerReceivers() {
        var fixture = fixture("""
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                first.add(position(12).asLong());
                java.util.Queue<Long> alias = flag ? first : external();
                return lookup(alias.poll());
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("UNRESOLVED_CONTAINER")));
    }

    static BytecodeFixtures.Fixture fixture(String body) {
        return fixture(body, "");
    }

    static BytecodeFixtures.Fixture fixture(String body, String members) {
        return BytecodeFixtures.fixture("""
                public static int sources;
                public static BlockPos position(long value) { sources++; return new BlockPos(value); }
                public static java.util.Queue<Long> external() { return new java.util.ArrayDeque<>(); }
                public static int lookup(long value) { return (int)value; }
                public static long legacy(long value) { return value; }
                public static long run(boolean flag) {
                """ + body + "}" + members, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                public static int lookup(BlockPos value) { return (int)value.value(); }
                @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                public static BlockPos keep(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                @love.crysh.mixinta.TypeRule(love.crysh.mixinta.TypeRule.Kind.READ)
                public static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                @love.crysh.mixinta.TypeRule(love.crysh.mixinta.TypeRule.Kind.ENCODE)
                public static long encode(BlockPos value) { return value.value(); }
                """, Map.of("sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }"));
    }

    static MethodNode run(Map<String, byte[]> classes) {
        return ClassFiles.findMethod(ClassFiles.read(classes.get("sample/Subject")), "run", "(Z)J");
    }

    private static long longCalls(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("java/lang/Long") && call.name.equals(name)).count();
    }

    static void assertEquivalent(Map<String, byte[]> original, Map<String, byte[]> output) throws Exception {
        for (boolean flag : new boolean[]{true, false}) {
            assertEquals(execute(original, flag), execute(output, flag));
        }
    }

    private record Outcome(Object value, String exception, int sources) {
    }

    private static Outcome execute(Map<String, byte[]> classes, boolean flag) throws Exception {
        Class<?> subject = BytecodeFixtures.loader(classes).loadClass("sample.Subject");
        try {
            Object value = subject.getMethod("run", boolean.class).invoke(null, flag);
            return new Outcome(value, "", subject.getField("sources").getInt(null));
        } catch (InvocationTargetException failure) {
            return new Outcome(null, failure.getCause().getClass().getName(), subject.getField("sources").getInt(null));
        }
    }
}
