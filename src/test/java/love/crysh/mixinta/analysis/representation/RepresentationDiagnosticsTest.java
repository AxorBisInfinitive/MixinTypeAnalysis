package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.StorageId;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.testsupport.BytecodeFixtures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepresentationDiagnosticsTest {
    private static final MethodKey TARGET = new MethodKey("sample/Subject", "lookup", "(J)I");
    private static final MigrationEvidence DEMAND = new MigrationEvidence("pos", TARGET, 0, "Lsample/BlockPos;", List.of());

    @Test
    void fallsBackToAllocationWhenNoAccessCanBeDisplayed() {
        var slot = new StorageSlot(new StorageId(new MethodKey("sample/Subject", "run", "()V"), 7, "Ljava/util/ArrayDeque;"), 1);
        var storages = new LinkedHashSet<>(Set.of(slot));
        var conflict = conflict(slot).withStorages(storages);
        storages.clear();
        var facts = facts(slot);

        var diagnostic = RepresentationDiagnostics.collect(List.of(), List.of(conflict), facts).getFirst();

        assertEquals(Set.of(slot), conflict.storages());
        assertThrows(UnsupportedOperationException.class, () -> conflict.storages().clear());
        assertEquals(slot.storage().method(), diagnostic.method());
        assertEquals(7, diagnostic.instruction());
        assertEquals("java/util/ArrayDeque", diagnostic.failureSymbol());
        var context = diagnostic.containers().getFirst();
        assertEquals(context.storage(), context.access());
        assertEquals("ALLOCATE", context.operation());
        assertEquals(1, context.channel());
        assertEquals(ElementState.CONFLICT, context.state());
        assertEquals(ConstraintGraph.storage(slot).point(), conflict.point());
    }

    @Test
    void selectsReadFromCompleteReportsInStableAccessOrder() {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        BytecodeFixtures.compile(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static void run() {
                        java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                        queue.add(1L);
                        queue.peek();
                        queue.poll();
                    }
                }
                """)).forEach((name, bytes) -> classes.put(name, ClassFiles.read(bytes)));
        var input = input(classes);
        var write = input.containerUses().stream().filter(use -> use.summary() != null && use.summary().writesElement()).findFirst().orElseThrow();
        var slot = write.receivers().slots(write.summary().channel()).iterator().next();
        var read = input.containerUses().stream().filter(use -> use.summary() != null && use.summary().readsElement())
                .min(java.util.Comparator.comparing(use -> use.site().point().key())).orElseThrow();
        assertTrue(write.site().index() < read.site().index());
        var complete = report(input);
        var empty = report(input(Map.of()));
        var conflict = conflict(slot);
        var facts = facts(slot);
        var expected = RepresentationDiagnostics.collect(List.of(complete, empty), List.of(conflict), facts);

        assertEquals(expected, RepresentationDiagnostics.collect(List.of(empty, complete), List.of(conflict), facts));
        assertEquals(read.site().point(), expected.getFirst().containers().getFirst().access());
        assertEquals(read.summary().operation().name(), expected.getFirst().containers().getFirst().operation());
        assertEquals(read.site().index(), expected.getFirst().instruction());
        assertEquals(ConstraintGraph.storage(slot).point(), conflict.point());
    }

    private RepresentationInput input(Map<String, ClassNode> classes) {
        var hierarchy = new ClassHierarchy(classes, getClass().getClassLoader());
        var containers = ContainerAnalysis.analyzeAll(classes, Set.of(), hierarchy, List.of(List.of()), new AnalysisMetrics()).get(Set.of());
        return new RepresentationInput(containers, Set.of(TARGET));
    }

    private static RepresentationDiagnostics report(RepresentationInput input) {
        var declaration = new RepresentationMigration("pos", TARGET, 0, Type.getObjectType("sample/BlockPos"),
                "sample/Rules", List.of(), List.of());
        var facts = new RepresentationFacts(Set.of(), Set.of(), Set.of(), Set.of(), List.of(), new ConstraintGraph(),
                Map.of(), Set.of(), Set.of(), Set.of());
        return new RepresentationDiagnostics(declaration, input, new ValidationResult(facts, Map.of(), List.of()), new ElementFacts());
    }

    private static BatchConflict conflict(StorageSlot slot) {
        return new BatchConflict("CONTAINER_FACT_CONFLICT", ConstraintGraph.storage(slot).point(), -1,
                "Incompatible representations for one storage channel", List.of(DEMAND)).withStorages(Set.of(slot));
    }

    private static ElementFacts facts(StorageSlot slot) {
        var facts = new ElementFacts();
        var fact = new ElementFacts.Fact();
        fact.add(DEMAND, true);
        fact.conflict();
        facts.storages.put(slot, fact);
        return facts;
    }
}
