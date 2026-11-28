package love.crysh.mixinta.runtime;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MixinDiscoveryTest {
    @Test
    void snapshotsActualConfigurationsWithoutACandidateRegistration() throws Exception {
        var transformer = new Transformer();
        IMixinConfig configuration = configuration(() -> { });
        transformer.processor.configs.add(configuration);
        var discovery = new MixinDiscovery();
        var snapshot = discovery.configurationSnapshot(transformer);
        assertEquals(List.of(configuration), snapshot.active());
        assertEquals(Set.of(configuration), snapshot.bindings().keySet());
        assertTrue(snapshot.pending().isEmpty());
        transformer.processor.pendingConfigs.add(configuration);
        assertFalse(snapshot.sameProcessor(discovery.configurationSnapshot(transformer)));
        transformer.processor.configs.clear();
        assertEquals(List.of(configuration), snapshot.active());
        assertEquals(Set.of(configuration), snapshot.bindings().keySet());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.active().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.bindings().clear());
    }

    @Test
    void rejectsAProcessorCollectionChangedWhileBindingsAreRead() {
        var transformer = new Transformer();
        transformer.processor.configs.add(configuration(() -> transformer.processor.configs.clear()));
        var failure = assertThrows(IllegalStateException.class,
                () -> new MixinDiscovery().configurationSnapshot(transformer));
        assertTrue(failure.getMessage().contains("MIXIN_CONFIGURATION_CHANGED"));
    }

    @Test
    void comparesConfigurationIdentitiesEvenWhenEqualsMatches() {
        Object first = new String("configuration");
        Object second = new String("configuration");
        Object processor = new Object();
        var snapshot = new MixinDiscovery.ConfigurationSnapshot(processor, List.of(first), List.of(), java.util.Map.of());
        var changed = new MixinDiscovery.ConfigurationSnapshot(processor, List.of(second), List.of(), java.util.Map.of());
        assertFalse(snapshot.sameProcessor(changed));
    }

    public interface TargetBindings {
        List<?> getMixinsFor(String target);
    }

    private static IMixinConfig configuration(Runnable read) {
        return (IMixinConfig) Proxy.newProxyInstance(MixinDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {IMixinConfig.class, TargetBindings.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "getTargets" -> {
                        read.run();
                        yield Set.of("sample.Subject");
                    }
                    case "getMixinsFor" -> List.of();
                    case "getPriority" -> 1000;
                    case "getEnvironment" -> null;
                    case "getMixinPackage" -> "sample.mixin";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Processor {
        private final List<Object> configs = new ArrayList<>();
        private final List<Object> pendingConfigs = new ArrayList<>();
    }

    private static final class Transformer {
        private Processor processor = new Processor();
    }
}
