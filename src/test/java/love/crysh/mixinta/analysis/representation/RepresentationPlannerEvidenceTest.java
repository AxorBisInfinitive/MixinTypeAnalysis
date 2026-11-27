package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.analysis.container.ContainerOperation;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.plan.RepresentationPlan;
import love.crysh.mixinta.analysis.plan.RepresentationPlanMerger;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RepresentationPlannerEvidenceTest {
    private static final String POSITION = "package sample; public record BlockPos(long value) { public long asLong() { return value; } }";
    private static final String PAIR = """
            package sample;
            public final class Pair<A,B> {
                private A a; private B b;
                public Pair() {}
                public void a(A value) { a=value; }
                public A a() { return a; }
                public void b(B value) { b=value; }
                public B b() { return b; }
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"source", "read", "encode", "observation"})
    void reusedBridgesKeepEveryUseAndArgumentIdentity(String scenario) {
        String body = switch (scenario) {
            case "read" -> """
                    java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                    q.add(new BlockPos(1).asLong()); q.add(new BlockPos(2).asLong());
                    return Subject.lookup(q.poll()) + Subject.lookup(q.poll());
                    """;
            case "encode" -> """
                    long value = new BlockPos(3).asLong();
                    return Subject.lookup(value) + (int)Subject.legacy(value, value);
                    """;
            case "observation" -> """
                    java.util.Map<Long,Long> q = new java.util.HashMap<>();
                    long value = new BlockPos(3).asLong(); q.put(1L, value);
                    boolean first = q.containsValue(value), second = q.containsValue(value);
                    return Subject.lookup(q.get(1L)) + (first && second ? 1 : 0);
                    """;
            default -> "return Subject.lookup(new BlockPos(1).asLong()) + Subject.lookup(new BlockPos(2).asLong());";
        };
        var sample = analyze(body, List.of("lookup"), Map.of(), List.of());
        var plan = sample.plans().getFirst();
        String helper = scenario.equals("source") ? "keep" : scenario.equals("read") ? "read" : "encode";
        var bridge = plan.bridges().stream().filter(value -> value.helper().name().equals(helper)).findFirst().orElseThrow();
        List<MigrationEvidence> expected = new ArrayList<>();
        plan.edits().stream().filter(edit -> bridge.method().equals(edit.helper())).forEach(edit -> expected.addAll(edit.contributors()));
        plan.arguments().stream().filter(argument -> bridge.method().equals(argument.encoder())).forEach(argument -> expected.addAll(argument.contributors()));

        assertTrue(expected.size() >= 2);
        assertEquals(MigrationEvidence.merge(List.of(expected)), bridge.contributors());
        if (scenario.equals("encode")) {
            assertEquals(Set.of("parameter=0", "parameter=1"), bridge.contributors().stream().flatMap(value -> value.anchors().stream())
                    .map(ProgramPoint::detail).collect(Collectors.toSet()));
        }
        assertEquals(1, plan.changes().stream().filter(change -> change.method().equals(bridge.method())).count());
        assertCanonical(bridge.contributors());
        var alternative = new RepresentationPlan.Bridge(bridge.method(), new MethodKey("sample/Subject", "other", bridge.helper().descriptor()),
                new MigrationEvidence("other", new MethodKey("sample/Subject", "other", "(J)I"), 0, "Lsample/BlockPos;", List.of()));
        var conflict = RepresentationPlanMerger.merge(List.of(plan, new RepresentationPlan(List.of(), List.of(), List.of(), List.of(alternative), List.of(), List.of())), sample.classes());
        var diagnostic = RepresentationDiagnostics.planConflicts(conflict.conflicts()).getFirst();
        assertEquals(bridge.contributors().getFirst().anchors(), diagnostic.migrations().stream()
                .filter(value -> value.id().equals(bridge.contributors().getFirst().id())).findFirst().orElseThrow().anchors());
    }

    @Test
    void sharedBridgeRetainsEachDeclarationsEvidence() {
        var sample = analyze("return Subject.first(new BlockPos(1).asLong()) + Subject.second(new BlockPos(2).asLong());",
                List.of("first", "second"), Map.of(), List.of());
        var merged = sample.merged();
        var bridge = merged.bridges().getFirst();
        assertEquals(2, bridge.contributors().size());
        assertEquals(Set.of("first", "second"), bridge.contributors().stream().map(value -> value.target().name()).collect(Collectors.toSet()));
        assertEquals(MigrationEvidence.merge(sample.plans().stream().flatMap(plan -> plan.edits().stream())
                .filter(edit -> bridge.method().equals(edit.helper())).map(RepresentationPlan.Edit::contributors).toList()), bridge.contributors());
        assertEquals(merged, RepresentationPlanMerger.merge(sample.plans().reversed(), sample.classes()).plan().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localSignatureEvidenceIncludesEveryChangedCustomChannel(boolean reassigned) {
        List<ContainerContract> contracts = new ArrayList<>();
        contracts.add(contract("<init>", "()V", ContainerOperation.ALLOCATE, 0, -1));
        for (int channel = 0; channel < 2; channel++) {
            String method = channel == 0 ? "a" : "b";
            contracts.add(contract(method, "(Ljava/lang/Object;)V", ContainerOperation.WRITE, channel, 0));
            contracts.add(contract(method, "()Ljava/lang/Object;", ContainerOperation.READ, channel, -1));
        }
        var sample = analyze(reassigned ? """
                Pair<Long,Long> pair = new Pair<>(); pair.a(new BlockPos(1).asLong());
                int first = Subject.lookup(pair.a());
                pair = new Pair<>(); pair.b(new BlockPos(2).asLong());
                return first + Subject.lookup(pair.b());
                """ : """
                Pair<Long,Long> pair = new Pair<>();
                pair.a(new BlockPos(1).asLong()); pair.b(new BlockPos(2).asLong());
                return Subject.lookup(pair.a()) + Subject.lookup(pair.b());
                """, List.of("lookup"), Map.of("sample.Pair", PAIR), contracts);
        var local = sample.local("pair");
        assertEquals("Lsample/Pair<Lsample/BlockPos;Lsample/BlockPos;>;", local.signature());
        assertEquals(Set.of("channel=0", "channel=1"), details(local.contributors()));
        assertEquals(Set.of("channel=0", "channel=1"), local.contributors().stream().flatMap(value -> value.dependencies().stream())
                .flatMap(edge -> java.util.stream.Stream.of(edge.fromPoint(), edge.toPoint())).map(ProgramPoint::detail)
                .filter(detail -> detail.startsWith("channel=")).collect(Collectors.toSet()));
        assertCanonical(local.contributors());
        var alternative = new RepresentationPlan.Local(local.method(), local.entryIndex(), local.descriptor(),
                "Lsample/Pair<Lsample/OtherPos;Lsample/OtherPos;>;", new MigrationEvidence("other", new MethodKey("sample/Subject", "other", "(J)I"),
                        0, "Lsample/OtherPos;", List.of()));
        var conflict = RepresentationPlanMerger.merge(List.of(sample.merged(), new RepresentationPlan(List.of(), List.of(),
                List.of(alternative), List.of(), List.of(), List.of())), sample.classes());
        assertTrue(conflict.plan().isEmpty());
        var diagnostic = RepresentationDiagnostics.planConflicts(conflict.conflicts()).getFirst();
        assertEquals(local.contributors().getFirst().anchors(), diagnostic.migrations().stream()
                .filter(value -> value.id().equals("lookup")).findFirst().orElseThrow().anchors());
    }

    @Test
    void mapAndDerivedLocalSignaturesKeepThePhysicalValueChannel() {
        var sample = analyze("""
                java.util.Map<Long,Long> q = new java.util.HashMap<>(); q.put(1L, new BlockPos(3).asLong());
                java.util.Set<Long> keys = q.keySet(); java.util.Iterator<Long> keyIterator = keys.iterator();
                java.util.Collection<Long> values = q.values();
                java.util.Iterator<Long> valueIterator = values.iterator();
                java.util.Set<java.util.Map.Entry<Long,Long>> entries = q.entrySet();
                java.util.Iterator<java.util.Map.Entry<Long,Long>> it = entries.iterator();
                java.util.Map.Entry<Long,Long> entry = it.next();
                return Subject.lookup(entry.getValue()) + keyIterator.next().intValue();
                """, List.of("lookup"), Map.of(), List.of());
        for (String name : List.of("q", "values", "valueIterator", "entries", "it", "entry")) {
            var local = sample.local(name);
            assertTrue(local.signature().contains("Lsample/BlockPos;"));
            assertEquals(Set.of("channel=1"), details(local.contributors()), name);
        }
        var run = ClassFiles.findMethod(sample.classes().get("sample/Worker"), "run", "()I");
        for (String name : List.of("keys", "keyIterator")) {
            int entry = run.localVariables.indexOf(run.localVariables.stream().filter(local -> local.name.equals(name)).findFirst().orElseThrow());
            assertTrue(sample.merged().locals().stream().noneMatch(local -> local.method().owner().equals("sample/Worker") && local.entryIndex() == entry));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"primitive", "boxed", "container"})
    void localEvidenceCoversReassignmentsAcrossItsWholeScope(String scenario) {
        String body = scenario.equals("container") ? """
                java.util.Queue<Long> value = new java.util.ArrayDeque<>(); value.add(new BlockPos(1).asLong());
                int first = Subject.lookup(value.poll());
                value = new java.util.ArrayDeque<>(); value.add(new BlockPos(2).asLong());
                return first + Subject.lookup(value.poll());
                """ : """
                %s value = new BlockPos(1).asLong(); int first = Subject.lookup(value);
                value = new BlockPos(2).asLong(); return first + Subject.lookup(value);
                """.formatted(scenario.equals("primitive") ? "long" : "Long");
        var sample = analyze(body, List.of("lookup"), Map.of(), List.of());
        var local = sample.local("value");
        var anchors = local.contributors().stream().flatMap(value -> value.anchors().stream()).toList();
        assertEquals(2, anchors.stream().map(ProgramPoint::instruction).distinct().count());
        assertEquals(2, local.contributors().stream().flatMap(value -> value.dependencies().stream())
                .filter(edge -> edge.kind() == love.crysh.mixinta.model.DependencyKind.TARGET_ARGUMENT)
                .map(edge -> edge.fromPoint().instruction()).distinct().count());
        assertCanonical(local.contributors());
        assertEquals(sample.merged(), RepresentationPlanMerger.merge(sample.plans().reversed(), sample.classes()).plan().orElseThrow());
    }

    private static Set<String> details(List<MigrationEvidence> evidence) {
        return evidence.stream().flatMap(value -> value.anchors().stream()).map(ProgramPoint::detail).collect(Collectors.toSet());
    }

    private static void assertCanonical(List<MigrationEvidence> evidence) {
        for (var value : evidence) {
            assertEquals(value.anchors().stream().distinct().sorted(java.util.Comparator.comparing(ProgramPoint::key)).toList(), value.anchors());
            assertFalse(value.dependencies().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> value.anchors().clear());
        }
    }

    private static ContainerContract contract(String name, String descriptor, ContainerOperation operation, int channel, int parameter) {
        var method = new MethodKey("sample/Pair", name, descriptor);
        return new ContainerContract(method.toString(), 1, method, new ContainerSummary(operation, channel, parameter));
    }

    private record Sample(Map<String, ClassNode> classes, List<RepresentationPlan> plans) {
        RepresentationPlan merged() {
            var result = RepresentationPlanMerger.merge(plans, classes);
            assertTrue(result.conflicts().isEmpty(), result.conflicts().toString());
            return result.plan().orElseThrow();
        }

        RepresentationPlan.Local local(String name) {
            var run = ClassFiles.findMethod(classes.get("sample/Worker"), "run", "()I");
            int entry = run.localVariables.indexOf(run.localVariables.stream().filter(local -> local.name.equals(name)).findFirst().orElseThrow());
            return merged().locals().stream().filter(local -> local.method().owner().equals("sample/Worker") && local.entryIndex() == entry).findFirst().orElseThrow();
        }
    }

    private Sample analyze(String body, List<String> targets, Map<String, String> additional, List<ContainerContract> contracts) {
        Map<String, String> sources = new LinkedHashMap<>(additional);
        sources.put("sample.BlockPos", POSITION);
        sources.put("sample.Worker", "package sample; public class Worker { public static int run() {" + body + "} }");
        String methods = targets.stream().map(name -> "public static int " + name + "(long value) { return (int)value; } public static int "
                + name + "(BlockPos value) { return (int)value.value(); }").collect(Collectors.joining());
        sources.put("sample.Subject", "package sample; public class Subject {" + methods + """
                private static BlockPos keep(BlockPos value) { return value; }
                private static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                private static long encode(BlockPos value) { return value.value(); }
                public static long legacy(long a, long b) { return a + b; }
                }
                """);
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        BytecodeFixtures.compile(sources).forEach((name, bytes) -> classes.put(name, ClassFiles.read(bytes)));
        var hierarchy = new ClassHierarchy(classes, getClass().getClassLoader());
        List<RepresentationRule> rules = List.of(
                new RepresentationRule("keep", 1, TypeRule.Kind.SOURCE, new MethodKey("sample/BlockPos", "asLong", "()J"),
                        new MethodKey("sample/Subject", "keep", "(Lsample/BlockPos;)Lsample/BlockPos;"), TypeRule.Binding.RECEIVER, 0, null),
                new RepresentationRule("read", 1, TypeRule.Kind.READ, null, new MethodKey("sample/Subject", "read", "(Lsample/BlockPos;)Lsample/BlockPos;"), TypeRule.Binding.RECEIVER, 0, null),
                new RepresentationRule("encode", 1, TypeRule.Kind.ENCODE, null, new MethodKey("sample/Subject", "encode", "(Lsample/BlockPos;)J"), TypeRule.Binding.RECEIVER, 0, null));
        var declarations = targets.stream().map(name -> new RepresentationMigration(name, new MethodKey("sample/Subject", name, "(J)I"),
                0, Type.getObjectType("sample/BlockPos"), "sample/Subject", rules, contracts)).toList();
        Map<MethodKey, Migration> migrations = new LinkedHashMap<>();
        declarations.forEach(value -> migrations.put(value.target(), new Migration(value.target(),
                new MethodKey(value.target().owner(), value.target().name(), "(Lsample/BlockPos;)I"), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, List.of(value))));
        var containers = ContainerAnalysis.analyzeAll(classes, migrations.keySet(), hierarchy, List.of(contracts)).get(Set.copyOf(contracts));
        var input = new RepresentationInput(containers);
        var propagation = declarations.stream().map(value -> new RepresentationSolver(List.of(value), migrations, hierarchy).solve(input)).toList();
        var initial = new ElementFacts();
        for (int i = 0; i < declarations.size(); i++) initial.add(List.of(declarations.get(i)), propagation.get(i).facts());
        List<ValidationResult> validated = new ArrayList<>();
        var facts = new ElementFacts();
        for (int i = 0; i < declarations.size(); i++) {
            var value = new RepresentationValidator(declarations.get(i), propagation.get(i), input, migrations, classes, hierarchy, initial).validate();
            assertTrue(value.issues().isEmpty(), value.issues().toString());
            validated.add(value);
            facts.add(List.of(declarations.get(i)), value.facts());
        }
        List<RepresentationPlan> plans = new ArrayList<>();
        for (int i = 0; i < declarations.size(); i++) plans.add(new RepresentationPlanner(List.of(declarations.get(i)), input,
                validated.get(i), migrations, classes, hierarchy, facts).plan());
        return new Sample(classes, List.copyOf(plans));
    }
}
