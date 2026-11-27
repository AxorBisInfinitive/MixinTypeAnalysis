package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.testsupport.BytecodeFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MethodAnalysisScopeTest {
    @Test
    void followsCallsFieldsDispatchAndInitializationWhilePruningDisconnectedMethods() {
        Map<String, ClassNode> nodes = nodes(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static int lookup(long value) { return (int) value; }
                    public static int use(Reader reader) { return lookup(reader.read()); }
                    public interface Reader { long read(); }
                    public static class Storage {
                        public static long saved = create();
                        static long create() { return 1L; }
                    }
                    public static class Writer {
                        public static void write() { Storage.saved = 2L; }
                    }
                    public static class Concrete implements Reader {
                        public long read() { return Storage.saved; }
                    }
                    public static class Unrelated {
                        public static int ignored() { return 42; }
                    }
                }
                """));
        Set<MethodKey> selected = select(nodes);
        assertTrue(selected.contains(key("Subject$Concrete", "read", "()J")));
        assertTrue(selected.contains(key("Subject$Writer", "write", "()V")));
        assertTrue(selected.contains(key("Subject$Storage", "<clinit>", "()V")));
        assertTrue(selected.contains(key("Subject$Storage", "create", "()J")));
        assertFalse(selected.contains(key("Subject$Unrelated", "ignored", "()I")));
        assertTrue(selected.size() < nodes.values().stream().mapToInt(node -> node.methods.size()).sum());
    }

    @Test
    void followsLambdaBootstrapAndFieldHandleReferences() {
        Map<String, ClassNode> nodes = nodes(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static int lookup(long value) { return (int) value; }
                    public static int use() { return lookup(Worker.source().getAsLong()); }
                    public static class Worker {
                        public static long saved;
                        public static java.util.function.LongSupplier source() { return Worker::read; }
                        private static long read() { return saved; }
                    }
                    public static class HandleOwner { public static void expose() { } }
                }
                """));
        var expose = ClassFiles.findMethod(nodes.get("sample/Subject$HandleOwner"), "expose", "()V");
        expose.instructions.insert(new LdcInsnNode(new Handle(Opcodes.H_GETSTATIC, "sample/Subject$Worker", "saved", "J", false)));
        expose.instructions.insertBefore(expose.instructions.getLast(), new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        Set<MethodKey> selected = select(nodes);
        assertTrue(selected.contains(key("Subject$Worker", "read", "()J")));
        assertTrue(selected.contains(key("Subject$HandleOwner", "expose", "()V")));
    }

    @Test
    void expandsUnknownDispatchToMatchingImplementations() {
        Map<String, ClassNode> nodes = nodes(Map.of("sample.Subject", """
                package sample;
                public class Subject {
                    public static int lookup(long value) { return (int) value; }
                    public static int use(Reader reader) { return lookup(reader.read()); }
                    public interface Reader { long read(); }
                    public static class Candidate { public long read() { return 42L; } }
                }
                """));
        nodes.remove("sample/Subject$Reader");
        assertTrue(select(nodes).contains(key("Subject$Candidate", "read", "()J")));
    }

    private static Set<MethodKey> select(Map<String, ClassNode> nodes) {
        return MethodAnalysisScope.select(nodes, Set.of(key("Subject", "lookup", "(J)I")),
                new ClassHierarchy(nodes, MethodAnalysisScopeTest.class.getClassLoader()));
    }

    private static MethodKey key(String owner, String name, String descriptor) {
        return new MethodKey("sample/" + owner, name, descriptor);
    }

    private static Map<String, ClassNode> nodes(Map<String, String> sources) {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        BytecodeFixtures.compile(sources).forEach((name, bytes) -> nodes.put(name, ClassFiles.read(bytes)));
        return nodes;
    }
}
