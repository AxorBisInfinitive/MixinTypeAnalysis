package love.crysh.mixinta.runtime;

import love.crysh.mixinta.testsupport.BytecodeFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class KnotClassSourcesTest {
    @TempDir Path temporary;

    @Test
    void enumeratesDevelopmentRootsUsingTheLoadersActualResourceWinner() throws Exception {
        byte[] bytes = BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {}"))
                .get("sample/Subject");
        Path first = Files.createDirectories(temporary.resolve("z-first"));
        Path second = Files.createDirectories(temporary.resolve("a-second"));
        for (Path root : List.of(first, second)) {
            Files.createDirectories(root.resolve("sample"));
            Files.write(root.resolve("sample/Subject.class"), bytes);
            Files.write(root.resolve("module-info.class"), new byte[] {0});
        }
        try (FakeKnot loader = new FakeKnot(first, second)) {
            var sources = new KnotClassSources(loader);
            var state = sources.state();
            var classes = sources.index(state).sources();
            assertEquals(Set.of("sample/Subject"), classes.keySet());
            assertEquals(first.resolve("sample/Subject.class").toUri().toURL(), classes.get("sample/Subject").resource());
            assertEquals(ClassSourceIndex.Status.DISCOVERED, classes.get("sample/Subject").status());
            loader.delegate.allowedPrefixes.put(first, new String[] {"other."});
            assertNotEquals(state, sources.state());
            var restricted = sources.index(sources.state()).sources().get("sample/Subject");
            assertEquals(ClassSourceIndex.Status.RESTRICTED, restricted.status());
            assertEquals(first.resolve("sample/Subject.class").toUri().toURL(), restricted.resource());
        }
    }

    @Test
    void enumeratesRuntimeSelectedMultiReleaseEntriesOnce() throws Exception {
        byte[] base = BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {public int value(){return 1;}}"))
                .get("sample/Subject");
        byte[] selected = BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {public int value(){return 25;}}"))
                .get("sample/Subject");
        Path jar = temporary.resolve("application.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("sample/Subject.class"));
            output.write(base);
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/versions/25/sample/Subject.class"));
            output.write(selected);
            output.closeEntry();
        }
        try (FakeKnot loader = new FakeKnot(jar)) {
            var sources = new KnotClassSources(loader);
            var classes = sources.index(sources.state()).sources();
            assertEquals(Set.of("sample/Subject"), classes.keySet());
            assertEquals(ClassSourceIndex.Status.DISCOVERED, classes.get("sample/Subject").status());
            assertArrayEquals(selected, KnotClassSources.read(classes.get("sample/Subject").resource()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detectsResourceWinnerChangesEvenWhenRootsAndBytesAreUnchanged(boolean differentBytes) throws Exception {
        byte[] firstBytes = BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {}"))
                .get("sample/Subject");
        byte[] secondBytes = differentBytes
                ? BytecodeFixtures.compile(Map.of("sample.Subject", "package sample; public class Subject {public int value;}"))
                        .get("sample/Subject") : firstBytes;
        Path first = Files.createDirectories(temporary.resolve("first/sample"));
        Path second = Files.createDirectories(temporary.resolve("second/sample"));
        Files.write(first.resolve("Subject.class"), firstBytes);
        Files.write(second.resolve("Subject.class"), secondBytes);
        try (FakeKnot loader = new FakeKnot(first.getParent(), second.getParent())) {
            var sources = new KnotClassSources(loader);
            var state = sources.state();
            var original = sources.index(state).sources();
            Object transformer = new Object();
            var configurations = new MixinDiscovery.ConfigurationSnapshot(new Object(), List.of(), List.of(), Map.of());
            var premises = new BatchPremises(null, transformer, state, configurations, original);
            Files.delete(first.resolve("Subject.class"));
            assertEquals(state, sources.state());
            assertDoesNotThrow(() -> premises.verify(null, transformer, sources.state(), configurations,
                    (code, detail) -> new IllegalStateException(code + " " + detail)));
            var failure = assertThrows(IllegalStateException.class, () -> premises.verifySource("sample/Subject",
                    sources.resolve("sample/Subject", state), (code, detail) -> new IllegalStateException(code + " " + detail)));
            assertTrue(failure.getMessage().contains("CLASS_SOURCE_CHANGED"));
            assertTrue(failure.getMessage().contains(first.getParent().toString()));
            assertTrue(failure.getMessage().contains(second.getParent().toString()));
            Files.delete(second.resolve("Subject.class"));
            assertThrows(IllegalStateException.class, () -> premises.verifySource("sample/Subject",
                    sources.resolve("sample/Subject", state), (code, detail) -> new IllegalStateException(code + " " + detail)));
        }
    }

    private static final class Delegate {
        private final Set<Path> codeSources;
        private final Map<Path, String[]> allowedPrefixes = new LinkedHashMap<>();
        private final Set<Path> validParentCodeSources = Set.of();

        Delegate(Path[] roots) { codeSources = Set.copyOf(List.of(roots)); }
    }

    private static final class FakeKnot extends URLClassLoader {
        private final Delegate delegate;

        FakeKnot(Path... roots) throws java.net.MalformedURLException {
            super(urls(roots), null);
            delegate = new Delegate(roots);
        }

        public Object getDelegate() { return delegate; }
        public URL findResourceFwd(String name) { return super.findResource(name); }

        private static URL[] urls(Path[] roots) throws java.net.MalformedURLException {
            URL[] urls = new URL[roots.length];
            for (int index = 0; index < roots.length; index++) urls[index] = roots[index].toUri().toURL();
            return urls;
        }
    }
}
