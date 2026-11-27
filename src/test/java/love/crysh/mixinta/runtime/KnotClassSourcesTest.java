package love.crysh.mixinta.runtime;

import love.crysh.mixinta.testsupport.BytecodeFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            var classes = sources.classes(state);
            assertEquals(Set.of("sample/Subject"), classes.keySet());
            assertEquals(first.resolve("sample/Subject.class").toUri().toURL(), classes.get("sample/Subject"));
            loader.delegate.allowedPrefixes.put(first, new String[] {"other."});
            assertNotEquals(state, sources.state());
            assertTrue(sources.classes(sources.state()).isEmpty());
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
            var classes = sources.classes(sources.state());
            assertEquals(Set.of("sample/Subject"), classes.keySet());
            assertArrayEquals(selected, KnotClassSources.read(classes.get("sample/Subject")));
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
