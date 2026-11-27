package love.crysh.mixinta.bytecode;

import org.junit.jupiter.api.Test;
import love.crysh.mixinta.testsupport.BytecodeFixtures;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassHierarchyTest {
    @Test
    void readsDependenciesThroughTheSelectedBytecodeSource() {
        var classes = BytecodeFixtures.compile(Map.of("sample.Dependency",
                "package sample; public class Dependency { private static int value(){ return 1; } }"));
        var transformed = ClassFiles.read(classes.get("sample/Dependency"));
        transformed.methods.stream().filter(method -> method.name.equals("value"))
                .forEach(method -> method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        byte[] bytes = ClassFiles.write(transformed);
        var hierarchy = new ClassHierarchy(Map.of(), (ClassByteSource) name -> bytes);
        var method = ClassFiles.findMethod(hierarchy.get("sample/Dependency"), "value", "()I");
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method.access);
    }

    @Test
    void preservesBytecodeSourceFailures() {
        var hierarchy = new ClassHierarchy(Map.of(), (ClassByteSource) name -> {
            throw new java.io.IOException("input-stage-failure");
        });
        var failure = assertThrows(IllegalArgumentException.class, () -> hierarchy.get("sample/Missing"));
        assertEquals("input-stage-failure", failure.getCause().getMessage());
    }
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
