package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepresentationPropagationTest {
    private static final String POSITION = "Lsample/BlockPos;";
    private static final MethodKey TARGET = new MethodKey("sample/Subject", "lookup", "(J)I");
    private static final MethodKey IMPLEMENTATION = new MethodKey("sample/Rules", "lookup", "(" + POSITION + ")I");
    private static final MethodKey KEEP = new MethodKey("sample/Rules", "keep", "(" + POSITION + ")" + POSITION);
    private static final MethodKey ENCODE = new MethodKey("sample/Rules", "encode", "(" + POSITION + ")J");
    private static final RepresentationPropagationExperiment.Producer PRODUCER =
            new RepresentationPropagationExperiment.Producer(new MethodKey("sample/BlockPos", "asLong", "()J"), KEEP);
    private static final String RUN_DESCRIPTOR = "(JJZ)J";
    private static final String COMMON = """
            public static int calls;
            public static int order;
            private static BlockPos position(long value) {
                calls++;
                order = order * 10 + (int)(value & 7);
                return new BlockPos(value);
            }
            private static long legacy(int before, long first, double middle, long second, int after) {
                return before + first * 3 + (long)middle + second * 7 + after;
            }
            private static long unknown(long value) { return value; }
            private static void externalWrite(java.util.Queue<Long> queue, long value) { queue.add(value); }
            """;

    @Test
    void experiment1MigratesStraightLineAndActualDescriptors() throws Exception {
        Fixture fixture = fixture("""
                BlockPos blockPos = position(a);
                long pos = blockPos.asLong();
                return lookup(pos);
                """, """
                BlockPos blockPos = position(a);
                BlockPos pos = Rules.keep(blockPos);
                return lookup(pos);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        MethodNode run = run(result.classes());
        assertFalse(calls(run, PRODUCER.original()));
        assertTrue(calls(run, KEEP));
        assertTrue(calls(run, new MethodKey("sample/Subject", "lookup", IMPLEMENTATION.descriptor())));
        assertTrue(Arrays.stream(run.instructions.toArray()).anyMatch(instruction -> instruction instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.ASTORE && variable.var >= 5));
        assertNull(ClassFiles.findMethod(ClassFiles.read(result.classes().get("sample/Subject")), "lookup", TARGET.descriptor()));
        assertTrue(result.changes().stream().anyMatch(change -> change.action().equals("replace-producer")));
        assertTrue(result.changes().stream().anyMatch(change -> change.action().equals("migrate-invocation")));
    }

    @Test
    void experiment1EvaluatesReceiverOnceAndPreservesNullFailure() throws Exception {
        Fixture fixture = fixture("""
                BlockPos blockPos = branch ? null : position(a);
                long pos = blockPos.asLong();
                return lookup(pos);
                """, """
                BlockPos blockPos = branch ? null : position(a);
                BlockPos pos = Rules.keep(blockPos);
                return lookup(pos);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        Outcome failure = execute(result.classes(), 12, 3, true);
        assertEquals("java.lang.NullPointerException", failure.failure);
        assertEquals(0, failure.calls);
        assertEquals(1, execute(result.classes(), 12, 3, false).calls);
    }

    @Test
    void experiment1ReportsMissingProducerWithoutMutatingInput() {
        Fixture fixture = fixture("long pos = position(a).asLong(); return lookup(pos);",
                "BlockPos pos = Rules.keep(position(a)); return lookup(pos);");
        Map<String, byte[]> snapshot = ClassFiles.snapshot(fixture.original);
        var missing = new RepresentationPropagationExperiment.Rules(TARGET, IMPLEMENTATION, List.of(), KEEP, ENCODE, true);
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(missing));
        assertDiagnostic(failure, "MISSING_PRODUCER_RULE", "lookup", "asLong");
        snapshot.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.original.get(name)));
    }

    @Test
    void experiment2UnifiesBranchOriginsAndAdaptsLegacyUses() throws Exception {
        Fixture fixture = fixture("""
                long pos;
                if (branch) pos = position(a).asLong();
                else pos = position(b).asLong();
                int result = lookup(pos);
                return result + legacy(1, pos, 2.5, pos, 3);
                """, """
                BlockPos pos;
                if (branch) pos = Rules.keep(position(a));
                else pos = Rules.keep(position(b));
                int result = lookup(pos);
                return result + legacy(1, Rules.encode(pos), 2.5, Rules.encode(pos), 3);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        assertEquals(2, invocationCount(run(result.classes()), KEEP));
        assertEquals(2, invocationCount(run(result.classes()), ENCODE));
        assertTrue(result.changes().stream().anyMatch(change -> change.action().equals("encode-arguments")));
    }

    @Test
    void experiment2TerminatesThroughLoopAndPreservesEvaluationOrder() throws Exception {
        Fixture fixture = fixture("""
                long pos = position(a).asLong();
                for (int i=0; i<3; i++) {
                    if (branch) pos = position(b + i).asLong();
                    else pos = position(a + i).asLong();
                }
                return lookup(pos) + legacy(1, pos, 2.5, pos, 3);
                """, """
                BlockPos pos = Rules.keep(position(a));
                for (int i=0; i<3; i++) {
                    if (branch) pos = Rules.keep(position(b + i));
                    else pos = Rules.keep(position(a + i));
                }
                return lookup(pos) + legacy(1, Rules.encode(pos), 2.5, Rules.encode(pos), 3);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        assertEquals(4, execute(result.classes(), 12, 3, true).calls);
    }

    @Test
    void experiment2ReportsUnresolvedBranchAlongItsDemandPath() {
        Fixture fixture = fixture("""
                long pos = branch ? position(a).asLong() : unknown(b);
                return lookup(pos);
                """, "return lookup(Rules.keep(position(a)));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "MISSING_PRODUCER_RULE", "lookup", "unknown");
        assertEquals(1, failure.diagnostics().size());
    }

    @Test
    void experiment2ReportsMissingBoundaryEncoder() {
        Fixture fixture = fixture("""
                long pos = position(a).asLong();
                return lookup(pos) + legacy(1,pos,2.5,pos,3);
                """, "return lookup(Rules.keep(position(a)));");
        var missing = new RepresentationPropagationExperiment.Rules(TARGET, IMPLEMENTATION, List.of(PRODUCER), KEEP, null, true);
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(missing));
        assertDiagnostic(failure, "MISSING_BOUNDARY_CONVERSION", "run", "Legacy");
    }

    @Test
    void experiment2KeepsUnrelatedLongsAndReusedLocalSlotsValid() throws Exception {
        Fixture fixture = fixture("""
                int result;
                { long pos = position(a).asLong(); result = lookup(pos); }
                { long timestamp = b; result += (int)legacy(1,timestamp,2.5,timestamp,3); }
                return result;
                """, """
                int result;
                { BlockPos pos = Rules.keep(position(a)); result = lookup(pos); }
                { long timestamp = b; result += (int)legacy(1,timestamp,2.5,timestamp,3); }
                return result;
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        assertEquals(0, invocationCount(run(result.classes()), ENCODE));
    }

    @Test
    void experiment3MigratesQueueBoxingUnboxingAndPollDescriptor() throws Exception {
        Fixture fixture = queueFixture("""
                long pos = position(a).asLong();
                queue.add(pos);
                long next = queue.poll();
                return lookup(next);
                """, """
                BlockPos pos = Rules.keep(position(a));
                queue.add(pos);
                BlockPos next = Rules.keep(queue.poll());
                return lookup(next);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        MethodNode run = run(result.classes());
        assertFalse(calls(run, new MethodKey("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")));
        assertFalse(calls(run, new MethodKey("java/lang/Long", "longValue", "()J")));
        assertTrue(calls(run, new MethodKey("java/util/Queue", "poll", "()Ljava/lang/Object;")));
        assertTrue(Arrays.stream(run.instructions.toArray()).anyMatch(instruction -> instruction instanceof TypeInsnNode cast
                && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals("sample/BlockPos")));
        assertTrue(run.localVariables.stream().anyMatch(variable -> variable.name.equals("next") && variable.desc.equals(POSITION)));
        assertTrue(run.localVariables.stream().anyMatch(variable -> variable.name.equals("queue")
                && variable.signature.equals("Ljava/util/Queue<" + POSITION + ">;")));
        assertTrue(result.changes().stream().anyMatch(change -> change.action().equals("remove-boxing")));
        assertTrue(result.changes().stream().anyMatch(change -> change.action().equals("replace-unboxing")));
    }

    @Test
    void experiment3IncludesEveryWriteAndLegacyReadFromSelectedStorage() throws Exception {
        Fixture fixture = queueFixture("""
                queue.add(position(a).asLong());
                queue.add(position(b).asLong());
                long next = queue.poll();
                long other = queue.poll();
                return lookup(next) + legacy(1,other,2.5,other,3);
                """, """
                queue.add(Rules.keep(position(a)));
                queue.add(Rules.keep(position(b)));
                BlockPos next = Rules.keep(queue.poll());
                BlockPos other = Rules.keep(queue.poll());
                return lookup(next) + legacy(1,Rules.encode(other),2.5,Rules.encode(other),3);
                """);
        assertMatches(fixture, fixture.transform(rules()));
    }

    @Test
    void experiment3PreservesEmptyQueueUnboxingFailure() throws Exception {
        Fixture fixture = queueFixture("""
                if (branch) queue.add(position(a).asLong());
                long next = queue.poll();
                return lookup(next);
                """, """
                if (branch) queue.add(Rules.keep(position(a)));
                BlockPos next = Rules.keep(queue.poll());
                return lookup(next);
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        assertEquals("java.lang.NullPointerException", execute(result.classes(), 12, 3, false).failure);
    }

    @Test
    void experiment3KeepsUnrelatedQueueInItsOriginalRepresentation() throws Exception {
        Fixture fixture = queueFixture("""
                java.util.Queue<Long> unrelated = new java.util.ArrayDeque<>();
                unrelated.add(b);
                queue.add(position(a).asLong());
                return lookup(queue.poll()) + unrelated.poll();
                """, """
                java.util.Queue<Long> unrelated = new java.util.ArrayDeque<>();
                unrelated.add(b);
                queue.add(Rules.keep(position(a)));
                return lookup(Rules.keep(queue.poll())) + unrelated.poll();
                """);
        var result = fixture.transform(rules());
        assertMatches(fixture, result);
        assertEquals(1, invocationCount(run(result.classes()), new MethodKey("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")));
        assertEquals(1, invocationCount(run(result.classes()), new MethodKey("java/lang/Long", "longValue", "()J")));
    }

    @Test
    void experiment3ReportsMissingQueueSummary() {
        Fixture fixture = queueFixture("queue.add(position(a).asLong()); return lookup(queue.poll());",
                "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var missing = new RepresentationPropagationExperiment.Rules(TARGET, IMPLEMENTATION, List.of(PRODUCER), KEEP, ENCODE, false);
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(missing));
        assertDiagnostic(failure, "MISSING_CONTAINER_SUMMARY", "lookup", "poll");
    }

    @Test
    void experiment4FollowsAliasesAndMergedAllocationOrigins() throws Exception {
        Fixture fixture = queueFixture("""
                java.util.Queue<Long> second = new java.util.ArrayDeque<>();
                java.util.Queue<Long> alias = branch ? queue : second;
                alias.add(position(a).asLong());
                long next = alias.poll();
                return lookup(next);
                """, """
                java.util.Queue<BlockPos> second = new java.util.ArrayDeque<>();
                java.util.Queue<BlockPos> alias = branch ? queue : second;
                alias.add(Rules.keep(position(a)));
                BlockPos next = Rules.keep(alias.poll());
                return lookup(next);
                """);
        assertMatches(fixture, fixture.transform(rules()));
    }

    @Test
    void experiment4ReportsUnknownWritesThroughAliasesWithPath() {
        Fixture fixture = queueFixture("""
                java.util.Queue<Long> alias = queue;
                queue.add(position(a).asLong());
                alias.add(unknown(b));
                return lookup(queue.poll());
                """, "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "MISSING_PRODUCER_RULE", "lookup", "poll", "add", "unknown");
        assertEquals(1, failure.diagnostics().size());
    }

    @Test
    void experiment4RejectsExternalContainerAccess() {
        Fixture fixture = queueFixture("""
                queue.add(position(a).asLong());
                externalWrite(queue,b);
                return lookup(queue.poll());
                """, "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "UNKNOWN_CONTAINER_USE", "lookup", "poll", "externalWrite");
    }

    @Test
    void experiment4RejectsWritesFromCapturedContainer() {
        Fixture fixture = queueFixture("""
                queue.add(position(a).asLong());
                Runnable writer = () -> queue.add(b);
                writer.run();
                return lookup(queue.poll());
                """, "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "UNKNOWN_CONTAINER_USE", "lookup", "invokedynamic capture");
    }

    @Test
    void experiment1RejectsUnmigratedTargetMethodReference() {
        Fixture fixture = fixture("""
                java.util.function.LongToIntFunction operation = Subject::lookup;
                return operation.applyAsInt(a) + lookup(position(a).asLong());
                """, "return lookup(Rules.keep(position(a)));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "UNSUPPORTED_METHOD_REFERENCE", "run");
    }

    @Test
    void formatsUnresolvedConstraintContextAndSupportsVerboseOutput() {
        Diagnostic diagnostic = new Diagnostic(
                "MISSING_PRODUCER_RULE",
                new MethodKey("sample/Subject", "run", "(JJZ)J"),
                27, -1,
                "Missing source relation sample/BlockPos.asLong()J",
                "sample/Subject.lookup(J)I parameter=0",
                List.of(new PropagationEdge("sample/Subject.lookup(J)I parameter=0",
                        "sample/BlockPos.asLong()J instruction=27", "source")),
                List.of(), TARGET, 0, "J", "Lsample/BlockPos;",
                new RuleRequirement("SOURCE", "RECEIVER", "(BlockPos)BlockPos"),
                List.of(new DependencyStep("sample/BlockPos.asLong()J", 27, "()J"),
                        new DependencyStep("sample/Subject.lookup(J)I", 53, "(J)I")),
                "sample/BlockPos.asLong()J");
        var failure = new TypedOverwriteException(List.of(diagnostic));
        String normal = failure.format(DiagnosticVerbosity.NORMAL);
        assertTrue(normal.startsWith("TypedOverwriteException: 1 unresolved constraints"), normal);
        assertTrue(normal.contains("Target:"), normal);
        assertTrue(normal.contains("parameter 0: long -> BlockPos"), normal);
        assertTrue(normal.contains("[MISSING_PRODUCER_RULE]"), normal);
        assertTrue(normal.contains("Required: SOURCE"), normal);
        assertTrue(normal.contains("Dependency:"), normal);
        assertTrue(normal.endsWith("Analysis failed. No transformations published."), normal);
        String verbose = failure.format(DiagnosticVerbosity.VERBOSE);
        assertTrue(verbose.contains("sample.Subject.lookup(J)I"), verbose);
        assertTrue(verbose.contains("sample.Subject.run(JJZ)J"), verbose);
    }

    @Test
    void experiment4RejectsUnmodeledContainerObservation() {
        Fixture fixture = queueFixture("""
                queue.add(position(a).asLong());
                boolean found = queue.contains(a);
                return lookup(queue.poll()) + (found ? 1 : 0);
                """, "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var failure = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertDiagnostic(failure, "UNKNOWN_CONTAINER_USE", "lookup", "contains");
    }

    @Test
    void experiment4AggregatesIndependentRootsDeterministically() {
        Fixture fixture = queueFixture("""
                java.util.Queue<Long> alias=queue;
                alias.add(unknown(a));
                alias.add(unknown(b));
                externalWrite(alias,a);
                return lookup(queue.poll());
                """, "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        var first = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        var second = assertThrows(TypedOverwriteException.class, () -> fixture.transform(rules()));
        assertEquals(first.diagnostics(), second.diagnostics());
        assertEquals(3, first.diagnostics().size());
        assertEquals(2, first.diagnostics().stream().filter(problem -> problem.code().equals("MISSING_PRODUCER_RULE")).count());
        assertDiagnostic(first, "UNKNOWN_CONTAINER_USE", "externalWrite");
    }

    @Test
    void experimentResultsAreRepeatableAndOwnTheirSnapshots() throws Exception {
        Fixture fixture = queueFixture("queue.add(position(a).asLong()); return lookup(queue.poll());",
                "queue.add(Rules.keep(position(a))); return lookup(Rules.keep(queue.poll()));");
        Map<String, byte[]> before = ClassFiles.snapshot(fixture.original);
        var first = fixture.transform(rules());
        var second = fixture.transform(rules());
        assertEquals(first.changes(), second.changes());
        first.classes().forEach((name, bytes) -> assertArrayEquals(bytes, second.classes().get(name)));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.original.get(name)));
        first.classes().values().forEach(bytes -> Arrays.fill(bytes, (byte)0));
        assertMatches(fixture, first);
    }

    private static RepresentationPropagationExperiment.Rules rules() {
        return new RepresentationPropagationExperiment.Rules(TARGET, IMPLEMENTATION, List.of(PRODUCER), KEEP, ENCODE, true);
    }

    private static Fixture queueFixture(String original, String expected) {
        String declaration = "java.util.Queue<%s> queue = new java.util.ArrayDeque<>();\n";
        return fixture(declaration.formatted("Long") + original, declaration.formatted("BlockPos") + expected);
    }

    private static Fixture fixture(String original, String expected) {
        return new Fixture(compile(original, false), compile(expected, true));
    }

    private static Map<String, byte[]> compile(String run, boolean target) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("sample.BlockPos", """
                package sample;
                public record BlockPos(long packed) {
                    public long asLong() { return packed; }
                }
                """);
        sources.put("sample.Rules", """
                package sample;
                public final class Rules {
                    public static BlockPos keep(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                    public static long encode(BlockPos value) { return value.asLong(); }
                    public static int lookup(BlockPos value) {
                        long packed=value.packed();
                        return (int)(packed ^ (packed >>> 32));
                    }
                }
                """);
        String lookup = target
                ? "private static int lookup(BlockPos value) { return Rules.lookup(value); }"
                : "private static int lookup(long value) { return (int)(value ^ (value >>> 32)); }";
        sources.put("sample.Subject", "package sample; public final class Subject {\n" + COMMON + lookup
                + "\npublic static long run(long a,long b,boolean branch) {\n" + run + "\n}\n}");
        return BytecodeFixtures.compile(sources);
    }

    private static MethodNode run(Map<String, byte[]> classes) {
        return ClassFiles.findMethod(ClassFiles.read(classes.get("sample/Subject")), "run", RUN_DESCRIPTOR);
    }

    private static boolean calls(MethodNode method, MethodKey key) {
        return invocationCount(method, key) != 0;
    }

    private static long invocationCount(MethodNode method, MethodKey key) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(key.owner()) && call.name.equals(key.name()) && call.desc.equals(key.descriptor())).count();
    }

    private static void assertDiagnostic(TypedOverwriteException failure, String code, String... fragments) {
        assertTrue(failure.diagnostics().stream().anyMatch(problem -> problem.code().equals(code)), failure.getMessage());
        for (String fragment : fragments) {
            assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
        }
    }

    private static void assertMatches(Fixture fixture, RepresentationPropagationExperiment.Result result) throws Exception {
        BytecodeFixtures.verify(result.classes());
        long[][] values = {{12,3}, {0,0}, {-1,17}, {Long.MIN_VALUE,Long.MAX_VALUE}, {0x123456789abcdef0L,-999}};
        for (long[] pair : values) {
            for (boolean branch : List.of(false, true)) {
                Outcome expected = execute(fixture.expected, pair[0], pair[1], branch);
                assertEquals(expected, execute(result.classes(), pair[0], pair[1], branch),
                        "Generated target for " + Arrays.toString(pair) + " branch=" + branch);
                assertEquals(expected, execute(fixture.original, pair[0], pair[1], branch),
                        "Original contract for " + Arrays.toString(pair) + " branch=" + branch);
            }
        }
    }

    private static Outcome execute(Map<String, byte[]> classes, long first, long second, boolean branch) throws Exception {
        Class<?> subject = BytecodeFixtures.loader(classes).loadClass("sample.Subject");
        Object value = null;
        String failure = null;
        try {
            value = subject.getMethod("run", long.class, long.class, boolean.class).invoke(null, first, second, branch);
        } catch (InvocationTargetException exception) {
            failure = exception.getCause().getClass().getName();
        }
        return new Outcome(value, failure, subject.getField("calls").getInt(null), subject.getField("order").getInt(null));
    }

    private record Outcome(Object value, String failure, int calls, int order) {
    }

    private record Fixture(Map<String, byte[]> original, Map<String, byte[]> expected) {
        RepresentationPropagationExperiment.Result transform(RepresentationPropagationExperiment.Rules rules) {
            return RepresentationPropagationExperiment.transform(original, rules);
        }
    }
}
