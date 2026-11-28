package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TypedOverwriteRuntimeTest {
    @TempDir Path directory;

    @Test
    void keepsPhaseChangesWhenTheLogFilterThrows() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var previousFilter = fixture.logger.getFilter();
            fixture.logger.setFilter(record -> { throw new IllegalStateException("filter rejected event"); });
            try {
                Field phase = field("phase");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object inactive = Enum.valueOf((Class) phase.getType(), "INACTIVE");
                var transition = TypedOverwriteRuntime.class.getDeclaredMethod("transition", phase.getType());
                transition.setAccessible(true);
                assertDoesNotThrow(() -> transition.invoke(null, inactive));
                assertEquals("INACTIVE", phase.get(null).toString());
                assertNull(field("failure").get(null));
            } finally {
                fixture.logger.setFilter(previousFilter);
            }
        }
    }

    @Test
    void retainsUncheckedResourceResolutionFailures() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var unavailable = new SecurityException("source access denied");
            URL resource = directory.resolve("sample/Outer.class").toUri().toURL();
            fixture.loader.resolve = name -> resource;
            Set<Path> roots = new java.util.AbstractSet<>() {
                @Override public boolean contains(Object value) { throw unavailable; }
                @Override public java.util.Iterator<Path> iterator() { return Set.of(directory).iterator(); }
                @Override public int size() { return 1; }
            };
            fixture.set("sourceState", new KnotClassSources.State(roots, Map.of(), Set.of()));
            var failure = assertThrows(IllegalStateException.class, () -> checkSource("sample/Outer"));
            assertSame(unavailable, failure.getCause().getCause());
            fixture.verifyFailure(failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settlesAllNestedTimersBeforeReportingFailure(boolean preprocessing) throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.loader.resolve = name -> {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                return fixture.changed;
            };
            String method = preprocessing ? "readInput" : "checkSource";
            var failure = assertThrows(IllegalStateException.class, () -> invoke(method, "sample/Outer"));
            fixture.verifyFailure(failure.getCause());
            fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
            if (preprocessing) fixture.verifyTiming(AnalysisMetrics.Stage.PREPROCESS);
        }
    }

    @Test
    void settlesOuterChecksWhenTheFailureLoggerThrows() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.failLogger = true;
            fixture.loader.resolve = name -> {
                if (name.equals("sample/Outer.class")) {
                    assertThrows(IllegalStateException.class, () -> checkSource("sample/Inner"));
                    assertTrue(fixture.metrics().isEmpty());
                }
                return fixture.changed;
            };
            var failure = assertThrows(IllegalStateException.class, () -> checkSource("sample/Outer"));
            assertTrue(failure.getCause().getMessage().contains("sample/Inner"));
            fixture.verifyFailure(failure.getCause());
            fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
        }
    }

    @Test
    void waitsForConcurrentChecksToSettle() throws Exception {
        try (var fixture = new Fixture(directory); var executor = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1);
            var released = new CountDownLatch(1);
            fixture.loader.resolve = name -> {
                if (name.equals("sample/Outer.class")) {
                    entered.countDown();
                    try {
                        if (!released.await(5, TimeUnit.SECONDS)) throw new AssertionError("Check was not released");
                    } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return fixture.changed;
            };
            var outer = executor.submit(() -> assertThrows(IllegalStateException.class, () -> checkSource("sample/Outer")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var inner = executor.submit(() -> assertThrows(IllegalStateException.class, () -> checkSource("sample/Inner")));
                var failure = inner.get(5, TimeUnit.SECONDS);
                assertTrue(fixture.metrics().isEmpty());
                released.countDown();
                assertSame(failure.getCause(), outer.get(5, TimeUnit.SECONDS).getCause());
                fixture.verifyFailure(failure.getCause());
                fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
            } finally {
                released.countDown();
                outer.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void waitsForTheOwnerToFinishAfterChecksSettle() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.set("owner", Thread.currentThread());
            var failure = assertThrows(IllegalStateException.class, () -> checkSource("sample/Outer"));
            assertTrue(fixture.metrics().isEmpty());
            invoke("releaseOwner");
            fixture.verifyFailure(failure.getCause());
            fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void measuresTheWholeDefinitionSourceCheck(boolean invalid) throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.loader.resolve = name -> resource(directory, name);
            fixture.frozenInputs.add("sample/Outer");
            var metrics = (AnalysisMetrics) field("METRICS").get(null);
            long before = metrics.nanos().getOrDefault(AnalysisMetrics.Stage.PREMISES, 0L);
            long checks = metrics.count(AnalysisMetrics.Count.SOURCE_CHECKS);
            var elapsed = new java.util.concurrent.atomic.AtomicLong();
            var source = delayedSource(directory, () -> {
                assertEquals(1, field("activeChecks").get(null));
                long started = System.nanoTime();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                elapsed.addAndGet(System.nanoTime() - started);
            }, invalid);
            if (invalid) {
                var failure = assertThrows(IllegalStateException.class, () -> checkDefinition(fixture.loader, source));
                fixture.verifyFailure(failure.getCause());
            } else {
                assertDoesNotThrow(() -> checkDefinition(fixture.loader, source));
                assertNull(field("failure").get(null));
            }
            assertTrue(metrics.nanos().get(AnalysisMetrics.Stage.PREMISES) - before >= elapsed.get());
            assertEquals(checks + 1, metrics.count(AnalysisMetrics.Count.SOURCE_CHECKS));
            assertEquals(0, field("activeChecks").get(null));
            fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
        }
    }

    @Test
    void keepsDefinitionChecksActiveUntilAConcurrentFailureSettles() throws Exception {
        try (var fixture = new Fixture(directory); var executor = Executors.newSingleThreadExecutor()) {
            fixture.loader.resolve = name -> name.equals("sample/Outer.class") ? resource(directory, name) : fixture.changed;
            fixture.frozenInputs.add("sample/Outer");
            var entered = new CountDownLatch(1);
            var released = new CountDownLatch(1);
            var domain = delayedSource(directory, () -> {
                entered.countDown();
                assertTrue(released.await(5, TimeUnit.SECONDS));
            }, false);
            var definition = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> checkDefinition(fixture.loader, domain)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var stopped = assertThrows(IllegalStateException.class, () -> checkSource("sample/Inner"));
                assertTrue(fixture.metrics().isEmpty());
                released.countDown();
                var rejected = definition.get(5, TimeUnit.SECONDS);
                assertSame(stopped.getCause(), rejected.getCause());
                fixture.verifyFailure(stopped.getCause());
                fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
            } finally {
                released.countDown();
                definition.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void measuresAlreadyDefinedSourcesBeforeReportingFailure() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.loader.resolve = name -> resource(directory, name);
            fixture.frozenInputs.add("sample/Outer");
            var metrics = (AnalysisMetrics) field("METRICS").get(null);
            long before = metrics.nanos().getOrDefault(AnalysisMetrics.Stage.PREMISES, 0L);
            var elapsed = new java.util.concurrent.atomic.AtomicLong();
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var domain = delayedSource(directory, () -> {
                if (!armed.get()) return;
                assertEquals(1, field("activeChecks").get(null));
                long started = System.nanoTime();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                elapsed.addAndGet(System.nanoTime() - started);
            }, true);
            byte[] bytes = love.crysh.mixinta.testsupport.BytecodeFixtures.compile(
                    Map.of("sample.Outer", "package sample; public class Outer {}" )).get("sample/Outer");
            Class<?> type = fixture.loader.define(bytes, domain.getCodeSource());
            armed.set(true);
            @SuppressWarnings("unchecked")
            var existing = (Map<String, Class<?>>) field("EXISTING").get(null);
            Class<?> previous = existing.put("sample/Outer", type);
            try {
                var rejected = assertThrows(IllegalStateException.class, () -> invoke("checkSources"));
                fixture.verifyFailure(rejected.getCause());
                assertTrue(elapsed.get() > 0);
                assertTrue(metrics.nanos().get(AnalysisMetrics.Stage.PREMISES) - before >= elapsed.get());
                fixture.verifyTiming(AnalysisMetrics.Stage.PREMISES);
            } finally {
                if (previous == null) existing.remove("sample/Outer");
                else existing.put("sample/Outer", previous);
            }
        }
    }

    private static URL resource(Path root, String name) {
        try { return root.resolve(name).toUri().toURL(); }
        catch (java.net.MalformedURLException failure) { throw new AssertionError(failure); }
    }

    private static java.security.ProtectionDomain delayedSource(Path root, org.junit.jupiter.api.function.Executable action,
            boolean invalid) throws Exception {
        String location = invalid ? "file:/invalid?query" : root.toUri().toString();
        URL url = new URL(null, location, new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(URL resource) { throw new UnsupportedOperationException(); }
            @Override protected String toExternalForm(URL resource) {
                try { action.execute(); }
                catch (Throwable failure) { throw new AssertionError(failure); }
                return location;
            }
        });
        return new java.security.ProtectionDomain(new java.security.CodeSource(url, (java.security.cert.Certificate[]) null), null);
    }

    private static void checkDefinition(ClassLoader loader, java.security.ProtectionDomain domain) throws Exception {
        var method = TypedOverwriteRuntime.class.getDeclaredMethod("checkDefinition", String.class, ClassLoader.class,
                java.security.ProtectionDomain.class);
        method.setAccessible(true);
        try { method.invoke(null, "sample/Outer", loader, domain); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            throw new AssertionError(wrapped.getCause());
        }
    }

    private static Object invoke(String name, String... arguments) throws Exception {
        Class<?>[] types = new Class<?>[arguments.length];
        java.util.Arrays.fill(types, String.class);
        var method = TypedOverwriteRuntime.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, (Object[]) arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(wrapped.getCause());
        }
    }

    private static void checkSource(String name) throws Exception { invoke("checkSource", name); }

    private static Field field(String name) throws Exception {
        var field = TypedOverwriteRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        final FakeKnot loader;
        final URL changed;
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Set<String> frozenInputs;
        final Set<String> frozenBefore;
        final Logger logger = Logger.getLogger("love.crysh.mixinta.runtime");
        final Level level = logger.getLevel();
        final boolean parentHandlers = logger.getUseParentHandlers();
        boolean failLogger;
        final Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                events.add(record.getMessage());
                if (failLogger && record.getMessage().equals("MixinTA failure retained")) {
                    throw new IllegalStateException("failure logger rejected event");
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };

        Fixture(Path directory) throws Exception {
            @SuppressWarnings("unchecked")
            var frozen = (Set<String>) field("FROZEN_INPUTS").get(null);
            frozenInputs = frozen;
            frozenBefore = Set.copyOf(frozen);
            loader = new FakeKnot(directory);
            changed = directory.resolve("changed/sample/Outer.class").toUri().toURL();
            loader.resolve = name -> changed;
            var sources = new KnotClassSources(loader);
            var state = sources.state();
            Map<String, ClassSourceIndex.Source> inputs = new LinkedHashMap<>();
            for (String name : List.of("sample/Outer", "sample/Inner")) {
                inputs.put(name, new ClassSourceIndex.Source(name, directory.resolve(name + ".class").toUri().toURL(),
                        directory, loader, ClassSourceIndex.Status.DISCOVERED));
            }
            set("classSources", sources);
            set("sourceState", state);
            set("sourceIndex", new ClassSourceIndex(inputs));
            set("premises", new BatchPremises(null, new Object(), state,
                    new MixinDiscovery.ConfigurationSnapshot(new Object(), List.of(), List.of(), Map.of()), inputs));
            set("failure", null);
            set("owner", null);
            set("batchStarted", System.nanoTime());
            set("completionReported", false);
            set("failureReported", false);
            set("inputMetricsRecorded", false);
            set("activeChecks", 0);
            Field phase = field("phase");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object ready = Enum.valueOf((Class) phase.getType(), "READY");
            set("phase", ready);
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
        }

        void set(String name, Object value) throws Exception {
            Field target = field(name);
            if (!saved.containsKey(target)) saved.put(target, target.get(null));
            target.set(null, value);
        }

        List<String> metrics() {
            return events.stream().filter(event -> event.startsWith("MixinTA metrics ")).toList();
        }

        void verifyFailure(Throwable cause) throws Exception {
            for (int attempt = 0; attempt < 2; attempt++) {
                var repeated = assertThrows(IllegalStateException.class, TypedOverwriteRuntime::initialize);
                assertSame(cause, repeated.getCause());
            }
            assertEquals("FAILED", field("phase").get(null).toString());
            assertEquals(0, field("activeChecks").get(null));
            assertEquals(1, events.stream().filter(event -> event.equals("MixinTA failure retained")).count());
            assertEquals(1, metrics().size());
            assertTrue(metrics().getFirst().contains(" status=FAILED "));
        }

        void verifyTiming(AnalysisMetrics.Stage stage) throws Exception {
            var metrics = (AnalysisMetrics) field("METRICS").get(null);
            long elapsed = metrics.nanos().getOrDefault(stage, 0L) / 1_000_000;
            assertTrue(metrics().getFirst().contains(" " + stage.name() + "_MS=" + elapsed + " "), metrics().toString());
        }

        @Override public void close() throws Exception {
            logger.removeHandler(handler);
            logger.setLevel(level);
            logger.setUseParentHandlers(parentHandlers);
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
            frozenInputs.clear();
            frozenInputs.addAll(frozenBefore);
            loader.close();
        }
    }

    private static final class Delegate {
        private final Set<Path> codeSources;
        private final Map<Path, String[]> allowedPrefixes = Map.of();
        private final Set<Path> validParentCodeSources = Set.of();
        Delegate(Path root) { codeSources = Set.of(root); }
    }

    private static final class FakeKnot extends URLClassLoader {
        private final Delegate delegate;
        volatile Function<String, URL> resolve;
        FakeKnot(Path root) {
            super(new URL[0], null);
            delegate = new Delegate(root);
        }
        public Object getDelegate() { return delegate; }
        public URL findResourceFwd(String name) { return resolve.apply(name); }
        Class<?> define(byte[] bytes, java.security.CodeSource source) {
            return defineClass("sample.Outer", bytes, 0, bytes.length, source);
        }
    }
}
