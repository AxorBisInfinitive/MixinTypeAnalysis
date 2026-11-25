package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassHierarchyTest {
    @Test
    void computesArrayAndInterfaceFrameMerges() {
        var hierarchy = new ClassHierarchy(Map.of(), getClass().getClassLoader());
        assertEquals("[Ljava/lang/Object;", hierarchy.commonSuperType("[Ljava/lang/String;", "[Ljava/lang/Integer;"));
        assertEquals("[Ljava/lang/Object;", hierarchy.commonSuperType("[[I", "[[J"));
        assertEquals("java/lang/Object", hierarchy.commonSuperType("[I", "[J"));
        assertEquals("[[I", hierarchy.commonSuperType("[[I", "[[I"));
        assertEquals("java/lang/Cloneable", hierarchy.commonSuperType("[I", "java/lang/Cloneable"));
        assertEquals("java/util/List", hierarchy.commonSuperType("java/util/ArrayList", "java/util/List"));
    }

    @Test
    void reportsMissingHierarchyMetadata() {
        var hierarchy = new ClassHierarchy(Map.of(), getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class, () -> hierarchy.commonSuperType("missing/First", "missing/Second"));
    }
}
