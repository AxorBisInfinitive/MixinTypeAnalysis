package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.DiagnosticVerbosity;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedBatchRepresentationTest {
    private static final String FIRST = """
            @TypedOverwrite(method="first(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=RulesA.class))
            public static int first(BlockPos value) { return (int)value.value(); }
            """;
    private static final String SECOND = """
            @TypedOverwrite(method="second(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=%s.class))
            public static int second(%s value) { return (int)value.value(); }
            """;

    static BytecodeFixtures.Fixture fixture(String body, String secondType, String secondRules, boolean reverse) {
        return fixture(body, secondType, secondRules, reverse, Map.of(), "", "");
    }

    private static BytecodeFixtures.Fixture fixture(String body, String secondType, String secondRules, boolean reverse,
            Map<String, String> additional, String contracts, String members) {
        String rules = """
                package sample;
                import love.crysh.mixinta.anno.TypeRule;
                %s
                public class %s {
                    @TypeRule(value=TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                    public static %s keep(BlockPos value) { sources++; return %s; }
                    public static int sources;
                    @TypeRule(TypeRule.Kind.READ)
                    public static %s read(%s value) { return java.util.Objects.requireNonNull(value); }
                    @TypeRule(TypeRule.Kind.ENCODE)
                    public static long encode(%s value) { return value.value(); }
                }
                """;
        Map<String, String> extra = new LinkedHashMap<>(additional);
        extra.put("sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong(){return value;} }");
        extra.put("sample.OtherPos", "package sample; public record OtherPos(long value) {}");
        extra.put("sample.RulesA", rules.formatted(contracts, "RulesA", "BlockPos", "value", "BlockPos", "BlockPos", "BlockPos"));
        extra.put("sample.RulesB", rules.formatted(contracts, "RulesB", secondType,
                secondType.equals("BlockPos") ? "value" : "new OtherPos(value.value())", secondType, secondType, secondType));
        String second = SECOND.formatted(secondRules, secondType);
        return BytecodeFixtures.fixture("""
                public static int first(long value) { return (int)value; }
                public static int second(long value) { return (int)value; }
                public static long legacy(long a, double b, long c) { return a + (long)b + c; }
                public static long run() {
                """ + body + "}" + members, reverse ? second + FIRST : FIRST + second, extra);
    }

    @Test
    void rejectsIncompatibleSharedStorageDuringAnalysisAndKeepsStableDiagnostics() {
        String body = """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(new BlockPos(12).asLong());
                long next = queue.peek();
                return first(next) + second(next);
                """;
        String formatted = null;
        for (boolean reverse : new boolean[]{false, true}) {
            var fixture = fixture(body, "OtherPos", "RulesB", reverse);
            var prepared = fixture.prepare();
            var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
            var snapshot = ClassFiles.snapshot(merged);
            var failure = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged));
            assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("CONTAINER_FACT_CONFLICT")));
            assertEquals(1, failure.diagnostics().size());
            String message = failure.format(DiagnosticVerbosity.NORMAL);
            assertTrue(message.contains("Storage:"), message);
            assertTrue(message.contains("Channel:"), message);
            assertTrue(message.contains("Operation:"), message);
            assertTrue(message.contains("BlockPos"), message);
            assertTrue(message.contains("OtherPos"), message);
            if (formatted != null) assertEquals(formatted, message);
            formatted = message;
            assertEquals(message, assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged))
                    .format(DiagnosticVerbosity.NORMAL));
            snapshot.forEach((name, bytes) -> assertArrayEquals(bytes, merged.get(name)));
        }
    }

    @Test
    void mergesCompatibleSharedValuesAndExecutesProducerOnlyOnce() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(new BlockPos(12).asLong());
                long next = queue.peek();
                return first(next) + second(next);
                """, "BlockPos", "RulesA", false);
        var prepared = fixture.prepare();
        var plan = prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared));
        var output = plan.apply();
        var repeated = plan.apply();
        output.forEach((name, bytes) -> assertArrayEquals(bytes, repeated.get(name)));
        BytecodeFixtures.verify(output);
        var loader = BytecodeFixtures.loader(output);
        assertEquals(24L, loader.loadClass("sample.Subject").getMethod("run").invoke(null));
        assertEquals(1, loader.loadClass("sample.RulesA").getField("sources").getInt(null));
        var run = ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "run", "()J");
        assertEquals(1, Arrays.stream(run.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("sample/RulesA") && call.name.equals("keep")).count());
    }

    @Test
    void allowsDifferentRepresentationsInIndependentContainersAndCombinesWideArguments() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(new BlockPos(12).asLong());
                b.add(new BlockPos(3).asLong());
                long av = a.peek();
                long bv = b.peek();
                return first(av) + second(bv) + legacy(av, 4.0, bv);
                """, "OtherPos", "RulesB", false);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquals(34L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void rejectsDifferentRulesForTheSamePhysicalValue() {
        var fixture = fixture("""
                long next = new BlockPos(12).asLong();
                return first(next) + second(next);
                """, "BlockPos", "RulesB", false);
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("REPRESENTATION_RULE_CONFLICT")));
    }

    @Test
    void aggregatesConflictsForIndependentSharedContainers() {
        var fixture = fixture("""
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(new BlockPos(12).asLong());
                b.add(new BlockPos(3).asLong());
                return first(a.peek()) + second(a.peek()) + first(b.peek()) + second(b.peek());
                """, "OtherPos", "RulesB", false);
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        assertEquals(2, failure.diagnostics().stream().filter(value -> value.code().equals("CONTAINER_FACT_CONFLICT")).count());
    }

    @Test
    void reportsValueConflictWithoutAContainer() {
        var fixture = fixture("long value = new BlockPos(12).asLong(); return first(value) + second(value);",
                "OtherPos", "RulesB", false);
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("VALUE_FACT_CONFLICT")));
    }

    @Test
    void allowsIndependentEncodingRulesAtDifferentArgumentsOfOneCall() throws Exception {
        var fixture = fixture("""
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(new BlockPos(12).asLong()); b.add(new BlockPos(3).asLong());
                long av = a.peek(); long bv = b.peek();
                return first(av) + second(bv) + legacy(av, 4.0, bv);
                """, "BlockPos", "RulesB", false);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquals(34L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesStablePrimitiveCallLocationsAndEncodesBeforeNumericConversion(boolean encode) throws Exception {
        var base = fixture("""
                long value = new BlockPos(12).asLong();
                return first(value) + second(value) + numeric(%s, 3);
                """.formatted(encode ? "value" : "9L"), "BlockPos", "RulesA", false, Map.of(), "",
                "public static long numeric(long value, int extra) { return value + extra; }");
        var mixins = new LinkedHashMap<>(base.mixins());
        var numeric = BytecodeFixtures.compile(Map.of("sample.NumericMixin", """
                package sample;
                @org.spongepowered.asm.mixin.Mixin(targets="sample.Subject", remap=false)
                public class NumericMixin {
                    @love.crysh.mixinta.anno.TypedOverwrite(method="numeric(JI)J")
                    public static long numeric(double value, double extra) { return (long)(value + extra); }
                }
                """));
        mixins.putAll(numeric);
        var fixture = new BytecodeFixtures.Fixture(base.originals(), mixins);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquals(encode ? 39L : 36L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void convertsPrimitiveAndReferenceParametersInTheSameTargetCall() throws Exception {
        var base = fixture("""
                long value = new BlockPos(12).asLong();
                return first(value) + second(value) + mixed(value, 2);
                """, "BlockPos", "RulesA", false, Map.of(), "",
                "public static int mixed(long value, int extra) { return (int)value + extra; }");
        var classes = BytecodeFixtures.compile(Map.of(
                "sample.BlockPos", "package sample; public record BlockPos(long value) {}",
                "sample.RulesA", "package sample; public class RulesA {}",
                "sample.MixedMixin", """
                package sample;
                @org.spongepowered.asm.mixin.Mixin(targets="sample.Subject", remap=false)
                public class MixedMixin {
                    @love.crysh.mixinta.anno.TypedOverwrite(method="mixed(JI)I",
                        parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=RulesA.class))
                    public static int mixed(BlockPos value, double extra) { return (int)(value.value() + extra); }
                }
                """));
        var mixins = new LinkedHashMap<>(base.mixins());
        mixins.put("sample/MixedMixin", classes.get("sample/MixedMixin"));
        var output = new BytecodeFixtures.Fixture(base.originals(), mixins).transform();
        BytecodeFixtures.verify(output);
        assertEquals(38L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void rejectsIncompatibleRepresentationsWithTheSameIdentity() {
        var base = fixture("return first(new BlockPos(12).asLong()) + second(new BlockPos(3).asLong());",
                "OtherPos", "RulesB", false);
        var mixins = new LinkedHashMap<String, byte[]>();
        base.mixins().forEach((name, bytes) -> {
            var node = ClassFiles.read(bytes);
            node.methods.forEach(method -> {
                var annotation = ClassFiles.annotation(method.visibleAnnotations, ClassFiles.TYPED_OVERWRITE);
                if (annotation == null) return;
                var declaration = (AnnotationNode) ((java.util.List<?>) ClassFiles.value(annotation, "parameters", java.util.List.of())).getFirst();
                declaration.values.add("id");
                declaration.values.add("shared");
            });
            mixins.put(name, ClassFiles.write(node));
        });
        var fixture = new BytecodeFixtures.Fixture(base.originals(), mixins);
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("MIGRATION_ID_CONFLICT")));
    }

    @Test
    void tracksConflictsThroughRecursiveViewsAndEntryValues() {
        var fixture = fixture("""
                java.util.Map<Long,Long> map = new java.util.HashMap<>();
                map.put(1L, new BlockPos(12).asLong());
                java.util.Iterator<java.util.Map.Entry<Long,Long>> it = entries(map, 2);
                long value = it.next().getValue();
                return first(value) + second(value);
                """, "OtherPos", "RulesB", false, Map.of(), "", """
                public static java.util.Iterator<java.util.Map.Entry<Long,Long>> entries(java.util.Map<Long,Long> map, int depth) {
                    return depth == 0 ? map.entrySet().iterator() : entries(map, depth - 1);
                }
                """);
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        var conflict = failure.diagnostics().stream().filter(value -> value.code().equals("CONTAINER_FACT_CONFLICT")).findFirst().orElseThrow();
        assertEquals(1, conflict.containers().getFirst().channel());
        assertEquals(2, conflict.migrations().size());
        assertEquals("CONFLICT", conflict.containers().getFirst().state().name());
        assertThrows(UnsupportedOperationException.class, () -> conflict.containers().clear());
    }

    @Test
    void mergesDifferentCustomChannelsAndTheirGenericSignatures() throws Exception {
        String contracts = """
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.<init>()V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.ALLOCATE)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.a(Ljava/lang/Object;)V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.WRITE, parameter=0, channel=0)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.a()Ljava/lang/Object;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.READ, channel=0)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.b(Ljava/lang/Object;)V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.WRITE, parameter=0, channel=1)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.b()Ljava/lang/Object;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.READ, channel=1)
                """;
        var fixture = fixture("""
                Pair<Long,Long> pair = new Pair<>();
                pair.a(new BlockPos(12).asLong()); pair.b(new BlockPos(3).asLong());
                return first(pair.a()) + second(pair.b());
                """, "OtherPos", "RulesB", false, Map.of("sample.Pair", """
                package sample;
                public final class Pair<A,B> {
                    private A a; private B b;
                    public Pair() {}
                    public void a(A value) { a=value; }
                    public A a() { return a; }
                    public void b(B value) { b=value; }
                    public B b() { return b; }
                }
                """), contracts, "");
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquals(15L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
        var method = ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "run", "()J");
        assertEquals("Lsample/Pair<Lsample/BlockPos;Lsample/OtherPos;>;",
                method.localVariables.stream().filter(local -> local.name.equals("pair")).findFirst().orElseThrow().signature);
    }
}
