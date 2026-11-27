package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedLocalMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"primitive", "boxed", "container"})
    void executesReassignedLocalsWithReusablePrivateRuleBridges(String scenario) throws Exception {
        String body = scenario.equals("container") ? """
                java.util.Queue<Long> value = new java.util.ArrayDeque<>(); value.add(new BlockPos(1).asLong());
                int first = Subject.lookup(value.poll());
                value = new java.util.ArrayDeque<>(); value.add(new BlockPos(2).asLong());
                return first + Subject.lookup(value.poll());
                """ : """
                %s value = new BlockPos(1).asLong(); int first = Subject.lookup(value);
                value = new BlockPos(2).asLong(); return first + Subject.lookup(value);
                """.formatted(scenario.equals("primitive") ? "long" : "Long");
        var fixture = BytecodeFixtures.fixture("""
                public static int lookup(long value) { return (int)value; }
                public static int run() { return Worker.run(); }
                """, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                public static int lookup(BlockPos value) { return (int)value.value(); }
                @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                private static BlockPos keep(BlockPos value) { return value; }
                @love.crysh.mixinta.anno.TypeRule(love.crysh.mixinta.anno.TypeRule.Kind.READ)
                private static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
                """, Map.of(
                        "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }",
                        "sample.Worker", "package sample; public class Worker { public static int run() {" + body + "} }"));
        var prepared = fixture.prepare();
        var input = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var snapshot = ClassFiles.snapshot(input);
        var plan = prepared.analyze(input);
        var output = plan.apply();
        BytecodeFixtures.verify(output);
        var subject = ClassFiles.read(output.get("sample/Subject"));
        assertEquals(1, subject.methods.stream().filter(method -> method.name.startsWith("mixinta$rule$keep$")).count());
        var run = ClassFiles.findMethod(ClassFiles.read(output.get("sample/Worker")), "run", "()I");
        var variable = run.localVariables.stream().filter(local -> local.name.equals("value")).findFirst().orElseThrow();
        if (scenario.equals("container")) assertEquals("Ljava/util/Queue<Lsample/BlockPos;>;", variable.signature);
        else assertEquals("Lsample/BlockPos;", variable.desc);
        assertEquals(3, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
        var repeated = plan.apply();
        output.forEach((name, bytes) -> assertArrayEquals(bytes, repeated.get(name)));
        snapshot.forEach((name, bytes) -> assertArrayEquals(bytes, input.get(name)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesEffectiveCrossClassLocalMetadataAndSkipsUnchangedGenericSignatures(boolean generic) throws Exception {
        var fixture = BytecodeFixtures.fixture("""
                public static int lookup(long pos) { return (int)pos; }
                public static int run() {
                    java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                    queue.add(new BlockPos(12).asLong());
                    java.util.Queue<Long> same = Forwarder.pass(queue);
                    return lookup(same.poll());
                }
                """, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                public static int lookup(BlockPos pos) { return (int)pos.value(); }
                @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                public static BlockPos keep(BlockPos pos) { return pos; }
                @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.READ)
                public static BlockPos read(BlockPos pos) { return java.util.Objects.requireNonNull(pos); }
                """, Map.of(
                        "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }",
                        "sample.Forwarder", "package sample; public class Forwarder { " + (generic
                                ? "public static <T> java.util.Queue<T> pass(java.util.Queue<T> queue) { return queue; }"
                                : "public static java.util.Queue<Long> pass(java.util.Queue<Long> queue) { return queue; }") + " }"));
        var preparation = fixture.prepare();
        var input = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        var snapshot = ClassFiles.snapshot(input);
        var plan = preparation.analyze(input);

        assertEquals(!generic, plan.modifiedClasses().contains("sample/Forwarder"));
        var output = plan.apply();
        var repeated = plan.apply();
        BytecodeFixtures.verify(output);
        var pass = ClassFiles.findMethod(ClassFiles.read(output.get("sample/Forwarder")), "pass", "(Ljava/util/Queue;)Ljava/util/Queue;");
        assertEquals(generic ? "Ljava/util/Queue<TT;>;" : "Ljava/util/Queue<Lsample/BlockPos;>;", pass.localVariables.getFirst().signature);
        if (generic) assertArrayEquals(input.get("sample/Forwarder"), output.get("sample/Forwarder"));
        output.forEach((name, bytes) -> assertArrayEquals(bytes, repeated.get(name)));
        snapshot.forEach((name, bytes) -> assertArrayEquals(bytes, input.get(name)));
        assertEquals(12, BytecodeFixtures.loader(output).loadClass("sample.Subject").getMethod("run").invoke(null));
    }
}
