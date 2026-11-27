package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepresentationStagesTest {
    @Test
    void validationAddsUnresolvedFactsWithoutChangingPropagationOrInitialBatchFacts() {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        BytecodeFixtures.compile(Map.of(
                "sample.BlockPos", "package sample; public record BlockPos(long value) { public long asLong() { return value; } }",
                "sample.Rules", "package sample; public class Rules { public static BlockPos keep(BlockPos pos) { return pos; } }",
                "sample.Subject", """
                        package sample;
                        public class Subject {
                            public static int lookup(long pos) { return (int) pos; }
                            public static int run() {
                                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                                queue.add(new BlockPos(12).asLong());
                                return lookup(queue.poll());
                            }
                        }
                        """)).forEach((name, bytes) -> classes.put(name, ClassFiles.read(bytes)));
        var target = new MethodKey("sample/Subject", "lookup", "(J)I");
        var sourceRule = new RepresentationRule("keep", 1, TypeRule.Kind.SOURCE,
                new MethodKey("sample/BlockPos", "asLong", "()J"),
                new MethodKey("sample/Rules", "keep", "(Lsample/BlockPos;)Lsample/BlockPos;"),
                TypeRule.Binding.RECEIVER, 0, "sample/Rules");
        var declaration = new RepresentationMigration("pos", target, 0, Type.getObjectType("sample/BlockPos"),
                "sample/Rules", List.of(sourceRule), List.of());
        var targets = Map.of(target, new Migration(target, new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;)I"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, List.of(declaration)));
        var hierarchy = new ClassHierarchy(classes, getClass().getClassLoader());
        var containers = ContainerAnalysis.analyzeAll(classes, targets.keySet(), hierarchy, List.of(List.of())).get(Set.of());
        var input = new RepresentationInput(containers);
        var propagation = new RepresentationSolver(List.of(declaration), targets, hierarchy).solve(input);
        var initial = new ElementFacts();
        initial.add(List.of(declaration), propagation.facts());
        var originalDependencies = propagation.facts().dependencies();
        var originalEvidence = propagation.facts().graph().evidence(propagation.facts().requested().stream()
                .map(location -> ConstraintGraph.value(propagation.facts().points().get(location))).toList());

        var validation = new RepresentationValidator(declaration, propagation, input, targets, classes, hierarchy, initial).validate();
        var validated = new ElementFacts();
        validated.add(List.of(declaration), validation.facts());

        assertTrue(propagation.issues().isEmpty());
        assertFalse(propagation.facts().storages().isEmpty());
        assertTrue(propagation.facts().unresolvedValues().isEmpty());
        assertTrue(initial.storages.values().stream().allMatch(fact -> fact.state() == ElementState.MIGRATED));
        assertTrue(validation.issues().stream().anyMatch(issue -> issue.code().equals("MISSING_READ_RULE")));
        assertFalse(validation.facts().unresolvedStorages().isEmpty());
        assertTrue(validated.storages.values().stream().anyMatch(fact -> fact.state() == ElementState.UNKNOWN));
        assertEquals(propagation.rules(), validation.rules());
        assertEquals(originalDependencies, propagation.facts().dependencies());
        assertEquals(originalEvidence, propagation.facts().graph().evidence(propagation.facts().requested().stream()
                .map(location -> ConstraintGraph.value(propagation.facts().points().get(location))).toList()));
        assertThrows(UnsupportedOperationException.class, () -> propagation.facts().requested().clear());
        assertThrows(UnsupportedOperationException.class, () -> validation.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> validation.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.sites().clear());

        var unmodeled = new RepresentationMigration("pos", target, 0, declaration.representation(), "sample/Rules", List.of(), List.of());
        var unresolved = new RepresentationSolver(List.of(unmodeled), targets, hierarchy).solve(input);
        assertTrue(unresolved.facts().requested().containsAll(unresolved.facts().migrated()));
        assertNotEquals(unresolved.facts().requested(), unresolved.facts().migrated());
        assertTrue(unresolved.issues().stream().anyMatch(issue -> issue.code().equals("MISSING_PRODUCER_RULE")));
        assertTrue(propagation.issues().isEmpty());
    }

    @Test
    void issueCopiesCandidateAndAnchorCollections() {
        var candidates = new ArrayList<>(List.of("candidate"));
        var issue = new RepresentationIssue("MISSING_READ_RULE", null, -1, "missing", candidates, Set.of(), Set.of(), "receiver");
        candidates.clear();

        assertEquals(List.of("candidate"), issue.candidates());
        assertThrows(UnsupportedOperationException.class, () -> issue.candidates().clear());
    }
}
