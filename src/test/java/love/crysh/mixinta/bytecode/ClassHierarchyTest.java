package love.crysh.mixinta.bytecode;

import org.junit.jupiter.api.Test;
import love.crysh.mixinta.testsupport.BytecodeFixtures;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassHierarchyTest {
    @Test
    void readOnlyQueriesCacheCompleteSignaturesNegativeResultsAndDirectionalAssignments() {
        var hierarchy = new ClassHierarchy(Map.of(), getClass().getClassLoader()).readOnlyQueries();
        assertNull(hierarchy.resolve("java/lang/Object", "absent", "()V"));
        assertNull(hierarchy.resolve("java/lang/Object", "absent", "()V"));
        var noArguments = hierarchy.resolve("java/lang/String", "substring", "(I)Ljava/lang/String;");
        var twoArguments = hierarchy.resolve("java/lang/String", "substring", "(II)Ljava/lang/String;");
        assertNotEquals(noArguments, twoArguments);
        assertEquals(noArguments, hierarchy.resolve("java/lang/String", "substring", "(I)Ljava/lang/String;"));
        assertTrue(hierarchy.isAssignable("java/lang/CharSequence", "java/lang/String"));
        assertFalse(hierarchy.isAssignable("java/lang/String", "java/lang/CharSequence"));
        assertTrue(hierarchy.isAssignable("java/lang/CharSequence", "java/lang/String"));
        assertFalse(hierarchy.isAssignable("java/lang/String", "java/lang/CharSequence"));
        assertTrue(hierarchy.isAssignable("[Ljava/lang/Object;", "[Ljava/lang/String;"));
        assertFalse(hierarchy.isAssignable("[Ljava/lang/String;", "[Ljava/lang/Object;"));
        assertTrue(hierarchy.queryStats().hits() >= 4);
        assertTrue(hierarchy.queryStats().nodes() > 0);
    }

    @Test
    void ordinaryHierarchyAndNewScopesObserveMutableNodesAndMetadataFailuresAreRetried() {
        var node = new org.objectweb.asm.tree.ClassNode();
        node.name = "sample/Subject";
        node.superName = "java/lang/Object";
        var hierarchy = new ClassHierarchy(Map.of(node.name, node), getClass().getClassLoader());
        assertNull(hierarchy.resolve(node.name, "added", "()V"));
        assertNull(hierarchy.readOnlyQueries().resolve(node.name, "added", "()V"));
        node.methods.add(new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "added", "()V", null, null));
        assertNotNull(hierarchy.resolve(node.name, "added", "()V"));
        assertNotNull(hierarchy.readOnlyQueries().resolve(node.name, "added", "()V"));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var missing = new ClassHierarchy(Map.of(), (ClassByteSource) name -> {
            reads.incrementAndGet();
            throw new java.io.IOException("metadata-failure");
        }).readOnlyQueries();
        for (int index = 0; index < 2; index++) {
            var failure = assertThrows(IllegalArgumentException.class, () -> missing.resolve("missing/Type", "run", "()V"));
            assertEquals("metadata-failure", failure.getCause().getMessage());
        }
        assertEquals(2, reads.get());
        assertEquals(2, missing.queryStats().failures());
    }

    @Test
    void virtualCacheDistinguishesReceiversAndInterfaceDefaults() {
        var bytes = BytecodeFixtures.compile(Map.of(
                "sample.Parent", "package sample; public interface Parent { default int value() { return 1; } }",
                "sample.First", "package sample; public class First implements Parent {}",
                "sample.Second", "package sample; public class Second implements Parent { public int value() { return 2; } }"));
        var nodes = new java.util.HashMap<String, org.objectweb.asm.tree.ClassNode>();
        bytes.forEach((name, data) -> nodes.put(name, ClassFiles.read(data)));
        var hierarchy = new ClassHierarchy(nodes, getClass().getClassLoader()).readOnlyQueries();
        assertEquals("sample/Parent", hierarchy.resolveVirtual("sample/First", "value", "()I").owner());
        assertEquals("sample/Second", hierarchy.resolveVirtual("sample/Second", "value", "()I").owner());
        assertEquals("sample/Parent", hierarchy.resolveVirtual("sample/First", "value", "()I").owner());
        assertTrue(hierarchy.queryStats().hits() > 0);
    }
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
