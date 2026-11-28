package love.crysh.mixinta.testsupport;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.analysis.container.ContainerOperation;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 缩放输入落盘后由两个版本分别读取，计时只包含完整容器分析。 */
public final class ContainerPerformanceProbe {
    private ContainerPerformanceProbe() { }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[1]);
        if (arguments[0].equals("generate")) {
            generate(root);
            return;
        }
        Path output = Path.of(arguments[2]);
        Files.createDirectories(output);
        try (var paths = Files.list(root)) {
            for (Path fixture : paths.sorted().toList()) run(fixture, output);
        }
    }

    private static void generate(Path root) throws IOException {
        for (int count : new int[]{64, 256, 1024}) {
            String calls = "sink(value);".repeat(count);
            String locals = "";
            for (int index = 0; index < 24; index++) locals += "Object local" + index + "=value;";
            String source = "package sample; public class Subject {"
                    + "public static Object run(Object value){" + locals + calls + "return value;}"
                    + "public static void sink(Object value){} }";
            write(root.resolve("positions-" + count), BytecodeFixtures.compile(Map.of("sample.Subject", source)), 1);
        }
        for (int groups : new int[]{1, 4}) {
            write(root.resolve("contracts-" + groups), BytecodeFixtures.compile(Map.of("sample.Subject", """
                    package sample;
                    public class Subject {
                        public static Object run(Object value){ return pass(value); }
                        public static Object pass(Object value){ return value; }
                    }
                    """)), groups);
        }
        for (int count : new int[]{4, 16, 64, 256}) {
            String branches = "";
            for (int index = 0; index < count; index++) branches += "case " + index + ": value = new java.util.ArrayDeque<>(); break;";
            String source = "package sample; public class Subject {"
                    + "public static Object run(int choice){ Object value; switch(choice){" + branches
                    + "default: value=null;} return recur(value,3);}"
                    + "public static Object recur(Object value,int count){return count==0?value:recur(value,count-1);} }";
            write(root.resolve("references-" + count), BytecodeFixtures.compile(Map.of("sample.Subject", source)), 1);
        }
    }

    private static void write(Path fixture, Map<String, byte[]> classes, int groups) throws IOException {
        Files.createDirectories(fixture);
        for (var entry : classes.entrySet()) {
            Path path = fixture.resolve(entry.getKey() + ".class");
            Files.createDirectories(path.getParent());
            Files.write(path, entry.getValue());
        }
        Files.writeString(fixture.resolve("groups.txt"), Integer.toString(groups));
    }

    private static void run(Path fixture, Path output) throws Exception {
        Map<String, ClassNode> nodes = new TreeMap<>();
        try (var files = Files.walk(fixture)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
                ClassNode node = ClassFiles.read(Files.readAllBytes(path));
                nodes.put(node.name, node);
            }
        }
        int groupCount = Integer.parseInt(Files.readString(fixture.resolve("groups.txt")));
        var groups = new ArrayList<List<ContainerContract>>();
        groups.add(List.of());
        for (int index = 1; index < groupCount; index++) {
            groups.add(List.of(new ContainerContract("box" + index, 1,
                    new MethodKey("external/Box" + index, "<init>", "()V"), new ContainerSummary(ContainerOperation.ALLOCATE, 0, -1))));
        }
        var metrics = new AnalysisMetrics();
        long started = System.nanoTime();
        var analyses = ContainerAnalysis.analyzeAll(nodes, Set.of(), new ClassHierarchy(nodes,
                ContainerPerformanceProbe.class.getClassLoader()), groups, metrics);
        long elapsed = System.nanoTime() - started;
        var facts = new TreeMap<String, Object>();
        var sources = java.util.Collections.newSetFromMap(new IdentityHashMap<love.crysh.mixinta.analysis.flow.MethodFlow, Boolean>());
        long positions = 0;
        long values = 0;
        for (var entry : analyses.entrySet()) {
            var analysis = entry.getValue();
            if (!analysis.failures().isEmpty()) throw new IllegalStateException(analysis.failures().toString());
            String group = entry.getKey().stream().map(Object::toString).sorted().toList().toString();
            facts.put(group + "/summaries", analysis.summaries());
            facts.put(group + "/dependencies", Set.copyOf(analysis.callGraph().dependencies()));
            facts.put(group + "/effects", Set.copyOf(analysis.effects().accesses()));
            for (var flow : analysis.flows()) {
                sources.add(flow.source());
                var identities = new IdentityHashMap<love.crysh.mixinta.analysis.flow.OriginValue, Boolean>();
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                for (int instruction = 0; instruction < flow.source().instructionCount(); instruction++) {
                    if (!flow.source().reachable(instruction)) continue;
                    for (int slot = 0; slot < flow.source().localCount(instruction); slot++) {
                        identities.put(flow.source().local(instruction, slot), true);
                        update(digest, instruction + "/local/" + slot, flow.aliases().local(instruction, slot));
                        positions++;
                    }
                    for (int slot = 0; slot < flow.source().stackSize(instruction); slot++) {
                        identities.put(flow.source().stack(instruction, slot), true);
                        update(digest, instruction + "/stack/" + slot, flow.aliases().stack(instruction, slot));
                        positions++;
                    }
                }
                facts.put(group + "/" + flow.source().key() + "/aliases", java.util.HexFormat.of().formatHex(digest.digest()));
                values += identities.size();
            }
        }
        String name = fixture.getFileName().toString();
        Files.writeString(output.resolve(name + ".facts"), AnalysisComparison.canonical(facts));
        String sample = "case=" + name + " elapsedNs=" + elapsed + " groups=" + analyses.size()
                + " uniqueFlows=" + sources.size() + " positions=" + positions + " values=" + values + metrics.format()
                + " caches=" + AnalysisCacheFootprint.measure(sources, analyses.values());
        Files.writeString(output.resolve(name + ".metrics"), sample);
        System.out.println(sample);
        if (Boolean.getBoolean("mixinta.performance.heap")) {
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
            var process = new ProcessBuilder(jcmd.toString(), Long.toString(ProcessHandle.current().pid()), "GC.class_histogram")
                    .redirectErrorStream(true).redirectOutput(output.resolve(name + ".heap").toFile()).start();
            if (process.waitFor() != 0) throw new IllegalStateException("Heap histogram failed");
        }
        java.lang.ref.Reference.reachabilityFence(analyses);
    }

    private static void update(java.security.MessageDigest digest, String position, Object facts) {
        digest.update((position + "=" + AnalysisComparison.canonical(facts) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
