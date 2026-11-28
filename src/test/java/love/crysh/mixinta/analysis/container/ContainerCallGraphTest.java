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
