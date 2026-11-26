package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;
import java.util.Map;
import java.util.Arrays;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import static love.crysh.mixinta.analysis.TypedContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedContainerCallGraphTest {
    record Program(String body, String members) {
    }

    static Stream<Program> programs() {
        return Stream.of(
                new Program("java.util.Queue<Long> q = create(); q.add(position(12).asLong()); return lookup(q.poll());",
                        "public static java.util.Queue<Long> create() { return new java.util.ArrayDeque<>(); }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return lookup(((java.util.Queue<Long>) identity(q)).poll());",
                        "public static Object identity(Object value) { return value; }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return lookup(q.poll()) + observe(q);",
                        "public static int observe(java.util.Queue<Long> q) { return q.size(); }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); forward(q, position(12)); return read(q);", """
                        public static void forward(java.util.Queue<Long> q, BlockPos p) { write(q, p); }
                        public static void write(java.util.Queue<Long> q, BlockPos p) { q.add(p.asLong()); }
                        public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return lookup(recurse(q, flag ? 2 : 3).poll());",
                        "public static java.util.Queue<Long> recurse(java.util.Queue<Long> q, int n) { return n == 0 ? q : recurse(q, n - 1); }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return lookup(first(q, flag ? 2 : 3).poll());", """
                        public static java.util.Queue<Long> first(java.util.Queue<Long> q, int n) { return n == 0 ? q : second(q, n - 1); }
                        public static java.util.Queue<Long> second(java.util.Queue<Long> q, int n) { return n == 0 ? q : first(q, n - 1); }
                        """),
                new Program("java.util.Queue<Long> q = create(flag ? 2 : 3); q.add(position(12).asLong()); return lookup(q.poll());",
                        "public static java.util.Queue<Long> create(int n) { return n == 0 ? new java.util.ArrayDeque<>() : create(n - 1); }"),
                new Program("java.util.Queue<Long> a = new java.util.ArrayDeque<>(); java.util.Queue<Long> b = new java.util.LinkedList<>(); write(a, position(12)); write(b, position(3)); return read(a) + read(b);", """
                        public static void write(java.util.Queue<Long> q, BlockPos p) { q.add(p.asLong()); }
                        public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); int value = lookup(q.peek()); return value + oldRead(q);",
                        "public static long oldRead(java.util.Queue<Long> q) { return q.poll(); }"),
                new Program("java.util.Map<Long, Long> q = create(); write(q, position(12)); return lookup(q.get(7L));", """
                        public static java.util.Map<Long, Long> create() { return new java.util.HashMap<>(); }
                        public static void write(java.util.Map<Long, Long> q, BlockPos p) { q.put(7L, p.asLong()); }
                        """),
                new Program("java.util.List<Long> q = new java.util.ArrayList<>(); q.add(position(12).asLong()); replace(q, position(3)); return lookup(q.get(0));",
                        "public static void replace(java.util.List<Long> q, BlockPos p) { q.set(0, p.asLong()); }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); Utility.write(q, position(12)); return Utility.read(q);", """
                        public static class Utility {
                            public static void write(java.util.Queue<Long> q, BlockPos p) { q.add(p.asLong()); }
                            public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }
                        }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return new Service().read(q);",
                        "public static final class Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }"),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service s = flag ? new First() : new Second(); return s.read(q);", """
                        public interface Service { int read(java.util.Queue<Long> q); }
                        public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        public static class Second implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()) + 1; } }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return createService().read(q);", """
                        public interface Service { int read(java.util.Queue<Long> q); }
                        public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        public static Service createService() { return new First(); }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Base s = flag ? new Child() : new Override(); return s.read(q);", """
                        public static class Base { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        public static class Child extends Base {}
                        public static class Override extends Base { public int read(java.util.Queue<Long> q) { return lookup(q.poll()) + 1; } }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service s = flag ? new First() : null; return s.read(q);", """
                        public interface Service { int read(java.util.Queue<Long> q); }
                        public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return new Service().invoke(q);", """
                        public static class Service {
                            public final int invoke(java.util.Queue<Long> q) { return read(q); }
                            private int read(java.util.Queue<Long> q) { return lookup(q.poll()); }
                        }
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service service = new Implementation(); return service.read(q);", """
                        public interface Service { default int read(java.util.Queue<Long> q) { return q.size(); } }
                        public interface Refined extends Service { default int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        public static class Implementation implements Service, Refined {}
                        """),
                new Program("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service service = new Implementation(); return service.read(q);", """
                        public interface Service { default int read(java.util.Queue<Long> q) { return q.size(); } }
                        public static class Base { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                        public static class Implementation extends Base implements Service {}
                        """),
                new Program("java.util.Queue<Long> q = nullable(flag); if (q == null) return 0; q.add(position(12).asLong()); return lookup(q.poll());",
                        "public static java.util.Queue<Long> nullable(boolean flag) { return flag ? new java.util.ArrayDeque<>() : null; }")
        );
    }

    @ParameterizedTest
    @MethodSource("programs")
    void executesContainerParametersReturnsAndCoveredDispatch(Program program) throws Exception {
        var fixture = fixture(program.body(), program.members());
        var output = assertTimeoutPreemptively(Duration.ofSeconds(10), fixture::transform);
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void reportsWritesMissingProducerRulesInsideCallee() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); write(q); return read(q);", """
                public static void write(java.util.Queue<Long> q) { q.add(7L); }
                public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }
                """);
        var before = ClassFiles.snapshot(fixture.originals());
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MISSING_PRODUCER_RULE")
                && diagnostic.method().name().equals("write")));
        assertTrue(failure.diagnostics().stream().flatMap(diagnostic -> diagnostic.propagation().stream())
                .anyMatch(edge -> edge.kind() == DependencyKind.CONTAINER_CALL_ARGUMENT));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
        assertEquals(failure.format(DiagnosticVerbosity.VERBOSE),
                assertThrows(TypedOverwriteException.class, fixture::transform).format(DiagnosticVerbosity.VERBOSE));
    }

    @Test
    void propagatesFieldEscapeThroughMultipleCalls() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); forward(q); return lookup(q.poll());", """
                public static Object saved;
                public static void forward(Object q) { retain(q); }
                public static void retain(Object q) { saved = q; }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertEquals(1, failure.diagnostics().size());
        var diagnostic = failure.diagnostics().getFirst();
        assertEquals("ESCAPED_CONTAINER", diagnostic.code());
        assertEquals("retain", diagnostic.method().name());
    }

    @Test
    void rejectsOpenDispatchAndUnknownInputsOfPotentialImplementations() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); return new First().read(q);", """
                public interface Service { int read(java.util.Queue<Long> q); }
                public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                public static int unknown(Service service, java.util.Queue<Long> q) { return service.read(q); }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")));
    }

    @Test
    void rejectsDispatchWithNativeImplementation() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service service = flag ? new First() : new Native(); return lookup(q.peek()) + service.read(q);", """
                public interface Service { int read(java.util.Queue<Long> q); }
                public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                public static class Native implements Service { public native int read(java.util.Queue<Long> q); }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")));
    }

    @Test
    void retainsUnknownReceiverWhenMergedWithKnownImplementation() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service service = flag ? new First() : externalService(); return lookup(q.peek()) + service.read(q);", """
                public interface Service { int read(java.util.Queue<Long> q); }
                public static class First implements Service { public int read(java.util.Queue<Long> q) { return lookup(q.poll()); } }
                public static native Service externalService();
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")));
    }

    @Test
    void selectsMostSpecificDefaultImplementationBeforeCheckingEffects() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); Service service = new Implementation(); int result = service.read(q); return result + lookup(q.poll());", """
                public static Object saved;
                public interface Service { default int read(java.util.Queue<Long> q) { return q.size(); } }
                public interface Refined extends Service {
                    default int read(java.util.Queue<Long> q) { saved = q; return q.size(); }
                }
                public static class Implementation implements Service, Refined {}
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("ESCAPED_CONTAINER")
                && diagnostic.method().owner().endsWith("$Refined")));
    }

    @Test
    void treatsMethodReferencesAsOpenIncomingBoundaries() {
        var fixture = fixture("java.util.Queue<Long> q = new java.util.ArrayDeque<>(); q.add(position(12).asLong()); java.util.function.Function<java.util.Queue<Long>, Integer> reader = Subject::read; return read(q);",
                "public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")));
    }

    @Test
    void rejectsPackageAccessDispatchWithoutAnOverrideProof() {
        var fixture = BytecodeFixtures.fixture("""
                public static int lookup(long value) { return (int)value; }
                public static int run() {
                    java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                    q.add(new BlockPos(12).asLong());
                    int result = foreign.Base.invoke(new other.Child(), q);
                    return result + lookup(q.poll());
                }
                """, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                public static int lookup(BlockPos value) { return (int)value.value(); }
                @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                private static BlockPos keep(BlockPos value) { return value; }
                @love.crysh.mixinta.TypeRule(love.crysh.mixinta.TypeRule.Kind.READ)
                private static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                """, Map.of(
                "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }",
                "foreign.Base", """
                        package foreign;
                        public class Base {
                            public static Object saved;
                            int read(java.util.Queue<Long> q) { saved = q; return q.size(); }
                            public static int invoke(Base receiver, java.util.Queue<Long> q) { return receiver.read(q); }
                        }
                        """,
                "other.Child", """
                        package other;
                        public class Child extends foreign.Base {
                            public int read(java.util.Queue<Long> q) { return q.size(); }
                        }
                        """));
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")
                || diagnostic.code().equals("ESCAPED_CONTAINER")));
    }

    @Test
    void terminatesUnseededRecursiveCycleWithUnresolvedConstraint() {
        var fixture = fixture("return 0;", """
                public static int recurse(java.util.Queue<Long> q, boolean flag) {
                    return flag ? lookup(q.poll()) : recurse(q, flag);
                }
                """);
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(TypedOverwriteException.class, fixture::transform));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNRESOLVED_CONTAINER")));
    }

    @Test
    void requiresOneRepresentationForAllCallsOfSharedBody() {
        var fixture = fixture("java.util.Queue<Long> first = new java.util.ArrayDeque<>(); java.util.Queue<Long> second = new java.util.ArrayDeque<>(); first.add(position(12).asLong()); second.add(7L); return read(first) + read(second);",
                "public static int read(java.util.Queue<Long> q) { return lookup(q.poll()); }");
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MISSING_PRODUCER_RULE")));
    }

    @Test
    void treatsUncalledRecursiveFactoryAsAnEscapingEntry() {
        var fixture = fixture("return 0;", """
                public static java.util.Queue<Long> create(int count) {
                    java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                    q.add(position(12).asLong());
                    lookup(q.peek());
                    return count == 0 ? q : create(count - 1);
                }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("ESCAPED_CONTAINER")));
    }

    @Test
    void invokesPrivateRulesAcrossPackagesThroughSyntheticBridges() throws Exception {
        var fixture = BytecodeFixtures.fixture("""
                public static int sources;
                public static BlockPos position(long value) { sources++; return new BlockPos(value); }
                public static int lookup(long value) { return (int)value; }
                public static long run(boolean flag) {
                    java.util.Queue<Long> q = new java.util.ArrayDeque<>();
                    other.Worker.write(q, position(12));
                    int result = other.Worker.read(q);
                    other.Worker.write(q, position(3));
                    return result + lookup(q.peek()) + other.Worker.oldRead(q);
                }
                """, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                public static int lookup(BlockPos value) { return (int)value.value(); }
                @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                private static BlockPos keep(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                @love.crysh.mixinta.TypeRule(love.crysh.mixinta.TypeRule.Kind.READ)
                private static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                @love.crysh.mixinta.TypeRule(love.crysh.mixinta.TypeRule.Kind.ENCODE)
                private static long encode(BlockPos value) { return value.value(); }
                """, Map.of(
                "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }",
                "other.Worker", """
                        package other;
                        public final class Worker {
                            public static void write(java.util.Queue<Long> q, sample.BlockPos pos) { q.add(pos.asLong()); }
                            public static int read(java.util.Queue<Long> q) { return sample.Subject.lookup(q.poll()); }
                            public static long oldRead(java.util.Queue<Long> q) { return q.poll(); }
                        }
                        """));
        var prepared = fixture.prepare();
        var plan = prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared));
        var output = plan.apply();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        var subject = ClassFiles.read(output.get("sample/Subject"));
        assertEquals(3, subject.methods.stream().filter(method -> method.name.startsWith("mixinta$rule$")
                && (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC))
                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)).count());
        assertTrue(subject.methods.stream().filter(method -> method.name.equals("keep") || method.name.equals("read") || method.name.equals("encode"))
                .allMatch(method -> (method.access & Opcodes.ACC_PRIVATE) != 0));
        assertTrue(ClassFiles.read(output.get("other/Worker")).methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.startsWith("mixinta$rule$")));
        plan.apply().forEach((name, bytes) -> assertArrayEquals(bytes, output.get(name)));
    }
}
