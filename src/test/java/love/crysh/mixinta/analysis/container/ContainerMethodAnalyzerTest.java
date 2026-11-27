package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContainerMethodAnalyzerTest {
    private record Sample(Map<MethodKey, MethodContainerSummary> methods, Map<String, byte[]> classes) {
        MethodContainerSummary method(String name) {
            return methods.values().stream().filter(method -> method.method().owner().equals("sample/Subject")
                    && method.method().name().equals(name)).findFirst().orElseThrow();
        }
    }

    private static Sample analyze(String members) throws Exception {
        Map<String, byte[]> classes = BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {" + members + "}"));
        Map<MethodKey, MethodContainerSummary> summaries = new LinkedHashMap<>();
        for (var bytes : classes.values()) {
            var owner = ClassFiles.read(bytes);
            for (var method : owner.methods) {
                MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    summaries.put(key, ContainerMethodAnalyzer.unavailable(key, method.access));
                } else {
                    var flow = new ContainerFlow(new MethodFlow(owner.name, method), new ContainerRules(List.of()));

                    summaries.put(key, new ContainerMethodAnalyzer(flow).analyze());
                }
            }
        }
        return new Sample(summaries, classes);
    }

    private static MethodContainerSummary.Call call(MethodContainerSummary summary, String name) {
        return summary.calls().stream().filter(call -> call.target().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void extractsParameterAliasesThroughCastsAndNullableMerges() throws Exception {
        var sample = analyze("""
                public static java.util.Queue<Long> alias(java.util.Queue<Long> queue, boolean flag) {
                    Object value = flag ? queue : null;
                    return (java.util.Queue<Long>) value;
                }
                """);
        var summary = sample.method("alias");
        var value = summary.returns().getFirst().value();
        assertTrue(value.nullable());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")), value.origins());
        assertTrue(summary.allocations().isEmpty());
        assertTrue(summary.calls().isEmpty());
        assertEquals(ContainerEffects.Kind.RETURN, summary.effects().getFirst().kind());
    }

    @Test
    void distinguishesReceiverAndParametersAfterWideLocals() throws Exception {
        var summary = analyze("""
                public Object choose(long first, double second, Object value, boolean flag) {
                    return flag ? this : value;
                }
                """).method("choose");
        assertEquals(Set.of(MethodContainerSummary.Receiver.INSTANCE,
                new MethodContainerSummary.Parameter(2, "Ljava/lang/Object;")), summary.returns().getFirst().value().origins());
        assertEquals(4, summary.parameters().size());
        assertEquals("J", summary.parameters().get(0).descriptor());
        assertEquals("D", summary.parameters().get(1).descriptor());
        assertFalse(summary.returns().getFirst().value().nullable());
    }

    @Test
    void retainsBoxingRelationsAndWriteParameterBindings() throws Exception {
        var summary = analyze("""
                public static void write(java.util.Queue<Long> queue, long value) { queue.add(value); }
                """).method("write");
        var write = call(summary, "add");
        var box = call(summary, "valueOf");
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")), write.receiver().origins());
        assertEquals(ContainerOperation.WRITE, write.intrinsic().operation());
        assertEquals(0, write.intrinsic().channel());
        assertEquals(0, write.intrinsic().elementParameter());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(1, "J")), box.arguments().getFirst().origins());
        assertEquals(Set.of(new MethodContainerSummary.Producer(box.point())), write.arguments().getFirst().origins());
        assertEquals(Opcodes.INVOKEINTERFACE, write.opcode());
        assertTrue(write.interfaceOwner());
        assertNull(box.receiver());
        assertEquals(Opcodes.INVOKESTATIC, box.opcode());
    }

    @Test
    void retainsReadAndUnboxingReturnRelations() throws Exception {
        var summary = analyze("""
                public static long read(java.util.Queue<Long> queue) { return queue.poll(); }
                """).method("read");
        var read = call(summary, "poll");
        var unbox = call(summary, "longValue");
        assertEquals(ContainerOperation.READ_REMOVE, read.intrinsic().operation());
        assertEquals(Set.of(new MethodContainerSummary.Producer(read.point())), unbox.receiver().origins());
        assertEquals(Set.of(new MethodContainerSummary.Producer(unbox.point())), summary.returns().getFirst().value().origins());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")), read.receiver().origins());
    }

    @Test
    void extractsMapChannelsAndOldValueReturn() throws Exception {
        var summary = analyze("""
                public static Long write(java.util.Map<Object, Long> map, Object key, Long value) {
                    return map.put(key, value);
                }
                """).method("write");
        var put = call(summary, "put");
        assertEquals(ContainerOperation.READ_WRITE, put.intrinsic().operation());
        assertEquals(1, put.intrinsic().channel());
        assertEquals(0, put.intrinsic().keyParameter());
        assertEquals(1, put.intrinsic().elementParameter());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(1, "Ljava/lang/Object;")), put.arguments().get(0).origins());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(2, "Ljava/lang/Long;")), put.arguments().get(1).origins());
        assertEquals(Set.of(new MethodContainerSummary.Producer(put.point())), summary.returns().getFirst().value().origins());
    }

    @Test
    void extractsListReadWriteAndClear() throws Exception {
        var sample = analyze("""
                public static Long replace(java.util.List<Long> values, Long value) { return values.set(0, value); }
                public static void clear(java.util.List<Long> values) { values.clear(); }
                """);
        var set = call(sample.method("replace"), "set");
        assertEquals(ContainerOperation.READ_WRITE, set.intrinsic().operation());
        assertEquals(1, set.intrinsic().elementParameter());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(1, "Ljava/lang/Long;")), set.arguments().get(1).origins());
        assertEquals(ContainerOperation.CLEAR, call(sample.method("clear"), "clear").intrinsic().operation());
        assertTrue(sample.method("clear").effects().isEmpty());
    }

    @Test
    void recordsScalarObservationsWithoutExposingContainer() throws Exception {
        var summary = analyze("""
                public static int observe(java.util.Queue<Long> queue, int extra) {
                    return queue.size() + extra;
                }
                """).method("observe");
        assertEquals(ContainerOperation.OBSERVE, call(summary, "size").intrinsic().operation());
        assertTrue(summary.effects().isEmpty());
        assertEquals(1, summary.returns().size());
    }

    @Test
    void recordsFieldAndArrayEscapesOfSymbolicParameter() throws Exception {
        var summary = analyze("""
                public static Object saved;
                public static void publish(java.util.Queue<Long> queue, Object[] array) {
                    saved = queue;
                    array[0] = queue;
                }
                """).method("publish");
        assertEquals(2, summary.effects().size());
        assertEquals(Set.of(ContainerEffects.Kind.FIELD_STORE, ContainerEffects.Kind.ARRAY_STORE),
                summary.effects().stream().map(MethodContainerSummary.Effect::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(summary.effects().stream().allMatch(effect -> effect.subject().origins()
                .equals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")))));
    }

    @Test
    void preservesAllPossibleAllocationSitesInReturnedAlias() throws Exception {
        var summary = analyze("""
                public static java.util.Queue<Long> create(boolean flag) {
                    java.util.Queue<Long> queue = flag ? new java.util.ArrayDeque<>() : new java.util.LinkedList<>();
                    return queue;
                }
                """).method("create");
        assertEquals(2, summary.allocations().size());
        assertEquals(Set.copyOf(summary.allocations()), summary.returns().getFirst().value().storages());
        assertEquals(2, summary.returns().getFirst().value().origins().size());
    }

    @Test
    void usesOneAbstractAllocationForLoopExecutions() throws Exception {
        var summary = analyze("""
                public static java.util.Queue<Long> create(int count) {
                    java.util.Queue<Long> queue = null;
                    for (int index = 0; index < count; index++) queue = new java.util.ArrayDeque<>();
                    return queue;
                }
                """).method("create");
        assertEquals(1, summary.allocations().size());
        assertEquals(Set.copyOf(summary.allocations()), summary.returns().getFirst().value().storages());
        assertTrue(summary.returns().getFirst().value().nullable());
    }

    @Test
    void keepsReturnedCallsAndMissingBodiesExplicit() throws Exception {
        var sample = analyze("""
                public static native java.util.Queue<Long> external();
                public static java.util.Queue<Long> load() { return external(); }
                """);
        assertFalse(sample.method("external").hasBody());
        var load = sample.method("load");
        assertTrue(load.hasBody());
        assertTrue(load.allocations().isEmpty());
        assertEquals(Set.of(new MethodContainerSummary.Producer(call(load, "external").point())),
                load.returns().getFirst().value().origins());
    }

    @Test
    void recordsVirtualDispatchAndAbstractDeclarations() throws Exception {
        var sample = analyze("""
                public interface Router { java.util.Queue<Long> route(java.util.Queue<Long> queue); }
                public static java.util.Queue<Long> invoke(Router router, java.util.Queue<Long> queue) {
                    return router.route(queue);
                }
                """);
        var invoke = sample.method("invoke");
        var route = call(invoke, "route");
        assertEquals(Opcodes.INVOKEINTERFACE, route.opcode());
        assertTrue(route.interfaceOwner());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Lsample/Subject$Router;")), route.receiver().origins());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(1, "Ljava/util/Queue;")), route.arguments().getFirst().origins());
        assertFalse(sample.methods().get(route.target()).hasBody());
        assertEquals(Set.of(new MethodContainerSummary.Producer(route.point())), invoke.returns().getFirst().value().origins());
    }

    @Test
    void retainsRecursiveCallAsUnresolvedRelation() throws Exception {
        var summary = analyze("""
                public static java.util.Queue<Long> recurse(java.util.Queue<Long> queue, int count) {
                    return count == 0 ? queue : recurse(queue, count - 1);
                }
                """).method("recurse");
        var recurse = call(summary, "recurse");
        assertEquals(summary.method(), recurse.target());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;"),
                new MethodContainerSummary.Producer(recurse.point())), summary.returns().getFirst().value().origins());
        assertNull(recurse.intrinsic());
    }

    @Test
    void preservesUnknownFieldAndArrayReadsAlongsideKnownParameters() throws Exception {
        var sample = analyze("""
                public static java.util.Queue<Long> saved;
                public static java.util.Queue<Long> field(java.util.Queue<Long> queue, boolean flag) {
                    return flag ? queue : saved;
                }
                public static java.util.Queue<Long> array(java.util.Queue<Long>[] values) { return values[0]; }
                """);
        var field = sample.method("field").returns().getFirst().value();
        assertTrue(field.origins().contains(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")));
        assertTrue(field.origins().stream().anyMatch(origin -> origin instanceof MethodContainerSummary.Producer producer
                && producer.point().symbol().contains(".saved:")));
        var array = sample.method("array");
        assertTrue(array.returns().getFirst().value().origins().stream().allMatch(MethodContainerSummary.Producer.class::isInstance));
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "[Ljava/util/Queue;")),
                array.effects().stream().filter(effect -> effect.kind() == ContainerEffects.Kind.UNSUPPORTED_USE)
                        .findFirst().orElseThrow().subject().origins());
    }

    @Test
    void preservesCatchValuesAsUnknownProducers() throws Exception {
        var summary = analyze("""
                public static Object caught() {
                    try { throw new IllegalStateException(); }
                    catch (IllegalStateException failure) { return failure; }
                }
                """).method("caught");
        var value = summary.returns().getFirst().value();
        assertFalse(value.origins().isEmpty());
        assertTrue(value.origins().stream().allMatch(MethodContainerSummary.Producer.class::isInstance));
        assertFalse(value.nullable());
    }

    @Test
    void recordsCaptureOfReferenceParameter() throws Exception {
        var summary = analyze("""
                public static Runnable capture(java.util.Queue<Long> queue, long value) {
                    return () -> queue.add(value);
                }
                """).method("capture");
        var capture = summary.effects().stream().filter(effect -> effect.kind() == ContainerEffects.Kind.DYNAMIC_CAPTURE).toList();
        assertEquals(1, capture.size());
        assertEquals(0, capture.getFirst().parameter());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "Ljava/util/Queue;")), capture.getFirst().subject().origins());
    }

    @Test
    void excludesPrimitiveArgumentsFromContainerAccessEffects() throws Exception {
        var summary = analyze("""
                public static long twice(long value) { return value * 2; }
                public static long numeric(long value) { return twice(value); }
                """).method("numeric");
        assertTrue(summary.effects().isEmpty());
        assertEquals(Set.of(new MethodContainerSummary.Parameter(0, "J")), call(summary, "twice").arguments().getFirst().origins());
    }

    @Test
    void summariesAreImmutableAndIndependentOfAsmNodes() throws Exception {
        var sample = analyze("public static Object identity(Object value) { return java.util.Objects.requireNonNull(value); }");
        var summary = sample.method("identity");
        assertThrows(UnsupportedOperationException.class, () -> summary.parameters().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.returns().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.calls().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.calls().getFirst().arguments().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.returns().getFirst().value().origins().clear());
        var owner = ClassFiles.read(sample.classes().get("sample/Subject"));
        var method = ClassFiles.findMethod(owner, "identity", "(Ljava/lang/Object;)Ljava/lang/Object;");
        var flow = new ContainerFlow(new MethodFlow(owner.name, method), new ContainerRules(List.of()));

        var before = new ContainerMethodAnalyzer(flow).analyze();
        method.instructions.clear();
        assertEquals(summary, before);
    }
}
