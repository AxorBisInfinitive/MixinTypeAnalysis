package love.crysh.mixinta.testsupport;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.analysis.container.ContainerOperation;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.diagnostic.DiagnosticVerbosity;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 同一固定输入分别测量新 JVM 的首次分析与受控预热后的时间和主线程分配。 */
public final class AnalysisWorkloadProbe {
    private static volatile Object retained;
    private static String outcome;

    private AnalysisWorkloadProbe() { }

    public static void main(String[] arguments) throws IOException {
        Path fixture = Path.of(arguments[1]);
        int warmup = Integer.parseInt(arguments[2]);
        int iterations = Integer.parseInt(arguments[3]);
        Runnable action = arguments[0].equals("container") ? container(fixture) : analysis(fixture);
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long beforeBytes = allocations.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        action.run();
        long firstNanos = System.nanoTime() - started;
        long firstBytes = allocations.getThreadAllocatedBytes(thread) - beforeBytes;
        for (int index = 0; index < warmup; index++) action.run();
        beforeBytes = allocations.getThreadAllocatedBytes(thread);
        long beforeGc = gcCount();
        started = System.nanoTime();
        for (int index = 0; index < iterations; index++) action.run();
        long elapsed = System.nanoTime() - started;
        long bytes = allocations.getThreadAllocatedBytes(thread) - beforeBytes;
        System.out.printf(Locale.ROOT,
                "{\"firstNs\":%d,\"firstBytes\":%d,\"warmNsPerAnalysis\":%.3f,\"warmBytesPerAnalysis\":%.3f,\"gcCount\":%d,\"outcome\":\"%s\"}%n",
                firstNanos, firstBytes, elapsed / (double) iterations, bytes / (double) iterations, gcCount() - beforeGc, outcome);
    }

    private static Runnable analysis(Path fixture) throws IOException {
        var originals = read(fixture.resolve("originals"));
        var mixins = read(fixture.resolve("mixins"));
        var after = read(fixture.resolve("after"));
        return () -> {
            try {
                retained = new TypedOverwriteAnalyzer().prepare(originals, mixins).analyze(after).apply();
                outcome = "complete";
            } catch (TypedOverwriteException failure) {
                retained = List.of(failure.diagnostics(), failure.format(DiagnosticVerbosity.NORMAL), failure.format(DiagnosticVerbosity.VERBOSE));
                outcome = "diagnostics-" + failure.diagnostics().size();
            }
        };
    }

    private static Runnable container(Path fixture) throws IOException {
        Map<String, ClassNode> nodes = new TreeMap<>();
        read(fixture).forEach((name, bytes) -> nodes.put(name, ClassFiles.read(bytes)));
        int groupCount = Integer.parseInt(Files.readString(fixture.resolve("groups.txt")));
        List<List<ContainerContract>> groups = new ArrayList<>();
        groups.add(List.of());
        for (int index = 1; index < groupCount; index++) {
            groups.add(List.of(new ContainerContract("box" + index, 1,
                    new MethodKey("external/Box" + index, "<init>", "()V"), new ContainerSummary(ContainerOperation.ALLOCATE, 0, -1))));
        }
        return () -> {
            var analyses = ContainerAnalysis.analyzeAll(nodes, Set.of(),
                    new ClassHierarchy(nodes, AnalysisWorkloadProbe.class.getClassLoader()), groups, new AnalysisMetrics());
            if (analyses.values().stream().anyMatch(analysis -> !analysis.failures().isEmpty())) {
                throw new IllegalStateException("Container workload failed");
            }
            retained = analyses;
            outcome = "complete";
        };
    }

    private static Map<String, byte[]> read(Path directory) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
                String name = directory.relativize(path).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - 6), Files.readAllBytes(path));
            }
        }
        return classes;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }
}
