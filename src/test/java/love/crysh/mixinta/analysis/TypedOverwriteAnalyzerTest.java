package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedOverwriteAnalyzerTest {
    private static final String ORIGINAL = "public static int div(int a, int b) { return a / b; }";
    private static final String TYPED = """
            @TypedOverwrite(method = "div(II)I")
            public static int div(double a, double b) { return (int) (a / b * 10); }
            """;

    @Test
    void convertsSameSignatureToNativeOverwriteWithoutChangingPriorityOrProtection() {
        var fixture = BytecodeFixtures.fixture("""
                @org.spongepowered.asm.mixin.Final
                public static int div(int a, int b) { return a / b; }
                """, """
                @TypedOverwrite(method = "div(II)I", aliases = {"fallback"}, remap = false, constraints = "FEATURE(1)")
                public static int div(int a, int b) { return a * b; }
                """);
        ClassNode mixin = ClassFiles.read(fixture.mixins().get("sample/SubjectMixin"));
        var metadata = ClassFiles.annotation(mixin.invisibleAnnotations, ClassFiles.MIXIN);
        metadata.values.addAll(List.of("priority", 2000));
        fixture.mixins().put(mixin.name, ClassFiles.write(mixin));
        byte[] original = fixture.originals().get("sample/Subject").clone();
        PreparedOverwrites preparation = fixture.prepare();
        ClassNode prepared = ClassFiles.read(preparation.mixins().get(mixin.name));
        MethodNode method = ClassFiles.findMethod(prepared, "div", "(II)I");
        AnnotationNode nativeOverwrite = ClassFiles.annotation(method.visibleAnnotations, ClassFiles.OVERWRITE);
        assertNotNull(nativeOverwrite);
        assertNull(ClassFiles.annotation(method.visibleAnnotations, ClassFiles.TYPED_OVERWRITE));
        assertEquals(List.of("fallback"), ClassFiles.value(nativeOverwrite, "aliases", null));
        assertEquals(false, ClassFiles.value(nativeOverwrite, "remap", true));
        assertEquals("FEATURE(1)", ClassFiles.value(nativeOverwrite, "constraints", null));
        assertEquals("absent", ClassFiles.value(nativeOverwrite, "method", "absent"));
        assertEquals(2000, ClassFiles.value(ClassFiles.annotation(prepared.invisibleAnnotations, ClassFiles.MIXIN), "priority", null));
        assertArrayEquals(original, fixture.originals().get("sample/Subject"));
        assertNotNull(ClassFiles.annotation(ClassFiles.findMethod(ClassFiles.read(original), "div", "(II)I").visibleAnnotations, ClassFiles.FINAL));
    }

    @Test
    void leavesCompetingNativeOverwritesToMixin() {
        Map<String, byte[]> classes = BytecodeFixtures.compile(Map.of(
                "sample.Subject", "package sample; public class Subject { " + ORIGINAL + " }",
                "sample.Low", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(value = Subject.class, priority = 900)
                        public class Low {
                            @love.crysh.mixinta.TypedOverwrite
                            public static int div(int a, int b) { return 1; }
                        }
                        """,
                "sample.High", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(value = Subject.class, priority = 1100)
                        public class High {
                            @love.crysh.mixinta.TypedOverwrite
                            public static int div(int a, int b) { return 2; }
                        }
                        """));
        var fixture = BytecodeFixtures.split(classes);
        var preparation = fixture.prepare();
        for (var entry : preparation.mixins().entrySet()) {
            ClassNode before = ClassFiles.read(fixture.mixins().get(entry.getKey()));
            ClassNode after = ClassFiles.read(entry.getValue());
            assertEquals(ClassFiles.value(ClassFiles.annotation(before.invisibleAnnotations, ClassFiles.MIXIN), "priority", null),
                    ClassFiles.value(ClassFiles.annotation(after.invisibleAnnotations, ClassFiles.MIXIN), "priority", null));
            assertNotNull(ClassFiles.annotation(ClassFiles.findMethod(after, "div", "(II)I").visibleAnnotations, ClassFiles.OVERWRITE));
        }
    }

    @Test
    void producesTheSameMixinBytesAsAnOrdinaryOverwrite() {
        String typed = "@TypedOverwrite(remap = false, constraints = \"FEATURE(1)\") public static int div(int a, int b) { return a * b; }";
        var fixture = BytecodeFixtures.fixture(ORIGINAL, typed);
        var nativeFixture = BytecodeFixtures.fixture(ORIGINAL, typed.replace("@TypedOverwrite", "@org.spongepowered.asm.mixin.Overwrite"));
        byte[] expected = ClassFiles.write(ClassFiles.read(nativeFixture.mixins().get("sample/SubjectMixin")));
        assertArrayEquals(expected, fixture.prepare().mixins().get("sample/SubjectMixin"));
    }

    @Test
    void retainsExplicitPrivateTargetAsNativeAlias() {
        var fixture = BytecodeFixtures.fixture("private static int div(int a, int b) { return 0; }", """
                @TypedOverwrite(method = "div(II)I")
                private static int replacement(int a, int b) { return a + b; }
                """);
        ClassNode prepared = ClassFiles.read(fixture.prepare().mixins().get("sample/SubjectMixin"));
        var annotation = ClassFiles.annotation(ClassFiles.findMethod(prepared, "replacement", "(II)I").visibleAnnotations, ClassFiles.OVERWRITE);
        assertEquals(List.of("div"), ClassFiles.value(annotation, "aliases", null));
    }

    @Test
    void migratesBothParametersAndExecutesDoubleArithmetic() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int run() { return div(5, 2); }", TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> afterMixins = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        TransformationPlan plan = preparation.analyze(afterMixins);
        assertEquals(1, plan.callSiteCount());
        Map<String, byte[]> result = plan.apply();
        BytecodeFixtures.verify(result);
        ClassNode transformed = ClassFiles.read(result.get("sample/Subject"));
        assertNull(ClassFiles.findMethod(transformed, "div", "(II)I"));
        MethodNode implementation = ClassFiles.findMethod(transformed, "div", "(DD)I");
        assertNotNull(implementation);
        assertEquals(0, implementation.access & Opcodes.ACC_SYNTHETIC);
        assertNull(ClassFiles.annotation(implementation.visibleAnnotations, ClassFiles.TYPED_OVERWRITE));
        assertEquals(25, invoke(result, "sample.Subject", "run"));
        assertArrayEquals(plan.apply().get("sample/Subject"), result.get("sample/Subject"));
        assertNotNull(ClassFiles.findMethod(ClassFiles.read(afterMixins.get("sample/Subject")), "div", "(II)I"));
    }

    @Test
    void preservesEvaluationOrderAndEvaluatesEachArgumentOnce() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + """
                public static String trace = "";
                public static int first() { trace += "A"; return 1; }
                public static int second() { trace += "B"; return 2; }
                public static int run() { return div(first(), second()); }
                """, """
                @TypedOverwrite public static int div(double a, double b) { return (int) (a * 10 + b); }
                """);
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        Class<?> subject = BytecodeFixtures.loader(result).loadClass("sample.Subject");
        assertEquals(12, subject.getMethod("run").invoke(null));
        assertEquals("AB", subject.getField("trace").get(null));
    }

    @Test
    void migratesWideArgumentsAcrossClasses() throws Exception {
        var fixture = BytecodeFixtures.fixture("public static long div(long a, double b) { return 0; }", """
                @TypedOverwrite public static long div(double a, long b) { return (long) (a * 10 + b); }
                """, Map.of("sample.Caller", """
                package sample;
                public class Caller { public static long run() { return Subject.div(7L, 2.9); } }
                """));
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(72L, invoke(result, "sample.Caller", "run"));
        assertNull(ClassFiles.findMethod(ClassFiles.read(result.get("sample/Subject")), "div", "(JD)J"));
        assertNotNull(ClassFiles.findMethod(ClassFiles.read(result.get("sample/Subject")), "div", "(DJ)J"));
    }

    @Test
    void preservesInstanceReceiver() throws Exception {
        var fixture = BytecodeFixtures.fixture("""
                public int div(int a, int b) { return 0; }
                public static int run() { return new Subject().div(5, 2); }
                """, """
                @TypedOverwrite public int div(double a, double b) { return (int) (a / b * 10); }
                """);
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(25, invoke(result, "sample.Subject", "run"));
    }

    @Test
    void handlesRecursionInTypedImplementation() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int run() { return div(5, 2); }", """
                @TypedOverwrite public static int div(double a, double b) {
                    if (a <= b) { return 1; }
                    return div(a - b, b) + 1;
                }
                """);
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(3, invoke(result, "sample.Subject", "run"));
    }

    @Test
    void discoversCallsAddedDuringTheMixinStage() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int run() { return div(5, 2); }", TYPED + """
                public int added() { return Subject.div(7, 2); }
                """);
        var preparation = fixture.prepare();
        var plan = preparation.analyze(BytecodeFixtures.mergeForCoreTest(fixture, preparation));
        assertEquals(2, plan.callSiteCount());
        Map<String, byte[]> result = plan.apply();
        BytecodeFixtures.verify(result);
        Class<?> subject = BytecodeFixtures.loader(result).loadClass("sample.Subject");
        assertEquals(35, subject.getMethod("added").invoke(subject.getConstructor().newInstance()));
    }

    @Test
    void adaptsCallsInsideOtherTypedImplementations() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + """
                public static int other(int value) { return 0; }
                public static int run() { return div(5, 2) + other(2) + div(7, 2); }
                """, """
                @TypedOverwrite public static int other(double value) { return (int) value * 10; }
                @TypedOverwrite public static int div(double a, double b) { return Subject.other((int) a) + (int) b; }
                """);
        var preparation = fixture.prepare();
        var plan = preparation.analyze(BytecodeFixtures.mergeForCoreTest(fixture, preparation));
        assertEquals(4, plan.callSiteCount());
        Map<String, byte[]> result = plan.apply();
        BytecodeFixtures.verify(result);
        assertEquals(144, invoke(result, "sample.Subject", "run"));
    }

    @Test
    void handlesBranchesLoopsAndExceptionHandlers() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + """
                public static int run(boolean flag) {
                    int result = 0;
                    for (int index = 0; index < 3; index++) {
                        try { result += div(flag ? index + 3 : index + 5, index == 1 ? 0 : 2); }
                        catch (IllegalArgumentException failure) { result += 100; }
                    }
                    return result;
                }
                """, """
                @TypedOverwrite public static int div(double a, double b) {
                    if (b == 0) { throw new IllegalArgumentException(); }
                    return (int) (a / b * 10);
                }
                """);
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        Class<?> subject = BytecodeFixtures.loader(result).loadClass("sample.Subject");
        assertEquals(140, subject.getMethod("run", boolean.class).invoke(null, true));
        assertEquals(160, subject.getMethod("run", boolean.class).invoke(null, false));
    }

    @Test
    void keepsUnchangedReferenceAndBooleanParameters() throws Exception {
        var fixture = BytecodeFixtures.fixture("""
                public static int div(int a, String[] values, boolean enabled) { return 0; }
                public static int run() { return div(5, new String[]{"a", "b"}, true); }
                """, """
                @TypedOverwrite public static int div(double a, String[] values, boolean enabled) {
                    return enabled ? (int) a + values.length : 0;
                }
                """);
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(7, invoke(result, "sample.Subject", "run"));
    }

    @Test
    void resolvesInheritedStaticCallToItsDeclaringClass() throws Exception {
        var fixture = BytecodeFixtures.split(BytecodeFixtures.compile(Map.of(
                "sample.Subject", "package sample; public class Subject { " + ORIGINAL + " }",
                "sample.Child", """
                        package sample;
                        public class Child extends Subject {
                            public static int div(double a, double b) { return -1; }
                            public static int run() { return Child.div(5, 2); }
                        }
                        """,
                "sample.SubjectMixin", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(Subject.class)
                        public class SubjectMixin {
                            @love.crysh.mixinta.TypedOverwrite
                            public static int div(double a, double b) { return (int) (a / b * 10); }
                        }
                        """)));
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(25, invoke(result, "sample.Child", "run"));
    }

    @Test
    void computesFramesFromSuppliedClassHierarchy() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int run() { return div(5, 2); }", """
                @TypedOverwrite public static int div(double a, double b) {
                    Base value = a > b ? new Left() : new Right();
                    return value.value();
                }
                """, Map.of(
                "sample.Base", "package sample; public class Base { public int value() { return 0; } }",
                "sample.Left", "package sample; public class Left extends Base { public int value() { return 7; } }",
                "sample.Right", "package sample; public class Right extends Base { public int value() { return 9; } }"));
        Map<String, byte[]> result = fixture.transform();
        BytecodeFixtures.verify(result);
        assertEquals(7, invoke(result, "sample.Subject", "run"));
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                Arguments.of(ORIGINAL, "@TypedOverwrite public static double div(int a, int b) { return 0; }", "RETURN_TYPE_NOT_IMPLEMENTED"),
                Arguments.of(ORIGINAL, "@TypedOverwrite public static double div(double a, double b) { return 0; }", "RETURN_TYPE_NOT_IMPLEMENTED"),
                Arguments.of(ORIGINAL, "@TypedOverwrite public static int div(Integer a, int b) { return 0; }", "PARAMETER_CONVERSION_NOT_IMPLEMENTED"),
                Arguments.of(ORIGINAL, "@TypedOverwrite public static int div(boolean a, int b) { return 0; }", "PARAMETER_CONVERSION_NOT_IMPLEMENTED"),
                Arguments.of(ORIGINAL, "@TypedOverwrite public int div(int a, int b) { return 0; }", "STATIC_MISMATCH"),
                Arguments.of(ORIGINAL, "@TypedOverwrite private static int div(int a, int b) { return 0; }", "VISIBILITY_REDUCTION"),
                Arguments.of(ORIGINAL, "@TypedOverwrite(method = \"div(II)I\") public static int div(double a) { return 0; }", "PARAMETER_COUNT_MISMATCH"),
                Arguments.of(ORIGINAL, "@TypedOverwrite(method = \"div\") public static int div(int a, int b) { return 0; }", "INVALID_SELECTOR"),
                Arguments.of(ORIGINAL, "@TypedOverwrite(method = \"missing(II)I\") public static int div(int a, int b) { return 0; }", "TARGET_NOT_FOUND"),
                Arguments.of(ORIGINAL + " public static int div(float a, float b) { return 0; }", TYPED.replace("(method = \"div(II)I\")", ""), "AMBIGUOUS_TARGET"),
                Arguments.of(ORIGINAL + " public static int div(double a, double b) { return 0; }", TYPED, "SIGNATURE_COLLISION"));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidDeclarations(String original, String implementation, String expectedCode) {
        var fixture = BytecodeFixtures.fixture(original, implementation);
        Map<String, byte[]> before = ClassFiles.snapshot(fixture.originals());
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(expectedCode)), failure.getMessage());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, fixture.originals().get(name)));
    }

    @Test
    void aggregatesDeclarationErrorsAndKeepsDiagnosticOrderingStable() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int other(int a) { return a; }", """
                @TypedOverwrite public static double div(double a, boolean b) { return 0; }
                @TypedOverwrite public static int other(Object a) { return 0; }
                """);
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertEquals(3, failure.diagnostics().size());
        assertEquals(failure.getMessage(), assertThrows(TypedOverwriteException.class, fixture::prepare).getMessage());
        assertThrows(UnsupportedOperationException.class, () -> failure.diagnostics().clear());
    }

    @Test
    void rejectsMultipleOriginalsCollapsingToOneNewSignature() {
        var fixture = BytecodeFixtures.split(BytecodeFixtures.compile(Map.of(
                "sample.Subject", """
                        package sample;
                        public class Subject {
                            public static int div(int value) { return 0; }
                            public static int div(long value) { return 0; }
                        }
                        """,
                "sample.FirstMixin", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(Subject.class)
                        public class FirstMixin {
                            @love.crysh.mixinta.TypedOverwrite(method = "div(I)I")
                            public static int div(double value) { return 1; }
                        }
                        """,
                "sample.SecondMixin", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(Subject.class)
                        public class SecondMixin {
                            @love.crysh.mixinta.TypedOverwrite(method = "div(J)I")
                            public static int div(double value) { return 2; }
                        }
                        """)));
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SIGNATURE_COLLISION")));
    }

    @Test
    void reportsMissingMergedImplementation() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED);
        var preparation = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(fixture.originals()));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("IMPLEMENTATION_MISSING")));
    }

    @Test
    void rejectsChangesToOriginalMethodBeforeMigration() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        ClassNode node = ClassFiles.read(merged.get("sample/Subject"));
        MethodNode original = ClassFiles.findMethod(node, "div", "(II)I");
        original.instructions.set(Arrays.stream(original.instructions.toArray())
                .filter(instruction -> instruction.getOpcode() == Opcodes.IDIV).findFirst().orElseThrow(), new InsnNode(Opcodes.IADD));
        merged.put(node.name, ClassFiles.write(node));
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(merged));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("TARGET_CHANGED_AFTER_PREPARATION")));
    }

    @Test
    void reportsAllMalformedCallersWithoutChangingAnyInput() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED, Map.of(
                "sample.First", "package sample; public class First { public static int run() { return Subject.div(1, 2); } }",
                "sample.Second", "package sample; public class Second { public static int run() { return Subject.div(3, 4); } }"));
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        for (String name : List.of("sample/First", "sample/Second")) {
            ClassNode node = ClassFiles.read(merged.get(name));
            MethodNode caller = ClassFiles.findMethod(node, "run", "()I");
            var firstArgument = Arrays.stream(caller.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= Opcodes.ICONST_0
                    && instruction.getOpcode() <= Opcodes.ICONST_5).findFirst().orElseThrow();
            caller.instructions.set(firstArgument, new InsnNode(Opcodes.ACONST_NULL));
            merged.put(name, ClassFiles.write(node));
        }
        Map<String, byte[]> before = ClassFiles.snapshot(merged);
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(merged));
        assertEquals(2, failure.diagnostics().size());
        assertTrue(failure.diagnostics().stream().allMatch(diagnostic -> diagnostic.code().equals("ARGUMENT_TYPE_MISMATCH")));
        assertEquals(List.of("sample/First", "sample/Second"), failure.diagnostics().stream().map(diagnostic -> diagnostic.method().owner()).toList());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, merged.get(name)));
    }

    @Test
    void reportsMethodReferencesToMigratedSignature() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + """
                public static java.util.function.IntBinaryOperator reference() { return Subject::div; }
                """, TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(merged));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("METHOD_HANDLE_NOT_IMPLEMENTED")));
    }

    @Test
    void aggregatesMethodHandleConstantsAcrossClasses() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED, Map.of(
                "sample.First", "package sample; public class First {}",
                "sample.Second", "package sample; public class Second {}"));
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        for (String name : List.of("sample/First", "sample/Second")) {
            ClassNode node = ClassFiles.read(merged.get(name));
            MethodNode reference = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference", "()Ljava/lang/invoke/MethodHandle;", null, null);
            reference.instructions.add(new LdcInsnNode(new Handle(Opcodes.H_INVOKESTATIC, "sample/Subject", "div", "(II)I", false)));
            reference.instructions.add(new InsnNode(Opcodes.ARETURN));
            reference.maxStack = 1;
            node.methods.add(reference);
            merged.put(name, ClassFiles.write(node));
        }
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(merged));
        assertEquals(2, failure.diagnostics().size());
        assertTrue(failure.diagnostics().stream().allMatch(diagnostic -> diagnostic.code().equals("METHOD_HANDLE_NOT_IMPLEMENTED")));
    }

    @Test
    void findsMethodHandlesInsideNestedDynamicConstants() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        ClassNode subject = ClassFiles.read(merged.get("sample/Subject"));
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "explicitCast",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
        ConstantDynamic inner = new ConstantDynamic("inner", "Ljava/lang/invoke/MethodHandle;", bootstrap,
                new Handle(Opcodes.H_INVOKESTATIC, subject.name, "div", "(II)I", false));
        ConstantDynamic outer = new ConstantDynamic("outer", "Ljava/lang/invoke/MethodHandle;", bootstrap, inner);
        MethodNode reference = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference", "()Ljava/lang/invoke/MethodHandle;", null, null);
        reference.instructions.add(new LdcInsnNode(outer));
        reference.instructions.add(new InsnNode(Opcodes.ARETURN));
        reference.maxStack = 1;
        subject.methods.add(reference);
        merged.put(subject.name, ClassFiles.write(subject));
        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(merged));
        assertEquals("METHOD_HANDLE_NOT_IMPLEMENTED", failure.diagnostics().getFirst().code());
    }

    @Test
    void rejectsFinalMigrationTarget() {
        var fixture = BytecodeFixtures.fixture("@org.spongepowered.asm.mixin.Final " + ORIGINAL, TYPED);
        var preparation = fixture.prepare();
        var failure = assertThrows(TypedOverwriteException.class,
                () -> preparation.analyze(BytecodeFixtures.mergeForCoreTest(fixture, preparation)));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("FINAL_TARGET")));
    }

    @Test
    void rejectsUnimplementedVirtualDispatch() {
        var fixture = BytecodeFixtures.split(BytecodeFixtures.compile(Map.of(
                "sample.Subject", "package sample; public class Subject { public int div(int a, int b) { return 0; } }",
                "sample.SubjectMixin", """
                        package sample;
                        @org.spongepowered.asm.mixin.Mixin(Subject.class)
                        public class SubjectMixin {
                            @love.crysh.mixinta.TypedOverwrite
                            public int div(double a, double b) { return 1; }
                        }
                        """)));
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("VIRTUAL_DISPATCH_NOT_IMPLEMENTED")));
    }

    @Test
    void rejectsChangesToOverrideContracts() {
        var fixture = BytecodeFixtures.fixture("public int div(int a, int b) { return 0; }", TYPED.replace("static ", ""));
        ClassNode subject = ClassFiles.read(fixture.originals().get("sample/Subject"));
        subject.interfaces.add("sample/Contract");
        fixture.originals().put(subject.name, ClassFiles.write(subject));
        fixture.originals().putAll(BytecodeFixtures.compile(Map.of("sample.Contract",
                "package sample; public interface Contract { int div(int a, int b); }")));
        var failure = assertThrows(TypedOverwriteException.class, fixture::prepare);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("OVERRIDE_CHAIN_NOT_IMPLEMENTED")));
    }

    @Test
    void refusesOutputWithBrokenReplacementBytecodeAndRemainsRepeatable() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        ClassNode subject = ClassFiles.read(merged.get("sample/Subject"));
        MethodNode implementation = ClassFiles.findMethod(subject, "div", "(DD)I");
        implementation.instructions.clear();
        implementation.localVariables.clear();
        implementation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        implementation.instructions.add(new InsnNode(Opcodes.IRETURN));
        merged.put(subject.name, ClassFiles.write(subject));
        Map<String, byte[]> before = ClassFiles.snapshot(merged);
        TransformationPlan plan = preparation.analyze(merged);
        var failure = assertThrows(TypedOverwriteException.class, plan::apply);
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("OUTPUT_VERIFICATION_FAILED")));
        assertEquals(failure.getMessage(), assertThrows(TypedOverwriteException.class, plan::apply).getMessage());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, merged.get(name)));
    }

    @Test
    void preservesUnchangedClassesByteForByte() {
        var fixture = BytecodeFixtures.fixture(ORIGINAL, TYPED,
                Map.of("sample.Unrelated", "package sample; public class Unrelated { public static int value() { return 1; } }"));
        Map<String, byte[]> transformed = fixture.transform();
        assertArrayEquals(fixture.originals().get("sample/Unrelated"), transformed.get("sample/Unrelated"));
    }

    @Test
    void preparationAndPlanOwnTheirSnapshots() throws Exception {
        var fixture = BytecodeFixtures.fixture(ORIGINAL + " public static int run() { return div(5, 2); }", TYPED);
        var preparation = fixture.prepare();
        Map<String, byte[]> preparedMixins = preparation.mixins();
        Arrays.fill(preparedMixins.get("sample/SubjectMixin"), (byte) 0);
        Map<String, byte[]> merged = BytecodeFixtures.mergeForCoreTest(fixture, preparation);
        var plan = preparation.analyze(merged);
        merged.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        fixture.originals().values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        Map<String, byte[]> first = plan.apply();
        assertEquals(25, invoke(first, "sample.Subject", "run"));
        Arrays.fill(first.get("sample/Subject"), (byte) 0);
        assertEquals(25, invoke(plan.apply(), "sample.Subject", "run"));
    }

    private static Object invoke(Map<String, byte[]> classes, String owner, String method) throws Exception {
        return BytecodeFixtures.loader(classes).loadClass(owner).getMethod(method).invoke(null);
    }
}
