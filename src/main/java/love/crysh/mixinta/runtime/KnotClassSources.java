package love.crysh.mixinta.runtime;

import net.fabricmc.loader.impl.util.UrlUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** 枚举实际 Knot 来源，资源胜出顺序由类加载器自身决定。 */
final class KnotClassSources {
    record State(Set<Path> roots, Map<Path, List<String>> prefixes, Set<Path> parentSources) { }

    private final Object delegate;
    private final ClassLoader loader;
    private final Method findResource;
    private final Path infrastructure;

    KnotClassSources(ClassLoader loader) throws ReflectiveOperationException, java.net.URISyntaxException {
        this.loader = loader;
        Method getDelegate = loader.getClass().getDeclaredMethod("getDelegate");
        getDelegate.setAccessible(true);
        delegate = getDelegate.invoke(loader);
        findResource = loader.getClass().getDeclaredMethod("findResourceFwd", String.class);
        findResource.setAccessible(true);
        infrastructure = Path.of(KnotClassSources.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    State state() throws ReflectiveOperationException {
        Set<Path> roots = Set.copyOf((Set<Path>) field("codeSources"));
        Map<Path, List<String>> prefixes = new LinkedHashMap<>();
        ((Map<Path, String[]>) field("allowedPrefixes")).forEach((root, values) ->
                prefixes.put(root, List.copyOf(Arrays.asList(values))));
        Set<Path> parents = (Set<Path>) field("validParentCodeSources");
        return new State(roots, Map.copyOf(prefixes), parents == null ? null : Set.copyOf(parents));
    }

    Map<String, URL> classes(State state) throws IOException, ReflectiveOperationException,
            net.fabricmc.loader.impl.util.UrlConversionException {
        Map<String, URL> result = new LinkedHashMap<>();
        index(state).sources().forEach((name, source) -> {
            if (source.status() == ClassSourceIndex.Status.DISCOVERED) result.put(name, source.resource());
        });
        return result;
    }

    ClassSourceIndex index(State state) throws IOException, ReflectiveOperationException,
            net.fabricmc.loader.impl.util.UrlConversionException {
        Set<String> names = new TreeSet<>();
        for (Path root : state.roots().stream().sorted().toList()) {
            if (root.equals(infrastructure)) continue;
            if (Files.isDirectory(root)) {
                try (var paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).map(root::relativize).map(Path::toString)
                            .map(name -> name.replace('\\', '/')).forEach(name -> addClass(names, name));
                }
            } else {
                try (JarFile archive = new JarFile(root.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    archive.versionedStream().filter(entry -> !entry.isDirectory())
                            .forEach(entry -> addClass(names, entry.getName()));
                }
            }
        }
        Map<String, ClassSourceIndex.Source> classes = new LinkedHashMap<>();
        for (String name : names) {
            String resource = name + ".class";
            URL url = (URL) findResource.invoke(loader, resource);
            if (url == null) {
                classes.put(name, new ClassSourceIndex.Source(name, null, null, loader, ClassSourceIndex.Status.MISSING));
                continue;
            }
            Path root = UrlUtil.getCodeSource(url, resource).toAbsolutePath().normalize();
            if (!state.roots().contains(root) || root.equals(infrastructure)) continue;
            List<String> prefixes = state.prefixes().get(root);
            boolean restricted = prefixes != null && prefixes.stream().noneMatch(name.replace('/', '.')::startsWith);
            classes.put(name, new ClassSourceIndex.Source(name, url, root, loader,
                    restricted ? ClassSourceIndex.Status.RESTRICTED : ClassSourceIndex.Status.DISCOVERED));
        }
        return new ClassSourceIndex(classes);
    }

    static byte[] read(URL resource) throws IOException {
        var connection = resource.openConnection();
        connection.setUseCaches(false);
        try (var input = connection.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private Object field(String name) throws ReflectiveOperationException {
        Field field = delegate.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(delegate);
    }

    private static void addClass(Set<String> names, String resource) {
        if (resource.endsWith(".class") && !resource.startsWith("META-INF/")
                && !resource.equals("module-info.class")) {
            names.add(resource.substring(0, resource.length() - 6));
        }
    }
}
