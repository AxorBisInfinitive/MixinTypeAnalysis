package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintGraphTest {
    @Test
    void locationIndexKeepsSubjectIdentityAndInvalidatesOnMutableChanges() {
        var method = new MethodKey("sample/Subject", "run", "()V");
        var point = new ProgramPoint(method, 3, "call");
        var argument = ConstraintGraph.use(point, 0);
        var receiver = ConstraintGraph.receiver(point);
        var operand = ConstraintGraph.operand(point, 0);
        var source = ConstraintGraph.value(new ProgramPoint(method, 1, "source"));
        var graph = new ConstraintGraph();
        graph.link(argument, source, DependencyKind.TARGET_ARGUMENT);
        assertEquals(Set.of(argument), graph.at(new ProgramPoint(method, 3, "other", "detail"), 0));
        graph.link(receiver, source, DependencyKind.SOURCE_RESULT);
        graph.link(operand, source, DependencyKind.SOURCE_RESULT);
        assertEquals(List.of(argument, receiver, operand), List.copyOf(graph.at(point, -1)));
        var snapshot = graph.snapshot();
        assertEquals(Set.of(argument), snapshot.at(point, 0));
        assertEquals(graph.evidence(List.of(source)), snapshot.evidence(List.of(source)));
        assertSame(snapshot.evidence(List.of(source)), snapshot.evidence(List.of(source)));
        var copy = snapshot.mutableCopy();
        var capture = ConstraintGraph.capture(point, 0);
        copy.link(capture, source, DependencyKind.OTHER);
        assertEquals(3, snapshot.at(point, -1).size());
        assertEquals(4, copy.at(point, -1).size());
    }

    @Test
    void evidenceRetainsDifferentKindsAndDeduplicatesEqualSubjectPoints() {
        var method = new MethodKey("sample/Subject", "run", "()V");
        var point = new ProgramPoint(method, 1, "source");
        var value = ConstraintGraph.value(point);
        var unknown = ConstraintGraph.unknown(point);
        var target = ConstraintGraph.use(new ProgramPoint(method, 2, "target"), 0);
        var graph = new ConstraintGraph();
        graph.link(target, value, DependencyKind.TARGET_ARGUMENT);
        graph.link(target, unknown, DependencyKind.TARGET_ARGUMENT);
        graph.link(target, value, DependencyKind.LOCAL_MERGE);
        assertEquals(2, graph.evidence(List.of(value, unknown)).size());
        assertEquals(2, graph.snapshot().evidence(List.of(value, unknown)).size());
    }
    @Test
    void validationCopyCannotChangePropagationSnapshotOrItsEvidence() {
        var method = new MethodKey("sample/Subject", "run", "()V");
        var source = ConstraintGraph.value(new ProgramPoint(method, 1, "source"));
        var target = ConstraintGraph.use(new ProgramPoint(method, 2, "target"), 0);
        var boundary = ConstraintGraph.use(new ProgramPoint(method, 3, "boundary"), 0);
        var propagation = new ConstraintGraph();
        propagation.link(target, source, DependencyKind.TARGET_ARGUMENT);
        var snapshot = propagation.snapshot();
        var originalEvidence = snapshot.evidence(List.of(source));
        var validation = snapshot.mutableCopy();
        validation.link(boundary, target, DependencyKind.SOURCE_RESULT);

        assertFalse(snapshot.contains(boundary));
        assertEquals(originalEvidence, snapshot.evidence(List.of(source)));
        assertTrue(validation.ancestors(List.of(source)).contains(boundary));
        assertThrows(IllegalStateException.class,
                () -> snapshot.link(boundary, target, DependencyKind.SOURCE_RESULT));
        var validated = validation.snapshot();
        propagation.link(source, boundary, DependencyKind.UNBOXING);
        assertEquals(originalEvidence, snapshot.evidence(List.of(source)));
        assertEquals(2, validated.evidence(List.of(source)).size());
    }
}
