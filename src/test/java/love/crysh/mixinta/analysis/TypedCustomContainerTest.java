package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.DiagnosticVerbosity;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static love.crysh.mixinta.analysis.TypedContainerTest.assertEquivalent;
import static org.junit.jupiter.api.Assertions.*;

class TypedCustomContainerTest {
    private static final String BOX = """
            package sample;
            public final class Box<T> {
                private final java.util.List<T> items = new java.util.ArrayList<>();
                public Box() {}
                public void put(T value) { items.add(value); }
                public T get(int index) { return items.get(index); }
                public T take() { return items.removeFirst(); }
                public T replace(int index, T value) { return items.set(index, value); }
                public int count() { return items.size(); }
                public void reset() { items.clear(); }
                public java.util.Iterator<T> iterator() { return items.iterator(); }
                public java.util.List<T> view() { return items.subList(0, items.size()); }
                public java.util.Collection<T> values() { return items; }
                public BoxView<T> alias() { return new BoxView<>(this); }
                public Object leak() { return items; }
            }
            """;
    private static final String MODELS = """
            @ContainerRule(method="sample.Box.<init>()V", operation=ContainerRule.Operation.ALLOCATE)
            @ContainerRule(method="sample.Box.put(Ljava/lang/Object;)V", operation=ContainerRule.Operation.WRITE, parameter=0)
            @ContainerRule(method="sample.Box.get(I)Ljava/lang/Object;", operation=ContainerRule.Operation.READ)
            @ContainerRule(method="sample.Box.take()Ljava/lang/Object;", operation=ContainerRule.Operation.READ_REMOVE)
            @ContainerRule(method="sample.Box.replace(ILjava/lang/Object;)Ljava/lang/Object;", operation=ContainerRule.Operation.READ_WRITE, parameter=1)
            @ContainerRule(method="sample.Box.count()I", operation=ContainerRule.Operation.OBSERVE)
            @ContainerRule(method="sample.Box.reset()V", operation=ContainerRule.Operation.CLEAR)
            @ContainerRule(method="sample.Box.iterator()Ljava/util/Iterator;", operation=ContainerRule.Operation.VIEW, view=ContainerRule.View.ITERATOR)
            @ContainerRule(method="sample.Box.view()Ljava/util/List;", operation=ContainerRule.Operation.VIEW, view=ContainerRule.View.LIST)
            @ContainerRule(method="sample.Box.values()Ljava/util/Collection;", operation=ContainerRule.Operation.VIEW, view=ContainerRule.View.VALUES)
            @ContainerRule(method="sample.Box.alias()Lsample/BoxView;", operation=ContainerRule.Operation.VIEW, view=ContainerRule.View.ALIAS)
            @ContainerRule(method="sample.BoxView.get(I)Ljava/lang/Object;", operation=ContainerRule.Operation.READ)
            """;
    private static final String HELPERS = """
            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
            public static BlockPos keep(BlockPos value) { return java.util.Objects.requireNonNull(value); }
            @love.crysh.mixinta.anno.TypeRule(love.crysh.mixinta.anno.TypeRule.Kind.READ)
            public static BlockPos read(BlockPos value) { return java.util.Objects.requireNonNull(value); }
            @love.crysh.mixinta.anno.TypeRule(love.crysh.mixinta.anno.TypeRule.Kind.ENCODE)
            public static long encode(BlockPos value) { return value.value(); }
            """;

    private static BytecodeFixtures.Fixture fixture(String body, String members, String declarations) {
        return fixture(body, members, declarations, Map.of());
    }

    private static BytecodeFixtures.Fixture fixture(String body, String members, String declarations, Map<String, String> additional) {
        Map<String, String> extra = new LinkedHashMap<>(additional);
        extra.put("sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }");
        extra.put("sample.Box", BOX);
        extra.put("sample.BoxView", "package sample; public final class BoxView<T> { private final Box<T> box; public BoxView(Box<T> box){this.box=box;} public T get(int index){return box.get(index);} }");
        extra.put("sample.Rules", "package sample; import love.crysh.mixinta.anno.ContainerRule; " + declarations + " public class Rules {" + HELPERS + "}");
        return BytecodeFixtures.fixture("""
                public static int sources;
                public static BlockPos position(long value) { sources++; return new BlockPos(value); }
                public static int lookup(long value) { return (int)value; }
                public static long run(boolean flag) {
                """ + body + "}" + members, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=Rules.class))
                public static int lookup(BlockPos value) { return (int)value.value(); }
                """, extra);
    }

    static Stream<String> programs() {
        return Stream.of(
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.get(0));",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); box.put(position(3).asLong()); return lookup(box.take()) + box.count();",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); long old = box.replace(0, position(3).asLong()); return lookup(old) + lookup(box.get(0));",
                "Box<Long> box = new Box<>(); box.put(position(3).asLong()); box.reset(); box.put(position(12).asLong()); return lookup(box.get(0));",
                "Box<Long> box = new Box<>(); if (flag) box.put(position(12).asLong()); return lookup(box.get(0));",
                "Box<Long> box = new Box<>(); box.put(flag ? position(12).asLong() : null); return lookup(box.get(0));",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.iterator().next());",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); java.util.List<Long> view = box.view(); view.set(0, position(3).asLong()); return lookup(view.iterator().next()) + lookup(box.get(0));",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.alias().get(0));",
                "Box<Long> box = new Box<>(); box.put(position(12).asLong()); java.util.Collection<Long> values = box.values(); if (!values.contains(12L)) throw new AssertionError(); return lookup(values.iterator().next());",
                "Box<Long> a = create(3); Box<Long> b = new Box<>(); a.put(position(12).asLong()); b.put(position(3).asLong()); return read(flag ? a : b);"
        );
    }

    @ParameterizedTest
    @MethodSource("programs")
    void executesDeclaredContainerOperationsAndViews(String body) throws Exception {
        var fixture = fixture(body, body.contains("return read") ? """
                public static Box<Long> create(int depth) { return depth == 0 ? new Box<>() : create(depth - 1); }
                public static int read(Box<Long> box) { return lookup(box.get(0)); }
                """ : "", MODELS);
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        assertArrayEquals(fixture.originals().get("sample/Box"), output.get("sample/Box"));
        assertTrue(Arrays.stream(TypedContainerTest.run(output).instructions.toArray())
                .noneMatch(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Long")
                        && call.name.equals("longValue")));
    }

    @Test
    void keepsUndeclaredOperationsAsBoundaries() {
        var fixture = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); box.leak(); return lookup(box.get(0));", "", MODELS);
        var failure = assertThrows(TypedOverwriteException.class, fixture::transform);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("UNKNOWN_CONTAINER_USE")));
    }

    @Test
    void reportsMissingWritesAndEscapesThroughDeclaredViews() {
        var missing = fixture("Box<Long> box = new Box<>(); box.put(7L); return lookup(box.get(0));", "", MODELS);
        assertTrue(assertThrows(TypedOverwriteException.class, missing::transform).diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("MISSING_PRODUCER_RULE")));
        var escape = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); saved = box.alias(); return lookup(box.get(0));",
                "public static Object saved;", MODELS);
        assertTrue(assertThrows(TypedOverwriteException.class, escape::transform).diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("ESCAPED_CONTAINER")));
    }

    static Stream<String> invalidDeclarations() {
        return Stream.of(
                "@ContainerRule(method=\"sample.Box.missing()I\", operation=ContainerRule.Operation.OBSERVE)",
                "@ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ, channel=-1)",
                "@ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ, version=0)",
                "@ContainerRule(method=\"sample.Box.put(Ljava/lang/Object;)V\", operation=ContainerRule.Operation.WRITE, parameter=1)",
                "@ContainerRule(method=\"sample.Box.put(Ljava/lang/Object;)V\", operation=ContainerRule.Operation.WRITE)",
                "@ContainerRule(method=\"sample.Box.replace(ILjava/lang/Object;)Ljava/lang/Object;\", operation=ContainerRule.Operation.WRITE, parameter=0)",
                "@ContainerRule(method=\"sample.Box.count()I\", operation=ContainerRule.Operation.READ)",
                "@ContainerRule(method=\"sample.Box.count()I\", operation=ContainerRule.Operation.CLEAR)",
                "@ContainerRule(method=\"sample.Box.count()I\", operation=ContainerRule.Operation.ALLOCATE)",
                "@ContainerRule(method=\"sample.Box.<init>()V\", operation=ContainerRule.Operation.OBSERVE)",
                "@ContainerRule(method=\"sample.Box.put(Ljava/lang/Object;)V\", operation=ContainerRule.Operation.OBSERVE)",
                "@ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.OBSERVE)",
                "@ContainerRule(method=\"sample.Box.iterator()Ljava/util/Iterator;\", operation=ContainerRule.Operation.VIEW)",
                "@ContainerRule(method=\"sample.Box.count()I\", operation=ContainerRule.Operation.OBSERVE, view=ContainerRule.View.ITERATOR)",
                "@ContainerRule(method=\"sample.Box.alias()Lsample/BoxView;\", operation=ContainerRule.Operation.VIEW, view=ContainerRule.View.ITERATOR)",
                "@ContainerRule(method=\"sample/Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ) @ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ)",
                "@ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ, id=\"same\") @ContainerRule(method=\"sample.Box.take()Ljava/lang/Object;\", operation=ContainerRule.Operation.READ_REMOVE, id=\"same\")",
                "@ContainerRule(method=\"java.util.ArrayDeque.<init>()V\", operation=ContainerRule.Operation.ALLOCATE)",
                "@ContainerRule(method=\"bad descriptor\", operation=ContainerRule.Operation.READ)"
        );
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidContractsDuringPreparation(String declaration) {
        var fixture = fixture("return 0;", "", declaration);
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("INVALID_CONTAINER_RULE")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dispatch", "static", "typed", "argument"})
    void rejectsUnsafeMethodShapes(String shape) {
        String source = switch (shape) {
            case "static" -> "public final class Bad { public static Object read(){return null;} }";
            case "typed" -> "public final class Bad { public void write(Long value){} }";
            case "argument" -> "public final class Bad { public Object read(Object retained){return null;} }";
            default -> "public class Bad { public Object read(){return null;} }";
        };
        String method = switch (shape) {
            case "typed" -> "write(Ljava/lang/Long;)V";
            case "argument" -> "read(Ljava/lang/Object;)Ljava/lang/Object;";
            default -> "read()Ljava/lang/Object;";
        };
        String rule = "@ContainerRule(method=\"sample.Bad." + method + "\", operation=ContainerRule.Operation."
                + (shape.equals("typed") ? "WRITE, parameter=0" : "READ") + ")";
        var base = fixture("return 0;", "", rule);
        Map<String, byte[]> originals = new LinkedHashMap<>(base.originals());
        originals.putAll(BytecodeFixtures.compile(Map.of("sample.Bad", "package sample; " + source)));
        var failure = assertThrows(TypedOverwriteException.class, () -> new TypedOverwriteAnalyzer().prepare(originals, base.mixins()));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("INVALID_CONTAINER_RULE")));
    }

    @Test
    void revalidatesDispatchAfterMixinAndPreservesInputOnFailure() {
        var fixture = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.get(0));", "", MODELS);
        var prepared = fixture.prepare();
        var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var node = ClassFiles.read(merged.get("sample/Box"));
        node.access &= ~Opcodes.ACC_FINAL;
        merged.put(node.name, ClassFiles.write(node));
        var before = ClassFiles.snapshot(merged);
        var failure = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("CONTAINER_RULE_CONFLICT")));
        before.forEach((name, bytes) -> assertArrayEquals(bytes, merged.get(name)));
    }

    @Test
    void migratesIndependentCustomChannelAndItsGenericArgument() throws Exception {
        String declarations = """
                @ContainerRule(method="sample.Pair.<init>()V", operation=ContainerRule.Operation.ALLOCATE)
                @ContainerRule(method="sample.Pair.first(Ljava/lang/Object;)V", operation=ContainerRule.Operation.WRITE, parameter=0, channel=0)
                @ContainerRule(method="sample.Pair.first()Ljava/lang/Object;", operation=ContainerRule.Operation.READ, channel=0)
                @ContainerRule(method="sample.Pair.second(Ljava/lang/Object;)V", operation=ContainerRule.Operation.WRITE, parameter=0, channel=1)
                @ContainerRule(method="sample.Pair.second()Ljava/lang/Object;", operation=ContainerRule.Operation.READ, channel=1)
                """;
        var fixture = fixture("""
                Pair<Long, Long> pair = new Pair<>();
                pair.first(7L); pair.second(position(12).asLong());
                return lookup(pair.second()) + pair.first();
                """, "", declarations, Map.of("sample.Pair", """
                package sample;
                public final class Pair<A, B> {
                    private A first;
                    private B second;
                    public Pair() {}
                    public void first(A value) { first = value; }
                    public A first() { return first; }
                    public void second(B value) { second = value; }
                    public B second() { return second; }
                }
                """));
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
        var pair = TypedContainerTest.run(output).localVariables.stream().filter(variable -> variable.name.equals("pair")).findFirst().orElseThrow();
        assertEquals("Lsample/Pair<Ljava/lang/Long;Lsample/BlockPos;>;", pair.signature);
        assertEquals(1, Arrays.stream(TypedContainerTest.run(output).instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Long") && call.name.equals("longValue")).count());
    }

    @Test
    void keepsPlansRepeatableAndNamesContractInConflict() {
        var fixture = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.get(0));", "", MODELS);
        var prepared = fixture.prepare();
        var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var plan = prepared.analyze(merged);
        var first = plan.apply();
        plan.apply().forEach((name, bytes) -> assertArrayEquals(bytes, first.get(name)));
        var box = ClassFiles.read(merged.get("sample/Box"));
        box.methods.removeIf(method -> method.name.equals("put"));
        merged.put(box.name, ClassFiles.write(box));
        var failure = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged));
        assertTrue(failure.format(DiagnosticVerbosity.VERBOSE).contains("sample/Box.put(Ljava/lang/Object;)V version=1"));
    }

    @Test
    void aggregatesInvalidDeclarationsAndRejectsConflictingScopes() {
        var invalid = fixture("return 0;", "", """
                @ContainerRule(method="sample.Box.absent()I", operation=ContainerRule.Operation.OBSERVE)
                @ContainerRule(method="sample.Box.count()I", operation=ContainerRule.Operation.READ)
                """);
        var failure = assertThrows(TypedOverwriteException.class, invalid::prepare);
        assertEquals(2, failure.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("INVALID_CONTAINER_RULE")).count());
        var conflicting = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.get(0)) + other(3L);",
                "public static int other(long value){return (int)value;}", MODELS, Map.of(
                        "sample.OtherRules", "package sample; import love.crysh.mixinta.anno.ContainerRule; @ContainerRule(method=\"sample.Box.get(I)Ljava/lang/Object;\", operation=ContainerRule.Operation.READ_REMOVE) public class OtherRules {}",
                        "sample.OtherMixin", """
                                package sample;
                                @org.spongepowered.asm.mixin.Mixin(value=Subject.class, remap=false)
                                public class OtherMixin {
                                    @love.crysh.mixinta.anno.TypedOverwrite(method="other(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=OtherRules.class))
                                    public static int other(BlockPos value) { return (int)value.value(); }
                                }
                                """));
        var prepared = conflicting.prepare();
        var merged = BytecodeFixtures.mergeForCoreTest(conflicting, prepared);
        var conflict = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged));
        assertTrue(conflict.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("CONTAINER_RULE_CONFLICT")));
    }

    @Test
    void acceptsDeclarationsOnMixinRuleClass() throws Exception {
        var base = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.get(0));", "", "");
        var mixin = BytecodeFixtures.compile(Map.of("sample.SubjectMixin", """
                package sample;
                import love.crysh.mixinta.anno.ContainerRule;
                @org.spongepowered.asm.mixin.Mixin(value=Subject.class, remap=false)
                """ + MODELS + """
                public class SubjectMixin {
                    @love.crysh.mixinta.anno.TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                    public static int lookup(BlockPos value) { return (int)value.value(); }
                """ + HELPERS + "}",
                "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong(){return value;} }",
                "sample.Subject", "package sample; public final class Subject { public static int lookup(long value){return (int)value;} }"));
        var fixture = new BytecodeFixtures.Fixture(base.originals(), Map.of("sample/SubjectMixin", mixin.get("sample/SubjectMixin")));
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void analyzesExternalContainerMetadataWithoutDefiningItsClasses() throws Exception {
        var fixture = fixture("Box<Long> box = new Box<>(); box.put(position(12).asLong()); return lookup(box.iterator().next());", "", MODELS);
        ClassLoader resources = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                byte[] bytes = name.endsWith(".class") ? fixture.originals().get(name.substring(0, name.length() - 6)) : null;
                return bytes == null ? super.getResourceAsStream(name) : new java.io.ByteArrayInputStream(bytes);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("sample.")) throw new AssertionError("Analysis attempted to define " + name);
                return super.loadClass(name, resolve);
            }
        };
        Map<String, byte[]> batch = new LinkedHashMap<>(fixture.originals());
        batch.remove("sample/Box");
        batch.remove("sample/BoxView");
        var reduced = new BytecodeFixtures.Fixture(batch, fixture.mixins());
        var prepared = new TypedOverwriteAnalyzer(resources).prepare(batch, fixture.mixins());
        var output = prepared.analyze(BytecodeFixtures.mergeForCoreTest(reduced, prepared)).apply();
        Map<String, byte[]> executable = new LinkedHashMap<>(fixture.originals());
        executable.putAll(output);
        BytecodeFixtures.verify(executable);
        assertEquivalent(fixture.originals(), executable);
    }

    @Test
    void bindsErasedElementAfterWideParameters() throws Exception {
        var fixture = fixture("Wide<Long> box = new Wide<>(); box.put(2L, 3d, position(12).asLong()); return lookup(box.get());", "", """
                @ContainerRule(method="sample.Wide.<init>()V", operation=ContainerRule.Operation.ALLOCATE)
                @ContainerRule(method="sample.Wide.put(JDLjava/lang/Object;)V", operation=ContainerRule.Operation.WRITE, parameter=2)
                @ContainerRule(method="sample.Wide.get()Ljava/lang/Object;", operation=ContainerRule.Operation.READ)
                """, Map.of("sample.Wide", """
                package sample;
                public final class Wide<T> {
                    private T value;
                    public Wide() {}
                    public void put(long first, double second, T value) { this.value = value; }
                    public T get() { return value; }
                }
                """));
        var output = fixture.transform();
        BytecodeFixtures.verify(output);
        assertEquivalent(fixture.originals(), output);
    }

    @Test
    void rejectsReferenceTargetCallsHiddenInsideModeledBodies() {
        var fixture = fixture("Opaque box = new Opaque(); box.put(position(12).asLong()); return lookup((Long)box.get());", "", """
                @ContainerRule(method="sample.Opaque.<init>()V", operation=ContainerRule.Operation.ALLOCATE)
                @ContainerRule(method="sample.Opaque.put(Ljava/lang/Object;)V", operation=ContainerRule.Operation.WRITE, parameter=0)
                @ContainerRule(method="sample.Opaque.get()Ljava/lang/Object;", operation=ContainerRule.Operation.READ)
                """, Map.of("sample.Opaque", """
                package sample;
                public final class Opaque {
                    private Object value;
                    public Opaque() {}
                    public void put(Object value){this.value=value;}
                    public Object get(){Subject.lookup(1L);return value;}
                }
                """));
        var prepared = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(BytecodeFixtures.mergeForCoreTest(fixture, prepared)));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("CONTAINER_RULE_CONFLICT")
                && diagnostic.message().contains("requiring reference migration")));
    }
}
