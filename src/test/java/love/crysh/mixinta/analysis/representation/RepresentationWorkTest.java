package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepresentationWorkTest {
    @Test
    void sortsLexicalIndicesAndKeepsDifferentSymbolsWithEqualKeysStable() {
        var method = new MethodKey("sample/Subject", "run", "()V");
        List<PropagationEdge> edges = new ArrayList<>();
        for (int index : new int[]{2, -2, 10, -1, 1}) {
            for (String symbol : List.of("second", "first")) {
                edges.add(new PropagationEdge(new ProgramPoint(method, index, symbol),
                        new ProgramPoint(method, 3, "target"), DependencyKind.LOCAL_MERGE));
            }
        }
        var expected = edges.stream().sorted(Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList();
        var work = new RepresentationWork();
        assertEquals(expected, work.orderedEdges(edges));
        assertEquals(List.of(-1, -1, -2, -2, 1, 1, 10, 10, 2, 2),
                work.orderedEdges(edges).stream().map(edge -> edge.fromPoint().instruction()).toList());
        var metrics = new AnalysisMetrics();
        work.recordMetrics(metrics);
        assertEquals(40, metrics.count(AnalysisMetrics.Count.SORT_KEYS));
        assertTrue(metrics.count(AnalysisMetrics.Count.SORT_COMPARISONS) > 0);
    }

    @Test
    void touchesObservesGrowingSelectionAndStopsAtFirstMatch() {
        var origin = new InsnNode(Opcodes.NOP);
        var value = OriginValue.of(1, origin);
        Set<AbstractInsnNode> selected = new HashSet<>();
        var work = new RepresentationWork();
        assertFalse(work.touches(value, selected));
        selected.add(origin);
        assertTrue(work.touches(value, selected));
        assertFalse(work.touches(null, selected));
        assertFalse(work.touches(new OriginValue(1, Set.of()), selected));
        var metrics = new AnalysisMetrics();
        work.recordMetrics(metrics);
        assertEquals(4, metrics.count(AnalysisMetrics.Count.TOUCHES_CALLS));
        assertEquals(2, metrics.count(AnalysisMetrics.Count.TOUCHES_ORIGINS));
    }
}
