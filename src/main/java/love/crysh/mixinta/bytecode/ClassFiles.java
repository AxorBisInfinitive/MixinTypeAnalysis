package love.crysh.mixinta.bytecode;

import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ClassFiles {
    public static final String TYPED_OVERWRITE = "Llove/crysh/mixinta/anno/TypedOverwrite;";
    public static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    public static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    public static final String FINAL = "Lorg/spongepowered/asm/mixin/Final;";
    public static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

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

    public static Map<String, ClassNode> readAll(Map<String, byte[]> classes, List<Diagnostic> diagnostics) {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> {
            try {
                ClassNode node = read(bytes);
                if (!name.equals(node.name)) {
                    diagnostics.add(new Diagnostic("CLASS_NAME_MISMATCH", null, -1, -1,
                            name + " contains " + node.name));
                } else {
                    nodes.put(name, node);
                }
            } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic("INVALID_CLASS", null, -1, -1, name + " " + failure.getMessage()));
            }
        });
        return nodes;
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

    public static void throwIfErrors(List<Diagnostic> diagnostics) {
        if (!diagnostics.isEmpty()) {
            throw new TypedOverwriteException(diagnostics);
        }
    }
}
