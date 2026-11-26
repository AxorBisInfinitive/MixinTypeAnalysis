package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;
import java.util.stream.Stream;

import static love.crysh.mixinta.analysis.TypedContainerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedMapTest {
    static Stream<String> mapOperations() {
        return Stream.of(
                "java.util.Map<String,Long> map = new java.util.HashMap<>(); map.put(\"a\", position(12).asLong()); long old = map.put(\"a\", position(3).asLong()); return lookup(old) + lookup(map.get(\"a\")) + lookup(map.remove(\"a\"));",
                "java.util.LinkedHashMap<String,Long> map = new java.util.LinkedHashMap<>(4, 0.75f); map.put(\"a\", position(12).asLong()); boolean found = map.containsValue(12L); return lookup(map.get(\"a\")) + (found ? 1 : 0);",
                "java.util.Map<Long,Long> map = new java.util.HashMap<>(); long key = position(200).asLong(); int result = lookup(key); map.put(key, position(12).asLong()); return result + lookup(map.get(key)) + (map.containsKey(key) ? lookup(map.remove(key)) : 0);",
                "java.util.Map<Long,String> map = new java.util.HashMap<>(); long key = position(200).asLong(); map.put(key, \"a\"); return lookup(key) + (map.containsKey(key) && map.get(key).equals(\"a\") ? 1 : 0);",
                "java.util.Map<Long,Long> map = new java.util.HashMap<>(); long value = position(200).asLong(); map.put(value, value); return lookup(map.get(value));",
                "java.util.Map<Long,Long> map = new java.util.HashMap<>(); Long value = position(200).asLong(); map.put(value, value); int result = lookup(value); boolean present = map.containsKey(value) && map.containsValue(value); return result + lookup(map.get(value)) + (present ? lookup(map.remove(value)) : 0);",
                "java.util.Map<Long,Long> map = new java.util.HashMap<>(); Long value = flag ? position(200).asLong() : null; map.put(value, value); if (map.containsKey(value) && map.containsValue(value)) sources += 5; return lookup(map.get(value));",
                "java.util.Map<Long,Long> map = new java.util.HashMap<>(); map.put(null, position(12).asLong()); map.put(1L, null); boolean found = map.containsKey(null) && map.containsValue(null); return lookup(map.get(null)) + (found ? 1 : 0);",
                "java.util.Map<String,Long> map = new java.util.LinkedHashMap<>(); long value = position(12).asLong(); map.put(\"a\", value); int result = lookup(value); return result + (map.containsValue(value) ? 1 : 0);",
                "java.util.Map<String,Long> map = new java.util.HashMap<>(); map.put(\"a\", position(12).asLong()); map.clear(); if (flag) map.put(\"b\", position(3).asLong()); return lookup(map.get(\"b\"));",
                "java.util.Map<String,Long> map = new java.util.HashMap<>(); map.put(\"a\", position(12).asLong()); boolean absent = !map.containsValue(99L); return lookup(map.get(\"a\")) + map.size() + (map.isEmpty() || !absent ? 0 : 1);",
                "java.util.Map<String,Long> map = flag ? new java.util.HashMap<>() : null; map.put(\"a\", position(12).asLong()); return lookup(map.get(\"a\"));",
                "java.util.Map<String,Long> map = new java.util.HashMap<>(); map.put(\"a\", flag ? position(12).asLong() : null); return lookup(map.get(\"a\"));",
                "java.util.Map<String,Long> map = new java.util.HashMap<>(); class Query { public boolean equals(Object other) { return other instanceof Long value && value == 12; } } map.put(\"a\", position(12).asLong()); return lookup(map.get(\"a\")) + (map.containsValue(new Query()) ? 1 : 0);"
        );
    }

    @ParameterizedTest
    @MethodSource("mapOperations")
    void preservesMapQueriesResultsExceptionsAndSourceEvaluations(String body) throws Exception {
        var fixture = fixture(body);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void changesOnlyTheValueArgumentInGenericDebugSignatures() throws Exception {
        var fixture = fixture("""
                java.util.Map<Long,Long> map = new java.util.HashMap<>();
                map.put(200L, position(12).asLong());
                return lookup(map.get(200L));
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertTrue(run(output).localVariables.stream().anyMatch(variable -> variable.name.equals("map")
                && variable.signature.equals("Ljava/util/Map<Ljava/lang/Long;Lsample/BlockPos;>;")));
        assertTrue(Arrays.stream(run(output).instructions.toArray()).anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("java/util/Map") && call.name.equals("get") && call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")));
    }

    @Test
    void keepsNestedKeySignaturesAndIndependentMapValues() throws Exception {
        var fixture = fixture("""
                java.util.Map<java.util.List<Long>,Long> map = new java.util.HashMap<>();
                java.util.Map<String,Long> unrelated = new java.util.HashMap<>();
                unrelated.put("a", 99L);
                map.put(java.util.List.of(1L), position(12).asLong());
                return lookup(map.get(java.util.List.of(1L))) + unrelated.get("a");
                """);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertTrue(run(output).localVariables.stream().anyMatch(variable -> variable.name.equals("map")
                && variable.signature.equals("Ljava/util/Map<Ljava/util/List<Ljava/lang/Long;>;Lsample/BlockPos;>;")));
        assertTrue(run(output).localVariables.stream().anyMatch(variable -> variable.name.equals("unrelated")
                && variable.signature.equals("Ljava/util/Map<Ljava/lang/String;Ljava/lang/Long;>;")));
    }

    @Test
    void requiresAnEncoderForValueObservation() {
        var fixture = fixture("""
                java.util.Map<String,Long> map = new java.util.HashMap<>();
                map.put("a", position(12).asLong());
                boolean found = map.containsValue(12L);
                return lookup(map.get("a")) + (found ? 1 : 0);
                """);
        var mixin = ClassFiles.read(fixture.mixins().get("sample/SubjectMixin"));
        mixin.methods.removeIf(method -> method.name.equals("encode"));
        fixture.mixins().put(mixin.name, ClassFiles.write(mixin));
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("MISSING_ENCODE_RULE")));
    }

    @Test
    void rejectsUnmodeledMapCallbacksBeforePublishing() {
        var fixture = fixture("""
                java.util.Map<String,Long> map = new java.util.HashMap<>();
                map.put("a", position(12).asLong());
                map.compute("a", (key, value) -> value);
                return lookup(map.get("a"));
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.code().equals("UNKNOWN_CONTAINER_USE")));
    }
}
