package love.crysh.mixinta.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BatchPremisesTest {
    private static final Object PROCESSOR = new Object();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test
    void detectsRemovedAndReorderedBindingsAtEveryVerification() {
        Object transformer = new Object();
        var sources = new KnotClassSources.State(Set.of(), Map.of(), Set.of());
        Map<String, List<String>> bindings = Map.of("target", List.of("first", "second"));
        var premises = new BatchPremises(null, transformer, sources, snapshot(bindings), Map.of());
        java.util.function.BiFunction<String, String, IllegalStateException> failure =
                (code, detail) -> new IllegalStateException(code + " " + detail);
        assertDoesNotThrow(() -> premises.verify(null, transformer, sources, snapshot(bindings), failure));
        var removed = assertThrows(IllegalStateException.class,
                () -> premises.verify(null, transformer, sources, snapshot(Map.of()), failure));
        assertTrue(removed.getMessage().contains("MIXIN_CONFIGURATION_CHANGED"));
        assertThrows(IllegalStateException.class, () -> premises.verify(null, transformer, sources,
                snapshot(Map.of("target", List.of("second", "first"))), failure));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"processor", "remove", "replace", "reorder", "pending"})
    void freezesActualProcessorConfigurationIdentitiesAndOrder(String mutation) {
        Object first = new Object();
        Object second = new Object();
        var original = new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, List.of(first, second), List.of(), Map.of());
        var changed = switch (mutation) {
            case "processor" -> new MixinDiscovery.ConfigurationSnapshot(new Object(), original.active(), List.of(), Map.of());
            case "remove" -> new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, List.of(second), List.of(), Map.of());
            case "replace" -> new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, List.of(first, new Object()), List.of(), Map.of());
            case "reorder" -> new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, List.of(second, first), List.of(), Map.of());
            case "pending" -> new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, original.active(), List.of(first), Map.of());
            default -> throw new IllegalArgumentException(mutation);
        };
        var state = new KnotClassSources.State(Set.of(), Map.of(), Set.of());
        Object transformer = new Object();
        var premises = new BatchPremises(null, transformer, state, original, Map.of());
        var failure = assertThrows(IllegalStateException.class, () -> premises.verify(null, transformer, state, changed,
                (code, detail) -> new IllegalStateException(code + " " + detail)));
        assertTrue(failure.getMessage().contains("MIXIN_CONFIGURATION_CHANGED"));
    }

    private static MixinDiscovery.ConfigurationSnapshot snapshot(Map<?, ?> bindings) {
        return new MixinDiscovery.ConfigurationSnapshot(PROCESSOR, List.of(), List.of(), bindings);
    }

    @Test
    void verifiesTheDefiningLoaderAndCodeSource() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        var source = new ClassSourceIndex.Source("sample/Subject", directory.resolve("sample/Subject.class").toUri().toURL(),
                directory, loader, ClassSourceIndex.Status.DISCOVERED);
        var premises = new BatchPremises(null, new Object(), new KnotClassSources.State(Set.of(directory), Map.of(), Set.of()),
                snapshot(Map.of()), Map.of(source.name(), source));
        var domain = new java.security.ProtectionDomain(new java.security.CodeSource(directory.toUri().toURL(),
                (java.security.cert.Certificate[]) null), null);
        java.util.function.UnaryOperator<IllegalStateException> failure = error -> error;
        assertDoesNotThrow(() -> premises.verifyDefinition(source.name(), loader, domain, failure));
        assertThrows(IllegalStateException.class, () -> premises.verifyDefinition(source.name(), null, domain, failure));
        assertThrows(IllegalStateException.class, () -> premises.verifyDefinition(source.name(), loader, null, failure));
        var other = new java.security.ProtectionDomain(new java.security.CodeSource(directory.resolve("other").toUri().toURL(),
                (java.security.cert.Certificate[]) null), null);
        assertThrows(IllegalStateException.class, () -> premises.verifyDefinition(source.name(), loader, other, failure));
        assertDoesNotThrow(() -> premises.verifyDefinition("generated/Subject", loader, null, failure));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://example.invalid/", "file:/invalid?query", "file:/invalid path"})
    void retainsDefinitionSourceParsingCauses(String location) throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        var source = new ClassSourceIndex.Source("sample/Subject", directory.toUri().toURL(), directory,
                loader, ClassSourceIndex.Status.DISCOVERED);
        var premises = new BatchPremises(null, new Object(), new KnotClassSources.State(Set.of(directory), Map.of(), Set.of()),
                snapshot(Map.of()), Map.of(source.name(), source));
        var domain = new java.security.ProtectionDomain(new java.security.CodeSource(
                location == null ? null : new java.net.URL(location), (java.security.cert.Certificate[]) null), null);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var stopped = new IllegalStateException("retained failure");
        var actual = assertThrows(IllegalStateException.class, () -> premises.verifyDefinition(source.name(), loader, domain, cause -> {
            callbacks.incrementAndGet();
            assertTrue(cause.getMessage().startsWith("CLASS_SOURCE_CHANGED sample/Subject"));
            assertNotNull(cause.getCause());
            stopped.initCause(cause);
            return stopped;
        }));
        assertSame(stopped, actual);
        assertEquals(1, callbacks.get());
    }

    @Test
    void retainsFileIdentityCheckFailures() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        var source = new ClassSourceIndex.Source("sample/Subject", directory.toUri().toURL(), directory.resolve("missing"),
                loader, ClassSourceIndex.Status.DISCOVERED);
        var premises = new BatchPremises(null, new Object(), new KnotClassSources.State(Set.of(directory), Map.of(), Set.of()),
                snapshot(Map.of()), Map.of(source.name(), source));
        var domain = new java.security.ProtectionDomain(new java.security.CodeSource(directory.toUri().toURL(),
                (java.security.cert.Certificate[]) null), null);
        var failure = assertThrows(IllegalStateException.class,
                () -> premises.verifyDefinition(source.name(), loader, domain, cause -> cause));
        assertTrue(failure.getMessage().startsWith("CLASS_SOURCE_CHANGED"));
        assertInstanceOf(java.io.IOException.class, failure.getCause());
    }
}
