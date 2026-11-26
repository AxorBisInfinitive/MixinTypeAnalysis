package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;
import java.util.stream.Stream;

import static love.crysh.mixinta.analysis.TypedContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedContainerViewsTest {
    record Program(String body, String members) {
        Program(String body) { this(body, ""); }
    }

    static Stream<Program> programs() {
        return Stream.of(
                new Program("""
                        java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        int sum = 0;
                        for (long value : q) sum += lookup(value);
                        return sum;
                        """),
                new Program("""
                        java.util.Deque<Long> q = new java.util.ArrayDeque<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        java.util.Iterator<Long> it = q.descendingIterator();
                        long result = lookup(it.next()); it.remove();
                        return result * 10 + lookup(q.peek());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        java.util.ListIterator<Long> it = q.listIterator(1);
                        long previous = it.previous();
                        it.set(position(7).asLong()); it.add(position(9).asLong());
                        if (it.nextIndex() != 1 || !it.hasNext() || !it.hasPrevious() || it.previousIndex() != 0) throw new AssertionError();
                        return lookup(previous) + lookup(it.next()) + lookup(q.get(0));
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.LinkedList<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        java.util.List<Long> view = q.subList(0, 2).subList(0, 1);
                        view.set(0, position(7).asLong());
                        int value = lookup(view.listIterator().next()); view.clear();
                        return value + lookup(q.getFirst());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        java.util.List<Long> view = q.reversed();
                        view.add(position(7).asLong());
                        return lookup(view.getFirst()) + lookup(q.getFirst());
                        """),
                new Program("""
                        java.util.Deque<Long> q = new java.util.ArrayDeque<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong());
                        java.util.Deque<Long> view = q.reversed();
                        view.addFirst(position(7).asLong());
                        return lookup(view.iterator().next()) + lookup(q.peekLast());
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.LinkedHashMap<>();
                        q.put(1L, position(12).asLong()); q.put(2L, position(3).asLong());
                        java.util.Collection<Long> values = q.values();
                        java.util.Iterator<Long> it = values.iterator();
                        long result = lookup(it.next()); it.remove();
                        if (values.size() != 1 || values.isEmpty()) throw new AssertionError();
                        result += lookup(q.get(2L)); values.clear();
                        return result + q.size();
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.LinkedHashMap<>();
                        q.put(1L, position(12).asLong()); q.put(2L, position(3).asLong());
                        java.util.Set<Long> keys = q.keySet();
                        long sum = 0;
                        for (long key : keys) sum += key;
                        int value = lookup(q.get(1L));
                        java.util.Iterator<Long> it = keys.iterator(); it.next(); it.remove();
                        return value + sum + q.size();
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.LinkedHashMap<>();
                        q.put(1L, position(12).asLong()); q.put(2L, position(3).asLong());
                        java.util.Set<java.util.Map.Entry<Long, Long>> entries = q.entrySet();
                        java.util.Iterator<java.util.Map.Entry<Long, Long>> it = entries.iterator();
                        java.util.Map.Entry<Long, Long> entry = it.next();
                        long old = entry.setValue(position(7).asLong());
                        long value = lookup(entry.getValue()) + lookup(old) + entry.getKey();
                        it.remove();
                        return value + lookup(q.get(2L));
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.HashMap<>();
                        q.put(1L, position(12).asLong());
                        long sum = 0;
                        for (java.util.Map.Entry<Long, Long> entry : q.entrySet()) sum += lookup(entry.getValue());
                        return sum;
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.LinkedHashMap<>();
                        q.put(1L, position(12).asLong()); q.put(2L, position(3).asLong()); q.put(3L, position(3).asLong());
                        java.util.Collection<Long> values = q.values();
                        if (!values.contains(3L) || !values.remove(3L) || !values.contains(3L) || q.size() != 2) throw new AssertionError();
                        if (!q.keySet().contains(1L) || !q.keySet().remove(3L)) throw new AssertionError();
                        return lookup(values.iterator().next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong()); q.add(position(3).asLong()); q.add(position(3).asLong());
                        java.util.List<Long> view = q.subList(1, 3).reversed();
                        if (!view.contains(3L) || !view.remove(3L) || view.size() != 1) throw new AssertionError();
                        return lookup(view.getFirst()) + lookup(q.getFirst());
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.LinkedHashMap<>();
                        q.put(1L, null); q.put(2L, position(12).asLong());
                        java.util.Collection<Long> values = q.values();
                        if (!values.contains(null) || !values.remove(null) || values.contains(null)) throw new AssertionError();
                        Long value = position(12).asLong();
                        if (!values.contains(value)) throw new AssertionError();
                        return lookup(values.iterator().next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong());
                        java.util.Iterator<Long> it = identity(view(q).iterator(), flag ? 3 : 4);
                        return read(it);
                        """, """
                        public static java.util.List<Long> view(java.util.List<Long> q) { return q.subList(0, q.size()); }
                        public static java.util.Iterator<Long> identity(java.util.Iterator<Long> it, int depth) {
                            return depth == 0 ? it : identity(it, depth - 1);
                        }
                        public static int read(java.util.Iterator<Long> it) { return lookup(it.next()); }
                        """),
                new Program("""
                        java.util.Map<Long, Long> q = new java.util.HashMap<>(); q.put(1L, position(12).asLong());
                        return read(next(q.entrySet().iterator()));
                        """, """
                        public static java.util.Map.Entry<Long, Long> next(java.util.Iterator<java.util.Map.Entry<Long, Long>> it) { return it.next(); }
                        public static int read(java.util.Map.Entry<Long, Long> entry) { return lookup(entry.getValue()); }
                        """),
                new Program("""
                        java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                        java.util.Queue<Long> second = new java.util.LinkedList<>();
                        first.add(position(12).asLong()); second.add(position(3).asLong());
                        java.util.Iterator<Long> it = flag ? first.iterator() : second.iterator();
                        return lookup(it.next());
                        """),
                new Program("""
                        java.util.Map<Long, Long> map = new java.util.HashMap<>(); map.put(1L, position(12).asLong());
                        java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(3).asLong());
                        java.util.Iterator<Long> it = flag ? map.values().iterator() : q.iterator();
                        return lookup(it.next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong());
                        java.util.Iterator<Long> it = q.iterator();
                        if (flag) it.remove();
                        return lookup(it.next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        q.add(position(12).asLong());
                        java.util.Iterator<Long> it = q.iterator();
                        if (flag) q.add(position(3).asLong());
                        return lookup(it.next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>();
                        if (flag) q.add(position(12).asLong());
                        return lookup(q.listIterator().previous());
                        """),
                new Program("""
                        java.util.List<Long> q = flag ? new java.util.ArrayList<>() : null;
                        if (flag) q.add(position(12).asLong());
                        return lookup(q.subList(0, 1).iterator().next());
                        """),
                new Program("""
                        java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong());
                        java.util.ListIterator<Long> it = q.listIterator();
                        if (flag) it.set(position(3).asLong());
                        return lookup(it.next());
                        """)
        );
    }

    @ParameterizedTest
    @MethodSource("programs")
    void executesSharedViewsAndIterators(Program program) throws Exception {
        var fixture = fixture(program.body(), program.members());
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertTrue(ClassFiles.read(output.get("sample/Subject")).methods.stream()
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("lookup")
                        && call.desc.equals("(Lsample/BlockPos;)I")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iterator", "view", "entry"})
    void rejectsDerivedReferenceEscape(String kind) {
        String value = switch (kind) {
            case "iterator" -> "q.values().iterator()";
            case "view" -> "q.values()";
            default -> "q.entrySet().iterator().next()";
        };
        var fixture = fixture("java.util.Map<Long, Long> q = new java.util.HashMap<>(); q.put(1L, position(12).asLong()); saved = "
                + value + "; return lookup(q.get(1L));", "public static Object saved;");
        var before = ClassFiles.snapshot(fixture.originals());
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("ESCAPED_CONTAINER")));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
    }

    @Test
    void rejectsUnresolvedIteratorOrigin() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); java.util.Iterator<Long> it = flag ? q.iterator() : unknown(); return lookup(it.next());",
                "public static native java.util.Iterator<Long> unknown();");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")));
    }

    @Test
    void rejectsUnresolvedViewObservationReceiver() {
        var fixture = fixture("""
                java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong());
                java.util.Collection<Long> view = flag ? q.subList(0, 1) : unknown();
                if (view.contains(12L)) return lookup(q.getFirst());
                return 0;
                """, "public static native java.util.Collection<Long> unknown();");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")));
    }

    @Test
    void rejectsMergedKeyAndValueIterators() {
        var fixture = fixture("""
                java.util.Map<Long, Long> q = new java.util.HashMap<>();
                q.put(position(12).asLong(), position(3).asLong());
                java.util.Iterator<Long> it = flag ? q.keySet().iterator() : q.values().iterator();
                return lookup(it.next());
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MAP_KEY_MIGRATION_NOT_IMPLEMENTED")));
    }

    @Test
    void retainsAllWritesThroughIteratorAndSubList() {
        var fixture = fixture("""
                java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong());
                q.subList(0, 1).listIterator().add(7L);
                return lookup(q.getLast());
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MISSING_PRODUCER_RULE")));
        assertTrue(failure.diagnostics().stream().flatMap(diagnostic -> diagnostic.propagation().stream())
                .anyMatch(edge -> edge.kind() == DependencyKind.CONTAINER_VIEW));
    }

    @Test
    void sharedIteratorBodyRejectsEntryAndElementKinds() {
        var fixture = fixture("""
                java.util.Map<Long, Long> q = new java.util.HashMap<>(); q.put(1L, position(12).asLong());
                java.util.List<Long> list = new java.util.ArrayList<>(); list.add(position(3).asLong());
                consume(q.entrySet().iterator()); consume(list.iterator());
                return lookup(q.get(1L)) + lookup(list.getFirst());
                """, "public static void consume(java.util.Iterator<?> it) { it.next(); }");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")));
    }

    @Test
    void keepsFailFastAndNullReadExceptions() throws Exception {
        var fixture = fixture("""
                java.util.List<Long> q = new java.util.ArrayList<>();
                q.add(flag ? position(12).asLong() : null);
                java.util.List<Long> view = q.subList(0, 1);
                if (flag) q.add(position(3).asLong());
                return lookup(view.iterator().next());
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void createsRepeatablePlansAndReportsForViews() {
        var fixture = fixture("""
                java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong());
                java.util.List<Long> view = q.subList(0, 1);
                if (!view.contains(12L)) throw new AssertionError();
                return lookup(view.iterator().next());
                """);
        var prepared = fixture.prepare();
        var plan = prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared));
        var first = plan.apply();
        var second = plan.apply();
        first.forEach((name, bytes) -> assertArrayEquals(bytes, second.get(name)));
        var failureFixture = fixture("""
                java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong());
                saved = q.subList(0, 1).iterator(); return lookup(q.getFirst());
                """, "public static Object saved;");
        var failure = assertThrows(TypedOverwriteException.class, failureFixture::transform);
        assertEquals(failure.format(DiagnosticVerbosity.VERBOSE),
                assertThrows(TypedOverwriteException.class, failureFixture::transform).format(DiagnosticVerbosity.VERBOSE));
    }

    @Test
    void rejectsPhysicalMapKeyMigration() {
        var fixture = fixture("java.util.Map<Long, Long> q = new java.util.HashMap<>(); q.put(position(12).asLong(), 7L); return lookup(q.keySet().iterator().next());");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MAP_KEY_MIGRATION_NOT_IMPLEMENTED")));
    }

    @Test
    void rewritesOnlyValueArgumentsInEntryAndIteratorDebugSignatures() {
        var fixture = fixture("""
                java.util.Map<Long, Long> q = new java.util.HashMap<>(); q.put(1L, position(12).asLong());
                java.util.Set<Long> keys = q.keySet();
                java.util.Iterator<Long> keyIterator = keys.iterator();
                java.util.Collection<Long> values = q.values();
                java.util.Set<java.util.Map.Entry<Long, Long>> entries = q.entrySet();
                java.util.Iterator<java.util.Map.Entry<Long, Long>> it = entries.iterator();
                java.util.Map.Entry<Long, Long> entry = it.next();
                return lookup(entry.getValue()) + keyIterator.next();
                """);
        var output = fixture.transform();
        var variables = run(output).localVariables.stream().collect(java.util.stream.Collectors.toMap(variable -> variable.name, variable -> variable));
        assertEquals("Ljava/util/Set<Ljava/lang/Long;>;", variables.get("keys").signature);
        assertEquals("Ljava/util/Iterator<Ljava/lang/Long;>;", variables.get("keyIterator").signature);
        assertEquals("Ljava/util/Collection<Lsample/BlockPos;>;", variables.get("values").signature);
        assertEquals("Ljava/util/Set<Ljava/util/Map$Entry<Ljava/lang/Long;Lsample/BlockPos;>;>;", variables.get("entries").signature);
        assertEquals("Ljava/util/Iterator<Ljava/util/Map$Entry<Ljava/lang/Long;Lsample/BlockPos;>;>;", variables.get("it").signature);
        assertEquals("Ljava/util/Map$Entry<Ljava/lang/Long;Lsample/BlockPos;>;", variables.get("entry").signature);
    }

    @ParameterizedTest
    @ValueSource(strings = {"q.iterator().forEachRemaining(value -> {});", "q.subList(0, 1).toArray();", "q.iterator().toString();"})
    void rejectsUnmodeledViewOperations(String operation) {
        var fixture = fixture("java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong()); " + operation + " return lookup(q.getFirst());");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")));
    }
}
