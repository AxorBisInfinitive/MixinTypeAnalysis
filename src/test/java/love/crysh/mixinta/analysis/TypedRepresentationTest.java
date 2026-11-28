package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedRepresentationTest {
    @Test
    void preparesAndAppliesReferenceRepresentationRuleReader() throws Exception {
        var fixture = fixture();
        var prepared = fixture.prepare();
        var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var output = prepared.analyze(merged).apply();
        BytecodeFixtures.verify(output);
        var loader = BytecodeFixtures.loader(output);
        var subject = loader.loadClass("sample.Subject");
        assertEquals(12, subject.getMethod("run", long.class).invoke(null, 12L));
        assertNull(ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "lookup", "(J)I"));
        assertNotNull(ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "lookup", "(Lsample/BlockPos;)I"));
    }

    @Test
    void rejectsInvalidDataflowInAnUnrelatedInputMethod() {
        var fixture = fixture();
        var prepared = fixture.prepare();
        var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        ClassWriter malformed = new ClassWriter(0);
        malformed.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "sample/Unrelated", null, "java/lang/Object", null);
        var method = malformed.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "broken", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        malformed.visitEnd();
        merged.put("sample/Unrelated", malformed.toByteArray());
        love.crysh.mixinta.testsupport.AnalysisComparison.capture(fixture.originals(), fixture.mixins(), merged);

        var failure = assertThrows(TypedOverwriteException.class, () -> prepared.analyze(merged));
        var location = new MethodKey("sample/Unrelated", "broken", "()V");
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("INVALID_PROPAGATION_INPUT") && location.equals(diagnostic.method())));
        var measuredFailure = assertThrows(TypedOverwriteException.class,
                () -> prepared.analyze(merged, new AnalysisMetrics()));
        assertEquals(failure.diagnostics(), measuredFailure.diagnostics());
    }

    private static BytecodeFixtures.Fixture fixture() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong(){return value;} }");
        sources.put("sample.Subject", "package sample; public final class Subject { public static int lookup(long value){return (int)value;} public static int run(long value){return lookup(new BlockPos(value).asLong());} }");
        sources.put("sample.SubjectMixin", """
                package sample;
                import love.crysh.mixinta.anno.ParameterMigration;
                import love.crysh.mixinta.anno.TypeRule;
                import love.crysh.mixinta.anno.TypedOverwrite;
                import org.spongepowered.asm.mixin.Mixin;
                @Mixin(value=Subject.class, remap=false)
                public class SubjectMixin {
                    @TypedOverwrite(method="lookup(J)I", parameters=@ParameterMigration(parameter=0, rules=SubjectMixin.class))
                    public static int lookup(BlockPos value){return (int)value.value();}
                    @TypeRule(value=TypeRule.Kind.SOURCE, method="sample.BlockPos.asLong()J")
                    public static BlockPos keep(BlockPos value){return value;}
                    @TypeRule(value=TypeRule.Kind.ENCODE)
                    public static long encode(BlockPos value){return value.value();}
                    @TypeRule(value=TypeRule.Kind.READ)
                    public static BlockPos read(BlockPos value){return java.util.Objects.requireNonNull(value);}
                }
                """);
        return BytecodeFixtures.split(BytecodeFixtures.compile(sources));
    }
}
