package love.crysh.mixinta.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSnapshotTest {
    @Test
    void isolatesInputsWhileSharingUnchangedStageData() throws Exception {
        byte[] original = {1};
        byte[] postMixin = {2};
        byte[] modified = {3};
        var snapshot = new RuntimeSnapshot(Map.of("a", original, "b", original),
                Map.of("a", original.clone(), "b", postMixin),
                Map.of("a", original.clone(), "b", modified), Set.of("b"), Set.of());
        original[0] = 4;
        postMixin[0] = 5;
        modified[0] = 6;
        assertArrayEquals(new byte[] {1}, snapshot.beforeMixin("a"));
        assertArrayEquals(new byte[] {2}, snapshot.afterMixin("b"));
        assertArrayEquals(new byte[] {3}, snapshot.outputOf("b"));
        assertEquals(Set.of("b"), snapshot.modifiedClasses());
        assertArrayEquals(new byte[] {1}, snapshot.outputOf("a"));
        byte[] supplied = snapshot.outputOf("b");
        supplied[0] = 7;
        assertArrayEquals(new byte[] {3}, snapshot.outputOf("b"));
        var field = RuntimeSnapshot.class.getDeclaredField("classes");
        field.setAccessible(true);
        var inputs = (Map<?, ?>) field.get(snapshot);
        Object a = inputs.get("a");
        var before = a.getClass().getDeclaredMethod("beforeMixin");
        var after = a.getClass().getDeclaredMethod("afterMixin");
        before.setAccessible(true);
        after.setAccessible(true);
        assertSame(before.invoke(a), after.invoke(a));
        assertNotSame(before.invoke(inputs.get("b")), after.invoke(inputs.get("b")));
        var outputs = RuntimeSnapshot.class.getDeclaredField("outputs");
        outputs.setAccessible(true);
        var changes = (Map<?, ?>) outputs.get(snapshot);
        assertEquals(snapshot.modifiedClasses(), changes.keySet());
        assertThrows(UnsupportedOperationException.class, changes::clear);
    }
}
