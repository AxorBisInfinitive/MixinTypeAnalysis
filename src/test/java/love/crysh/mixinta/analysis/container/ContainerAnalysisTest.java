package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContainerAnalysisTest {
    @Test
    void retainsEffectsOfCustomCollectionImplementationsWithUnmodeledReceivers() {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static Object saved;
                    public static void use() {
                        java.util.Queue<Object> queue = new java.util.ArrayDeque<>();
                        java.util.Collection<Object> receiver = new Custom();
                        receiver.contains(queue);
                    }
                    public static class Custom extends java.util.AbstractSet<Object> {
                        public boolean contains(Object value) { saved = value; return true; }
                        public int size() { return 0; }
                        public java.util.Iterator<Object> iterator() { return java.util.Collections.emptyIterator(); }
                    }
                }
                """)).forEach((name, bytes) -> nodes.put(name, ClassFiles.read(bytes)));
        var hierarchy = new ClassHierarchy(nodes, getClass().getClassLoader());
        var analysis = ContainerAnalysis.analyzeAll(nodes, Set.of(), hierarchy, List.of(List.<ContainerContract>of()))
                .get(Set.of());
        assertTrue(analysis.failures().isEmpty());
        assertTrue(analysis.effects().accesses().stream().anyMatch(access ->
                access.kind() == ContainerEffects.Kind.FIELD_STORE
                        && access.storages().stream().anyMatch(storage -> storage.method().owner().equals("sample/Subject"))));
    }

    @Test
    void keepsModeledMapViewsOutOfUnrelatedVirtualImplementations() {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static Object read() {
                        java.util.Map<Object, Object> map = new java.util.LinkedHashMap<>();
                        map.put(1L, 12L);
                        java.util.Collection<Object> values = map.values();
                        values.contains(3L);
                        values.remove(3L);
                        return values.iterator().next();
                    }
                    public static class Unrelated extends java.util.AbstractSet<Object> {
                        private Object[] data;
                        public boolean contains(Object value) { return data.length != 0; }
                        public int size() { return 0; }
                        public java.util.Iterator<Object> iterator() { return java.util.Collections.emptyIterator(); }
                    }
                }
                """)).forEach((name, bytes) -> nodes.put(name, ClassFiles.read(bytes)));
        var hierarchy = new ClassHierarchy(nodes, getClass().getClassLoader());
        var analysis = ContainerAnalysis.analyzeAll(nodes, Set.of(), hierarchy, List.of(List.<ContainerContract>of()))
                .get(Set.of());
        assertTrue(analysis.failures().isEmpty());
        assertTrue(analysis.effects().accesses().stream().noneMatch(access ->
                access.point().method().owner().equals("sample/Subject$Unrelated")
                        && access.storages().stream().anyMatch(storage -> storage.method().owner().equals("sample/Subject"))));
    }

    @Test
    void sharesSourceFramesButIsolatesContractDependentAllocationsAndAliases() {
        Map<String, byte[]> bytes = BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static final class Box {
                        public Object get() { return null; }
                    }
                    public static Box create() { return new Box(); }
                }
                """));
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        bytes.forEach((name, value) -> nodes.put(name, ClassFiles.read(value)));
        var hierarchy = new ClassHierarchy(nodes, ContainerAnalysisTest.class.getClassLoader());
        var allocation = new ContainerContract("box", 1, new MethodKey("sample/Subject$Box", "<init>", "()V"),
                new ContainerSummary(ContainerOperation.ALLOCATE, 0, -1));
        var read = new ContainerContract("read", 1, new MethodKey("sample/Subject$Box", "get", "()Ljava/lang/Object;"),
                new ContainerSummary(ContainerOperation.READ, 0, -1));

        var groups = ContainerAnalysis.analyzeAll(nodes, Set.of(), hierarchy,
                List.of(List.of(), List.of(allocation, read), List.of(read, allocation)));
        var ordinary = groups.get(Set.of());
        var contracted = groups.get(Set.of(allocation, read));
        assertSame(contracted, groups.get(Set.of(read, allocation)));
        assertEquals(2, groups.size());
        assertThrows(UnsupportedOperationException.class, groups::clear);
        assertNotSame(ordinary.callGraph(), contracted.callGraph());
        var ordinaryFlow = createFlow(ordinary);
        var contractedFlow = createFlow(contracted);
        assertSame(ordinaryFlow.source(), contractedFlow.source());
        assertSame(ordinaryFlow.source().instruction(0), contractedFlow.source().instruction(0));
        assertNotSame(ordinaryFlow.aliases(), contractedFlow.aliases());
        assertTrue(ordinaryFlow.allocations().isEmpty());
        assertEquals(1, contractedFlow.allocations().size());
        assertSame(ordinary, groups.get(Set.of()));
        assertTrue(ordinaryFlow.allocations().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> contractedFlow.allocations().clear());

        var anotherBatch = ContainerAnalysis.analyzeAll(nodes, Set.of(), hierarchy, List.of(List.of(allocation, read))).get(Set.of(allocation, read));
        assertNotSame(contracted.callGraph(), anotherBatch.callGraph());
        assertNotSame(contractedFlow.source(), createFlow(anotherBatch).source());
    }

    private static ContainerFlow createFlow(ContainerAnalysis analysis) {
        return analysis.flows().stream().filter(flow -> flow.source().key().name().equals("create")).findFirst().orElseThrow();
    }

    @Test
    void collectsContractDependentEffectsOnceAndExposesOnlyCompletedResults() {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static final class Box { }
                    public static Box create() { return new Box(); }
                }
                """)).forEach((name, bytes) -> nodes.put(name, ClassFiles.read(bytes)));
        var hierarchy = new ClassHierarchy(nodes, getClass().getClassLoader());
        var allocation = new ContainerContract("box", 1, new MethodKey("sample/Subject$Box", "<init>", "()V"),
                new ContainerSummary(ContainerOperation.ALLOCATE, 0, -1));

        var groups = ContainerAnalysis.analyzeAll(nodes, Set.of(), hierarchy, List.of(List.of(), List.of(allocation)));
        var ordinary = groups.get(Set.of());
        var contracted = groups.get(Set.of(allocation));
        var storage = createFlow(contracted).allocations().values().iterator().next();

        assertTrue(ordinary.effects().accesses().isEmpty());
        assertEquals(ContainerEffects.EscapeState.ESCAPED, contracted.effects().state(storage));
        assertTrue(contracted.effects().accesses().stream().anyMatch(access -> access.kind() == ContainerEffects.Kind.RETURN));
        assertTrue(contracted.failures().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> contracted.flows().clear());
        assertThrows(UnsupportedOperationException.class, () -> contracted.summaries().clear());
        assertThrows(UnsupportedOperationException.class, () -> contracted.effects().accesses().clear());
        assertThrows(UnsupportedOperationException.class, () -> contracted.effects().accesses().getFirst().storages().clear());
        assertTrue(ordinary.effects().accesses().isEmpty());
    }
}
