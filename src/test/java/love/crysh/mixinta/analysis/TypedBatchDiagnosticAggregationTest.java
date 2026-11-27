package love.crysh.mixinta.analysis;

import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedBatchDiagnosticAggregationTest {
    @Test
    void includesPrimitiveCallProblemsWhenRepresentationAnalysisFails() {
        var fixture = BytecodeFixtures.fixture("""
                public static int lookup(long pos) { return (int) pos; }
                public static int div(int a, int b) { return a / b; }
                public static int run() {
                    java.util.function.IntBinaryOperator divide = Subject::div;
                    return lookup(12L) + divide.applyAsInt(12, 3);
                }
                """, """
                @TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=sample.Rules.class))
                public static int lookup(sample.BlockPos pos) { return 12; }
                @TypedOverwrite
                public static int div(double a, double b) { return (int) (a / b); }
                """, Map.of(
                        "sample.BlockPos", "package sample; public record BlockPos(long value) { }",
                        "sample.Rules", """
                                package sample;
                                public class Rules {
                                    @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE, method="sample.BlockPos.value()J")
                                    public static BlockPos keep(BlockPos pos) { return pos; }
                                }
                                """));
        var preparation = fixture.prepare();
        var input = BytecodeFixtures.mergeForCoreTest(fixture, preparation);

        var failure = assertThrows(TypedOverwriteException.class, () -> preparation.analyze(input));

        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MISSING_PRODUCER_RULE")));
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("METHOD_HANDLE_NOT_IMPLEMENTED")));
    }
}
