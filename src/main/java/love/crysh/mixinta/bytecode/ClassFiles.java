package love.crysh.mixinta.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ClassFiles {

    private ClassFiles() {
    }

    public static Map<String, byte[]> snapshot(Map<String, byte[]> classes) {
        Map<String, byte[]> copy = new TreeMap<>();
        classes.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return copy;
    }

    public static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    public static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    public static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElse(null);
    }

    public static Object value(AnnotationNode annotation, String name, Object fallback) {
        if (annotation == null || annotation.values == null) {
            return fallback;
        }
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return fallback;
    }

    public static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

}
