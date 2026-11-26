package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedBatchBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"Long", "long", "transfer"})
    void combinesCompatibleCrossDeclarationBoundaries(String shape) throws Exception {
        String body = shape.equals("transfer") ? """
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(new BlockPos(12).asLong()); b.add(a.peek());
                return first(a.peek()) + second(b.peek());
                """ : """
                %s value = new BlockPos(12).asLong();
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(value); b.add(value);
                return first(a.peek()) + second(b.peek());
                """.formatted(shape);
        for (boolean reverse : new boolean[]{false, true}) {
            var fixture = fixture(body, "BlockPos", "RulesA", reverse);
            var prepared = fixture.prepare();
            var input = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
            var before = ClassFiles.snapshot(input);
            var plan = prepared.analyze(input);
            var output = plan.apply();
            var repeated = plan.apply();
            output.forEach((name, bytes) -> assertArrayEquals(bytes, repeated.get(name)));
            before.forEach((name, bytes) -> assertArrayEquals(bytes, input.get(name)));
            BytecodeFixtures.verify(output);
            var loader = BytecodeFixtures.loader(output);
            assertEquals(24L, loader.loadClass("sample.Subject").getMethod("run").invoke(null));
            assertEquals(1, loader.loadClass("sample.RulesA").getField("sources").getInt(null));
            var run = ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "run", "()J");
            assertFalse(Arrays.stream(run.instructions.toArray()).anyMatch(instruction -> instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/Long")));
        }
    }

    @Test
    void doesNotRequireEncodingForBoxingRemovedByAnotherDeclaration() throws Exception {
        var base = fixture("""
                long value = new BlockPos(12).asLong();
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(value); b.add(value);
                return first(a.peek()) + second(b.peek());
                """, "BlockPos", "RulesA", false);
        var originals = new LinkedHashMap<>(base.originals());
        var rules = ClassFiles.read(originals.get("sample/RulesA"));
        rules.methods.stream().filter(method -> method.name.equals("encode")).forEach(method -> method.visibleAnnotations.clear());
        originals.put(rules.name, ClassFiles.write(rules));
        var output = new BytecodeFixtures.Fixture(originals, base.mixins()).transform();
        assertEquals(24L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void preservesSourceRuleScopes() {
        var base = fixture("""
                java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                q.add(new BlockPos(12).asLong());
                return first(q.peek()) + second(q.peek());
                """, "BlockPos", "RulesB", false);
        var originals = new LinkedHashMap<>(base.originals());
        var rules = ClassFiles.read(originals.get("sample/RulesB"));
        rules.methods.stream().filter(method -> method.name.equals("keep")).forEach(method -> method.visibleAnnotations.clear());
        originals.put(rules.name, ClassFiles.write(rules));
        var failure = failure(new BytecodeFixtures.Fixture(originals, base.mixins()));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("MISSING_PRODUCER_RULE")));
    }

    @Test
    void reportsIndependentValueConflictAlongsideStorageConflict() {
        var failure = failure(fixture("""
                java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                q.add(new BlockPos(12).asLong());
                long standalone = new BlockPos(3).asLong();
                return first(q.peek()) + second(q.peek()) + first(standalone) + second(standalone);
                """, "OtherPos", "RulesB", false));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("CONTAINER_FACT_CONFLICT")));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("VALUE_FACT_CONFLICT")));
    }

    @Test
    void leavesStandaloneRuleConflictWithoutUnrelatedStorageContext() {
        var fixture = fixture("""
                java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                q.add(new BlockPos(12).asLong());
                long standalone = new BlockPos(3).asLong();
                return first(q.peek()) + second(q.peek()) + first(standalone) + second(standalone);
                """, "BlockPos", "RulesB", false);
        var subject = ClassFiles.findMethod(ClassFiles.read(fixture.originals().get("sample/Subject")), "run", "()J");
        int secondSource = -1;
        for (int i = 0; i < subject.instructions.size(); i++) {
            if (subject.instructions.get(i) instanceof MethodInsnNode call && call.name.equals("asLong")) secondSource = i;
        }
        int instruction = secondSource;
        var conflict = failure(fixture).diagnostics().stream().filter(d -> d.code().equals("REPRESENTATION_RULE_CONFLICT")
                && d.instruction() == instruction).findFirst().orElseThrow();
        assertTrue(conflict.containers().isEmpty());
    }

    @Test
    void aggregatesSharedMissingRuleAndRetainsRealPaths() {
        var diagnostic = failure(fixture("""
                java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                q.add(12L);
                return first(q.peek()) + second(q.peek());
                """, "BlockPos", "RulesA", false));
        var missing = diagnostic.diagnostics().stream().filter(d -> d.code().equals("MISSING_PRODUCER_RULE")).toList();
        assertEquals(1, missing.size());
        assertEquals(2, missing.getFirst().migrations().size());
        assertRealPath(missing.getFirst());
    }

    @Test
    void recordsUnknownOperationAtItsActualAccess() {
        var failure = failure(fixture("""
                java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                q.add(new BlockPos(12).asLong()); q.toArray();
                return first(q.peek()) + second(q.peek());
                """, "BlockPos", "RulesA", false));
        var unknown = failure.diagnostics().stream().filter(d -> d.code().equals("UNKNOWN_CONTAINER_USE")).findFirst().orElseThrow();
        assertEquals(2, unknown.migrations().size());
        assertEquals(1, unknown.containers().size());
        assertEquals(unknown.instruction(), unknown.containers().getFirst().access().instruction());
        assertEquals("UNKNOWN_OPERATION", unknown.containers().getFirst().operation());
    }

    @Test
    void sharesNullAcrossIndependentRepresentations() throws Exception {
        var output = fixture("""
                Long empty = null;
                java.util.Queue<Long> a = new java.util.LinkedList<>();
                java.util.Queue<Long> b = new java.util.LinkedList<>();
                a.add(new BlockPos(12).asLong()); a.add(empty);
                b.add(new BlockPos(3).asLong()); b.add(empty);
                return first(a.peek()) + second(b.peek());
                """, "OtherPos", "RulesB", false).transform();
        BytecodeFixtures.verify(output);
        assertEquals(15L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void preservesNullCastsAndOrdinaryReferenceUses() throws Exception {
        var output = fixture("""
                Long empty = (Long)(Object)null;
                java.util.Queue<Long> a = new java.util.LinkedList<>();
                java.util.Queue<Long> b = new java.util.LinkedList<>();
                a.add(new BlockPos(12).asLong()); a.add((Long)(Object)empty);
                b.add(new BlockPos(3).asLong()); b.add((Long)(Object)empty);
                return first(a.peek()) + second(b.peek()) + (java.util.Objects.isNull(empty) ? 1 : 0);
                """, "OtherPos", "RulesB", false).transform();
        BytecodeFixtures.verify(output);
        assertEquals(16L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void isolatesMissingSourceWithinOneCustomChannel() {
        String contracts = """
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.<init>()V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.ALLOCATE)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.a(Ljava/lang/Object;)V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.WRITE, parameter=0, channel=0)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.a()Ljava/lang/Object;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.READ, channel=0)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.b(Ljava/lang/Object;)V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.WRITE, parameter=0, channel=1)
                @love.crysh.mixinta.anno.ContainerRule(method="sample.Pair.b()Ljava/lang/Object;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.READ, channel=1)
                """;
        var fixture = TypedBatchRepresentationTest.fixture("""
                Pair<Long,Long> pair = new Pair<>();
                pair.a(new BlockPos(12).asLong()); pair.b(3L);
                return first(pair.a()) + first(pair.b());
                """, "BlockPos", "RulesA", false, Map.of("sample.Pair", """
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
        var missing = failure(fixture).diagnostics().stream().filter(d -> d.code().equals("MISSING_PRODUCER_RULE")).findFirst().orElseThrow();
        assertEquals(1, missing.containers().size());
        assertEquals(1, missing.containers().getFirst().channel());
        assertEquals("UNKNOWN", missing.containers().getFirst().state().name());
        assertEquals("WRITE", missing.containers().getFirst().operation());
        assertRealPath(missing);
    }

    @Test
    void preservesNullReadExceptionAfterCrossDeclarationTransfer() throws Exception {
        var fixture = fixture("""
                Long empty = null;
                java.util.Queue<Long> a = new java.util.LinkedList<>();
                java.util.Queue<Long> b = new java.util.LinkedList<>();
                a.add(empty); b.add(empty);
                return first(a.peek()) + second(b.peek());
                """, "OtherPos", "RulesB", false);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void keepsCallArgumentProofsSeparate() {
        var fixture = TypedBatchRepresentationTest.fixture("""
                java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.add(12L); b.add(new BlockPos(3).asLong());
                return consume(a, b);
                """, "BlockPos", "RulesA", false, Map.of(), "", """
                public static int consume(java.util.Queue<Long> a, java.util.Queue<Long> b) { return first(a.peek()); }
                """);
        var missing = failure(fixture).diagnostics().stream().filter(d -> d.code().equals("MISSING_PRODUCER_RULE")).findFirst().orElseThrow();
        assertEquals(1, missing.containers().size());
        var calls = missing.propagation().stream().filter(edge -> edge.kind() == love.crysh.mixinta.model.DependencyKind.CONTAINER_CALL_ARGUMENT).toList();
        assertFalse(calls.isEmpty());
        assertTrue(calls.stream().noneMatch(edge -> edge.fromPoint().detail().equals("parameter=1") || edge.toPoint().detail().equals("parameter=1")));
    }

    @Test
    void locatesEncodingConflictsAtTheirActualParameters() {
        var failure = failure(fixture("""
                long value = new BlockPos(12).asLong();
                return first(value) + second(value) + legacy(value, 4.0, value);
                """, "BlockPos", "RulesB", false));
        var conflicts = failure.diagnostics().stream().filter(d -> d.code().equals("REPRESENTATION_RULE_CONFLICT")
                && d.message().contains("ENCODE")).toList();
        assertEquals(2, conflicts.size());
        assertEquals(java.util.Set.of(0, 2), conflicts.stream().map(Diagnostic::parameter).collect(java.util.stream.Collectors.toSet()));
        for (var diagnostic : conflicts) {
            assertTrue(diagnostic.failureSymbol().contains("legacy"));
            assertTrue(diagnostic.containers().isEmpty());
            assertEquals(2, diagnostic.migrations().size());
            assertTrue(diagnostic.migrations().stream().allMatch(requirement -> !requirement.dependencies().isEmpty()));
        }
    }

    @Test
    void attachesBothDeclarationsToMetadataPlanConflict() {
        var failure = failure(fixture("""
                long value = new BlockPos(12).asLong();
                int result = first(value);
                value = new BlockPos(3).asLong();
                return result + second(value);
                """, "OtherPos", "RulesB", false));
        var conflict = failure.diagnostics().stream().filter(d -> d.code().equals("REPRESENTATION_PLAN_CONFLICT")).findFirst().orElseThrow();
        assertEquals(2, conflict.migrations().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"java.util.Map<Long,Long> a = new java.util.HashMap<>(); a.put(1L, left);",
            "java.util.List<Long> a = new java.util.ArrayList<>(); a.add(left);"})
    void combinesContainerObservationWithIndependentQueryEncoding(String allocation) throws Exception {
        boolean map = allocation.startsWith("java.util.Map");
        var fixture = fixture("""
                long left = new BlockPos(12).asLong();
                long right = new BlockPos(12).asLong();
                %s
                java.util.Queue<Long> b = new java.util.ArrayDeque<>(); b.add(right);
                int result = first(%s) + second(b.peek());
                return result + (%s ? 1 : 0);
                """.formatted(allocation, map ? "a.get(1L)" : "a.getFirst()",
                        map ? "a.containsValue(b.peek())" : "a.subList(0, 1).contains(b.peek())"), "OtherPos", "RulesB", false);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquals(25L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void combinesObservationWhenOnlyOneDeclarationMigratesItsQuery() throws Exception {
        var output = fixture("""
                java.util.Map<Long,Long> a = new java.util.HashMap<>();
                java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                a.put(1L, new BlockPos(12).asLong()); b.add(new BlockPos(12).asLong());
                int result = first(a.get(1L)) + second(a.get(1L)) + second(b.peek());
                return result + (a.containsValue(b.peek()) ? 1 : 0);
                """, "BlockPos", "RulesA", false).transform();
        BytecodeFixtures.verify(output);
        assertEquals(37L, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }

    @Test
    void retainsDistinctUnresolvedFormalParameters() {
        var fixture = TypedBatchRepresentationTest.fixture("return external(1L, 2L);", "BlockPos", "RulesA", false,
                Map.of(), "", "public static int external(long a, long b) { return first(a) + first(b); }");
        var missing = failure(fixture).diagnostics().stream().filter(d -> d.code().equals("MISSING_PRODUCER_RULE")).toList();
        assertEquals(2, missing.size());
        assertEquals(2, missing.stream().map(d -> d.dependencyPath().getFirst().instruction()).distinct().count());
    }

    private static void assertRealPath(Diagnostic diagnostic) {
        var path = diagnostic.dependencyPath();
        assertFalse(path.isEmpty());
        for (int i = 1; i < path.size(); i++) {
            var previous = path.get(i - 1);
            var next = path.get(i);
            assertTrue(diagnostic.propagation().stream().anyMatch(edge -> edge.fromPoint().instruction() == next.instruction()
                    && edge.fromPoint().symbol().equals(next.symbol()) && edge.toPoint().instruction() == previous.instruction()
                    && edge.toPoint().symbol().equals(previous.symbol())), path.toString());
        }
    }

    private static BytecodeFixtures.Fixture fixture(String body, String type, String rules, boolean reverse) {
        return TypedBatchRepresentationTest.fixture(body, type, rules, reverse);
    }

    private static TypedOverwriteException failure(BytecodeFixtures.Fixture fixture) {
        var prepared = fixture.prepare();
        return assertThrows(TypedOverwriteException.class, () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
    }
}
