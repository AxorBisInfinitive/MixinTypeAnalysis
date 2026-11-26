package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedRepresentationTest {
    @Test
    void preparesAndAppliesReferenceRepresentationRules() throws Exception {
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
        var fixture = BytecodeFixtures.split(BytecodeFixtures.compile(sources));
        var prepared = new TypedOverwriteAnalyzer().prepare(fixture.originals(), fixture.mixins());
        var merged = BytecodeFixtures.mergeForCoreTest(fixture, prepared);
        var output = prepared.analyze(merged).apply();
        BytecodeFixtures.verify(output);
        var loader = BytecodeFixtures.loader(output);
        var subject = loader.loadClass("sample.Subject");
        assertEquals(12, subject.getMethod("run", long.class).invoke(null, 12L));
        assertNull(ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "lookup", "(J)I"));
        assertNotNull(ClassFiles.findMethod(ClassFiles.read(output.get("sample/Subject")), "lookup", "(Lsample/BlockPos;)I"));
    }
}
