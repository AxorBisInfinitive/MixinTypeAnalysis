package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContainerCallGraphTest {
    @Test
    void finalizedQueriesReuseDispatchAndDependenciesAfterSecondConvergence() {
        var classes = love.crysh.mixinta.testsupport.BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static Object run(Object value) { return pass(value); }
                    public static Object pass(Object value) { return value; }
                }
                """));
        var node = love.crysh.mixinta.bytecode.ClassFiles.read(classes.get("sample/Subject"));
        var nodes = Map.of(node.name, node);
        var metrics = new love.crysh.mixinta.analysis.AnalysisMetrics();
        var analysis = ContainerAnalysis.analyzeAll(nodes, Set.of(), new ClassHierarchy(nodes, getClass().getClassLoader()),
                List.of(List.of()), metrics).get(Set.of());
        var graph = analysis.callGraph();
        var dependencies = graph.dependencies();
        for (int index = 0; index < 5; index++) {
            assertSame(dependencies, graph.dependencies());
            analysis.summaries().values().forEach(summary -> summary.calls().forEach(call -> {
                graph.covered(call.point());
                graph.coverage(call.point());
            }));
        }
        graph.recordMetrics();
        assertEquals(1, metrics.count(love.crysh.mixinta.analysis.AnalysisMetrics.Count.DEPENDENCY_BUILDS));
        assertTrue(metrics.count(love.crysh.mixinta.analysis.AnalysisMetrics.Count.FINAL_DISPATCH_HITS) > 10);
        assertTrue(metrics.count(love.crysh.mixinta.analysis.AnalysisMetrics.Count.SECOND_ROUNDS) > 0);
    }

    @Test
    void emptyOriginValuesRemainBoundToCompleteUseLocations() {
        var method = new MethodNode(Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        var flow = new MethodFlow("sample/Subject", method);
        var analyzer = new ContainerMethodAnalyzer(new ContainerFlow(flow, new ContainerRules(List.of())));
        var source = new love.crysh.mixinta.analysis.flow.OriginValue(1, Set.of());
        var first = new ProgramPoint(flow.key(), 3, "call", "receiver");
        var second = new ProgramPoint(flow.key(), 3, "call", "parameter=0");
        assertEquals(producer(first), analyzer.value(source, first));
        assertEquals(producer(second), analyzer.value(source, second));
        assertNotEquals(analyzer.value(source, first), analyzer.value(source, second));
    }
    @Test
    void reusesUnknownsByCompleteLocationWithoutAddingSolverNodes() throws Exception {
        var graph = new ContainerCallGraph(Map.of(), List.of(), new ClassHierarchy(Map.of(), getClass().getClassLoader()), new ContainerRules(List.of()));
        var method = new MethodKey("sample/Subject", "run", "()V");
        var first = new ProgramPoint(method, 1, "field", "first");
        var detail = new ProgramPoint(method, 1, "field", "second");
        var symbol = new ProgramPoint(method, 1, "other", "first");
        var resolved = graph.resolve(method, producer(first));
        assertSame(resolved, graph.resolve(method, producer(new ProgramPoint(method, 1, "field", "first"))));
        assertNotEquals(resolved, graph.resolve(method, producer(detail)));
        assertNotEquals(resolved, graph.resolve(method, producer(symbol)));
        var results = ContainerCallGraph.class.getDeclaredField("results");
        results.setAccessible(true);
        assertTrue(((Map<?, ?>) results.get(graph)).isEmpty());
    }

    @Test
    void preservesPendingEmptyReturnsUntilUnknownCompletion() {
        var rules = new ContainerRules(List.of());
        var owner = new ClassNode();
        owner.name = "sample/Subject";
        owner.superName = "java/lang/Object";
        var method = new MethodNode(Opcodes.ACC_STATIC, "run", "()Ljava/lang/Object;", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "external/Factory", "create", "()Ljava/lang/Object;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        owner.methods.add(method);
        var flow = new MethodFlow(owner.name, method);
        var nodes = Map.of(owner.name, owner);
        var graph = new ContainerCallGraph(nodes, List.of(new ContainerFlow(flow, rules)), new ClassHierarchy(nodes, getClass().getClassLoader()), rules);
        var value = producer(flow.point(0));
        assertTrue(ReferenceJoiner.empty(graph.resolve(flow.key(), value)));
        graph.solve();
        assertEquals(Set.of(flow.point(0)), graph.resolve(flow.key(), value).unknownOrigins());
    }

    private static MethodContainerSummary.Value producer(ProgramPoint point) {
        return new MethodContainerSummary.Value(Set.of(new MethodContainerSummary.Producer(point)), false);
    }
}
