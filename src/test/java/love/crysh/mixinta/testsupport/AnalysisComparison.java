package love.crysh.mixinta.testsupport;

import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import love.crysh.mixinta.diagnostic.DiagnosticVerbosity;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** 固定测试输入跨独立 JVM 对照，文件身份由完整 class 内容决定。 */
public final class AnalysisComparison {
    private AnalysisComparison() { }

    public static void capture(Map<String, byte[]> originals, Map<String, byte[]> mixins, Map<String, byte[]> after) {
        String root = System.getProperty("mixinta.performance.fixtures");
        if (root == null) return;
        try {
            String id = digest(canonical(hashes(originals)) + canonical(hashes(mixins))
                    + (after == null ? "" : canonical(hashes(after))));
            Path fixture = Path.of(root).resolve(id);
            writeClasses(fixture.resolve("originals"), originals);
            writeClasses(fixture.resolve("mixins"), mixins);
            if (after != null) writeClasses(fixture.resolve("after"), after);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot preserve comparison fixture", failure);
        }
    }

    private static void writeClasses(Path directory, Map<String, byte[]> classes) throws IOException {
        Files.createDirectories(directory);
        for (var entry : classes.entrySet()) {
            Path path = directory.resolve(entry.getKey() + ".class");
            Files.createDirectories(path.getParent());
            Files.write(path, entry.getValue());
        }
    }

    private static Map<String, byte[]> readClasses(Path directory) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - 6), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static Map<String, String> hashes(Map<String, byte[]> classes) {
        Map<String, String> result = new TreeMap<>();
        classes.forEach((name, bytes) -> result.put(name, digest(bytes)));
        return result;
    }

    private static String digest(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof love.crysh.mixinta.model.PropagationEdge edge) {
            return canonical(Map.of("from", edge.fromPoint(), "to", edge.toPoint(), "kind", edge.kind(),
                    "fromText", edge.from(), "toText", edge.to(), "reason", edge.reason()));
        }
        if (value instanceof Set<?> values) return canonical(values.stream().map(AnalysisComparison::canonical).sorted().toList());
        if (value instanceof Map<?, ?> values) {
            Map<String, String> sorted = new TreeMap<>();
            values.forEach((key, item) -> sorted.put(canonical(key), canonical(item)));
            return sorted.toString();
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(canonical(item)));
            return items.toString();
        }
        if (value.getClass().isRecord()) {
            Map<String, String> fields = new TreeMap<>();
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    Object field = component.getAccessor().invoke(value);
                    // anchors 来自无序集合，跨 JVM 对照按完整锚点身份比较。
                    if (component.getName().equals("anchors") && (value instanceof love.crysh.mixinta.model.MigrationEvidence
                            || value instanceof love.crysh.mixinta.diagnostic.MigrationRequirement)) {
                        field = ((List<?>) field).stream().map(AnalysisComparison::canonical).sorted().toList();
                    }
                    fields.put(component.getName(), canonical(field));
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return value.getClass().getName() + fields;
        }
        return value.toString();
    }

    public static void main(String[] arguments) throws IOException {
        Path fixtures = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        Files.createDirectories(output);
        int cases = 0;
        try (Stream<Path> directories = Files.list(fixtures)) {
            for (Path fixture : directories.sorted().toList()) {
                Map<String, Object> result = new TreeMap<>();
                String phase = "prepare";
                boolean published = false;
                try {
                    var prepared = new TypedOverwriteAnalyzer().prepare(readClasses(fixture.resolve("originals")),
                            readClasses(fixture.resolve("mixins")));
                    result.put("prepared", hashes(prepared.mixins()));
                    if (Files.isDirectory(fixture.resolve("after"))) {
                        phase = "analyze";
                        var plan = prepared.analyze(readClasses(fixture.resolve("after")));
                        result.put("modified", plan.modifiedClasses());
                        result.put("retired", plan.retiredMethods());
                        phase = "apply";
                        var full = plan.apply();
                        result.put("full", hashes(full));
                        phase = "applyChanges";
                        var sparse = plan.applyChanges();
                        result.put("sparse", hashes(sparse));
                        for (var entry : sparse.entrySet()) {
                            if (!java.util.Arrays.equals(entry.getValue(), full.get(entry.getKey()))) {
                                throw new IllegalStateException("Sparse output differs for " + entry.getKey());
                            }
                        }
                        published = true;
                        phase = "complete";
                    } else {
                        phase = "prepared";
                    }
                } catch (TypedOverwriteException failure) {
                    result.put("diagnostics", failure.diagnostics());
                    result.put("normal", failure.format(DiagnosticVerbosity.NORMAL));
                    result.put("verbose", failure.format(DiagnosticVerbosity.VERBOSE));
                }
                result.put("phase", phase);
                result.put("published", published);
                Files.writeString(output.resolve(fixture.getFileName() + ".txt"), canonical(result));
                cases++;
            }
        }
        System.out.println("Compared fixtures " + cases);
    }
}
