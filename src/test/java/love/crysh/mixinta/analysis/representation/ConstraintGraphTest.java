package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintGraphTest {
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
