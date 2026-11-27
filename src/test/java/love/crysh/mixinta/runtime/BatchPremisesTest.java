package love.crysh.mixinta.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BatchPremisesTest {
    @Test
    void detectsRemovedAndReorderedBindingsAtEveryVerification() {
        Object transformer = new Object();
        var sources = new KnotClassSources.State(Set.of(), Map.of(), Set.of());
        Map<String, List<String>> bindings = Map.of("target", List.of("first", "second"));
        var premises = new BatchPremises(null, transformer, sources, bindings);
        java.util.function.BiFunction<String, String, IllegalStateException> failure =
                (code, detail) -> new IllegalStateException(code + " " + detail);
        assertDoesNotThrow(() -> premises.verify(null, transformer, sources, bindings, failure));
        var removed = assertThrows(IllegalStateException.class,
                () -> premises.verify(null, transformer, sources, Map.of(), failure));
        assertTrue(removed.getMessage().contains("MIXIN_CONFIGURATION_CHANGED"));
        assertThrows(IllegalStateException.class, () -> premises.verify(null, transformer, sources,
                Map.of("target", List.of("second", "first")), failure));
    }
}
