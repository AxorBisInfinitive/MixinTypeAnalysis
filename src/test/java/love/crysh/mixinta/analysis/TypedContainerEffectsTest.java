package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static love.crysh.mixinta.analysis.TypedContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedContainerEffectsTest {
    record Exposure(String operation, String members, String code, String kind, String escape, int parameter) {
    }

    static Stream<Exposure> exposures() {
        return Stream.of(
                new Exposure("saved = queue;", "public static Object saved;",
                        "ESCAPED_CONTAINER", "FIELD_STORE", "ESCAPED", -1),
                new Exposure("new Holder().value = queue;", "public static class Holder { public Object value; }",
                        "ESCAPED_CONTAINER", "FIELD_STORE", "ESCAPED", -1),
                new Exposure("Object[] array = new Object[1]; array[0] = queue;", "",
                        "ESCAPED_CONTAINER", "ARRAY_STORE", "ESCAPED", -1),
                new Exposure("Runnable task = () -> queue.clear();", "",
                        "ESCAPED_CONTAINER", "DYNAMIC_CAPTURE", "ESCAPED", 0),
                new Exposure("opaque(queue);", "public static native void opaque(Object queue);",
                        "UNKNOWN_CONTAINER_USE", "CALL_ARGUMENT", "UNKNOWN", 0),
                new Exposure("new java.util.ArrayList<>(queue);", "",
                        "UNKNOWN_CONTAINER_USE", "CALL_ARGUMENT", "UNKNOWN", 0),
                new Exposure("java.util.Objects.toString(queue);", "",
                        "UNKNOWN_CONTAINER_USE", "CALL_ARGUMENT", "UNKNOWN", 0),
                new Exposure("queue.toString();", "",
                        "UNKNOWN_CONTAINER_USE", "UNKNOWN_OPERATION", "UNKNOWN", -1),
                new Exposure("queue.forEach(value -> {});", "",
                        "UNKNOWN_CONTAINER_USE", "UNKNOWN_OPERATION", "UNKNOWN", -1)
        );
    }

    @ParameterizedTest
    @MethodSource("exposures")
    void rejectsUncoveredEffectsWithStorageChannelAndDependency(Exposure exposure) {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                """ + exposure.operation() + "return lookup(queue.poll());", exposure.members());
        var before = ClassFiles.snapshot(fixture.originals());
        var mixins = ClassFiles.snapshot(fixture.mixins());
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size(), failure.getMessage());
        Diagnostic diagnostic = failure.diagnostics().getFirst();
        assertEquals(exposure.code(), diagnostic.code());
        assertEquals(exposure.parameter(), diagnostic.parameter());
        assertTrue(diagnostic.message().contains(exposure.kind()));
        assertTrue(diagnostic.message().contains("escape=" + exposure.escape()));
        assertTrue(diagnostic.message().contains("channels=[0]"));
        assertTrue(diagnostic.message().contains("allocation=java/util/ArrayDeque"));
        assertTrue(diagnostic.dependencyPath().stream().anyMatch(step -> step.symbol().contains("lookup")));
        assertTrue(diagnostic.dependencyPath().stream().anyMatch(step -> step.symbol().contains("poll")));
        DependencyKind edge = exposure.escape().equals("ESCAPED")
                ? DependencyKind.CONTAINER_ESCAPE : DependencyKind.CONTAINER_EXTERNAL_ACCESS;
        assertTrue(diagnostic.propagation().stream().anyMatch(value -> value.kind() == edge));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
        mixins.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.mixins().get(name)));
        assertEquals(failure.format(DiagnosticVerbosity.VERBOSE),
                assertThrows(TypedOverwriteException.class, fixture::transform).format(DiagnosticVerbosity.VERBOSE));
    }

    @Test
    void rejectsReturnedContainerWithSelectedRepresentation() {
        var fixture = fixture("return 0;", """
                public static java.util.Queue<Long> leaked() {
                    java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                    queue.add(position(12).asLong());
                    lookup(queue.peek());
                    return queue;
                }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size());
        var diagnostic = failure.diagnostics().getFirst();
        assertEquals("ESCAPED_CONTAINER", diagnostic.code());
        assertEquals("leaked", diagnostic.method().name());
        assertTrue(diagnostic.message().contains("RETURN"));
        assertTrue(diagnostic.message().contains("escape=ESCAPED"));
        assertTrue(diagnostic.dependencyPath().stream().anyMatch(step -> step.symbol().contains("lookup")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "after", "clear", "branch", "exception"})
    void retainsExposureForTheWholeAllocation(String placement) {
        String operation = switch (placement) {
            case "before" -> "expose(queue); queue.add(position(12).asLong()); return lookup(queue.poll());";
            case "after" -> "queue.add(position(12).asLong()); int result = lookup(queue.peek()); expose(queue); return result;";
            case "clear" -> "expose(queue); queue.clear(); queue.add(position(12).asLong()); return lookup(queue.poll());";
            case "branch" -> "queue.add(position(12).asLong()); if (flag) expose(queue); return lookup(queue.poll());";
            default -> "queue.add(position(12).asLong()); try { if (flag) throw new IllegalStateException(); } catch (IllegalStateException expected) { expose(queue); } return lookup(queue.poll());";
        };
        var fixture = fixture("java.util.Queue<Long> queue = new java.util.ArrayDeque<>();" + operation,
                "public static native void expose(Object queue);");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size());
        assertEquals("UNKNOWN_CONTAINER_USE", failure.diagnostics().getFirst().code());
        assertTrue(failure.diagnostics().getFirst().message().contains("CALL_ARGUMENT"));
    }

    @Test
    void reportsEveryCallArgumentAndAggregatesEscapeState() {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                expose(queue, queue);
                saved = queue;
                return lookup(queue.poll());
                """, "public static Object saved; public static native void expose(Object first, Object second);");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(3, failure.diagnostics().size());
        var arguments = failure.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")).toList();
        assertEquals(2, arguments.size());
        assertEquals(1, arguments.stream().map(Diagnostic::instruction).distinct().count());
        assertArrayEquals(new int[]{0, 1}, arguments.stream().mapToInt(Diagnostic::parameter).sorted().toArray());
        assertTrue(arguments.stream().allMatch(diagnostic -> diagnostic.message().contains("escape=ESCAPED")));
    }

    @Test
    void associatesMergedReceiverWithEveryAffectedStorage() {
        var fixture = fixture("""
                java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                java.util.Queue<Long> second = new java.util.LinkedList<>();
                first.add(position(12).asLong());
                second.add(position(3).asLong());
                Object alias = flag ? first : second;
                expose(alias);
                return lookup(first.poll()) + lookup(second.poll());
                """, "public static native void expose(Object queue);");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size());
        var diagnostic = failure.diagnostics().getFirst();
        assertTrue(diagnostic.message().contains("allocation=java/util/ArrayDeque"));
        assertTrue(diagnostic.message().contains("allocation=java/util/LinkedList"));
        assertEquals(2, diagnostic.propagation().stream()
                .filter(edge -> edge.kind() == DependencyKind.CONTAINER_EXTERNAL_ACCESS).count());
    }

    @Test
    void reportsMapValueChannelOnExternalAccess() {
        var fixture = fixture("""
                java.util.Map<Long, Long> map = new java.util.HashMap<>();
                map.put(7L, position(12).asLong());
                expose(map);
                return lookup(map.get(7L));
                """, "public static native void expose(Object map);");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size());
        assertTrue(failure.diagnostics().getFirst().message().contains("channels=[1]"));
    }

    @Test
    void permitsEscapesOfIndependentOriginalContainers() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> original = new java.util.ArrayDeque<>();
                original.add(7L);
                saved = original;
                expose(original);
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                return lookup(queue.poll()) + original.poll();
                """, "public static Object saved; public static void expose(java.util.Queue<Long> queue) { queue.add(3L); }");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSynchronizationAndNullReceiverExceptions(boolean nullable) throws Exception {
        String monitor = nullable ? "flag ? queue : null" : "queue";
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                Object monitor = %s;
                synchronized (monitor) {
                    queue.add(position(12).asLong());
                    if (flag) {
                        try { throw new IllegalStateException(); }
                        catch (IllegalStateException expected) { queue.add(position(3).asLong()); }
                    }
                    return lookup(queue.poll());
                }
                """.formatted(monitor));
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void permitsPassingContainerStructureObservationsAsScalarArguments() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                int size = observe(queue.size(), queue.isEmpty());
                return lookup(queue.poll()) + size;
                """, "public static int observe(int size, boolean empty) { return empty ? 0 : size; }");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void appliesOrdinaryObserverSummary() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                observe(queue);
                return lookup(queue.poll());
                """, "public static int observe(java.util.Queue<Long> queue) { return queue.size(); }");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void exposesUnavailableNativeBodyInDiagnosticSummary() {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(position(12).asLong());
                expose(queue);
                return lookup(queue.poll());
                """, "public static native void expose(Object queue);");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals("UNKNOWN_CONTAINER_USE", failure.diagnostics().getFirst().code());
        assertTrue(failure.diagnostics().getFirst().candidates().stream().anyMatch(candidate ->
                candidate.contains("Callee summary sample/Subject.expose") && candidate.contains("body=false")));
    }

    @Test
    void appliesFactoryReturnSummary() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = create();
                queue.add(position(12).asLong());
                return lookup(queue.poll());
                """, "public static java.util.Queue<Long> create() { return new java.util.ArrayDeque<>(); }");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }
}
