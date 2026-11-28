package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceJoinerTest {
    @Test
    void growingUnionsPreserveOverlapsAndSealBothComponents() {
        var first = new LinkedHashSet<ProgramPoint>();
        var second = new LinkedHashSet<ProgramPoint>();
        for (int index = 0; index < 64; index++) first.add(new ProgramPoint(METHOD, index, "value"));
        for (int index = 32; index < 96; index++) second.add(new ProgramPoint(METHOD, index, "value"));
        var joiner = new ReferenceJoiner();
        var joined = joiner.join(facts(Set.of(KEY), first, false), facts(Set.of(VALUE), second, true));
        assertEquals(96, joined.unknownOrigins().size());
        assertEquals(Set.of(KEY, VALUE), joined.references());
        assertTrue(joined.nullable());
        first.clear();
        second.clear();
        assertEquals(96, joined.unknownOrigins().size());
        var metrics = new AnalysisMetrics();
        joiner.recordMetrics(metrics);
        assertEquals(98, metrics.count(AnalysisMetrics.Count.REFERENCE_SEALED_ELEMENTS));
    }
    private static final MethodKey METHOD = new MethodKey("sample/Subject", "run", "()V");
    private static final StorageId STORAGE = new StorageId(METHOD, 3, "Ljava/util/Map;");
    private static final ContainerReference KEY = new ContainerReference(STORAGE, 0, ContainerReference.Kind.KEYS);
    private static final ContainerReference VALUE = new ContainerReference(STORAGE, 1, ContainerReference.Kind.VALUES);
    private static final ProgramPoint FIRST = new ProgramPoint(METHOD, 5, "read", "first");
    private static final ProgramPoint SECOND = new ProgramPoint(METHOD, 5, "read", "second");

    @Test
    void reusesIdentityEqualFactsAndBothContainmentDirections() {
        var joiner = new ReferenceJoiner();
        var subset = facts(Set.of(KEY), Set.of(FIRST), false);
        var superset = facts(Set.of(KEY, VALUE), Set.of(FIRST, SECOND), true);
        assertSame(subset, joiner.join(subset, subset));
        assertSame(subset, joiner.join(subset, facts(Set.of(KEY), Set.of(FIRST), false)));
        assertSame(superset, joiner.join(subset, superset));
        assertSame(superset, joiner.join(superset, subset));
        // 相等分量可来自不同 Set 实例，分量身份不决定事实包含关系。
        var equalUnknown = facts(Set.of(KEY), Set.of(FIRST, SECOND), true);
        assertSame(superset, joiner.join(equalUnknown, superset));
        assertSame(superset, joiner.join(superset, equalUnknown));
    }

    @Test
    void preservesNullAsAnIndependentFact() {
        var joiner = new ReferenceJoiner();
        var empty = facts(Set.of(), Set.of(), false);
        var nil = facts(Set.of(), Set.of(), true);
        var value = facts(Set.of(KEY), Set.of(), false);
        assertTrue(ReferenceJoiner.empty(empty));
        assertFalse(ReferenceJoiner.empty(nil));
        assertSame(nil, joiner.join(empty, nil));
        assertSame(nil, joiner.join(nil, empty));
        assertEquals(facts(Set.of(KEY), Set.of(), true), joiner.join(nil, value));
        assertEquals(facts(Set.of(KEY), Set.of(), true), joiner.join(value, nil));
    }

    @Test
    void retainsChannelsKindsAndCompleteUnknownLocations() {
        var entry = new ContainerReference(STORAGE, 1, ContainerReference.Kind.ENTRY);
        var joiner = new ReferenceJoiner();
        var first = facts(Set.of(KEY, VALUE), Set.of(FIRST), false);
        var second = facts(Set.of(entry), Set.of(SECOND), false);
        var joined = joiner.join(first, second);
        assertEquals(Set.of(KEY, VALUE, entry), joined.references());
        assertEquals(Set.of(FIRST, SECOND), joined.unknownOrigins());
        assertEquals(Set.of(STORAGE), joined.storages());
        assertThrows(UnsupportedOperationException.class, () -> joined.references().clear());
        assertThrows(UnsupportedOperationException.class, () -> joined.unknownOrigins().clear());
    }

    @Test
    void matchesTheUnionEquationForAllSmallFactCombinations() {
        var joiner = new ReferenceJoiner();
        List<Set<ContainerReference>> references = List.of(Set.of(), Set.of(KEY), Set.of(VALUE), Set.of(KEY, VALUE));
        List<Set<ProgramPoint>> unknowns = List.of(Set.of(), Set.of(FIRST), Set.of(SECOND), Set.of(FIRST, SECOND));
        for (var firstReferences : references) for (var secondReferences : references) {
            for (var firstUnknown : unknowns) for (var secondUnknown : unknowns) {
                for (boolean firstNull : new boolean[]{false, true}) for (boolean secondNull : new boolean[]{false, true}) {
                    var first = facts(firstReferences, firstUnknown, firstNull);
                    var second = facts(secondReferences, secondUnknown, secondNull);
                    var expectedReferences = new LinkedHashSet<>(firstReferences);
                    expectedReferences.addAll(secondReferences);
                    var expectedUnknown = new LinkedHashSet<>(firstUnknown);
                    expectedUnknown.addAll(secondUnknown);
                    assertEquals(facts(expectedReferences, expectedUnknown, firstNull || secondNull), joiner.join(first, second));
                }
            }
        }
    }

    @Test
    void reportsReuseGrowthAndCopiedElementsAtTheBoundary() {
        var joiner = new ReferenceJoiner();
        var first = facts(Set.of(KEY), Set.of(FIRST), false);
        var second = facts(Set.of(VALUE), Set.of(SECOND), false);
        joiner.join(first, first);
        joiner.join(first, second);
        var metrics = new AnalysisMetrics();
        joiner.recordMetrics(metrics);
        assertEquals(2, metrics.count(AnalysisMetrics.Count.REFERENCE_JOINS));
        assertEquals(1, metrics.count(AnalysisMetrics.Count.REFERENCE_REUSES));
        assertEquals(1, metrics.count(AnalysisMetrics.Count.REFERENCE_GROWTHS));
        assertEquals(4, metrics.count(AnalysisMetrics.Count.REFERENCE_COPIED_ELEMENTS));
        joiner.recordMetrics(metrics);
        assertEquals(2, metrics.count(AnalysisMetrics.Count.REFERENCE_JOINS));
    }

    private static StorageReferences facts(Set<ContainerReference> references, Set<ProgramPoint> unknown, boolean nullable) {
        return new StorageReferences(references, unknown, nullable);
    }
}
