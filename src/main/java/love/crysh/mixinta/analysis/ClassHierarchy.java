package love.crysh.mixinta.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 从字节码读取层次信息，计算栈帧期间避免定义或初始化被分析的类。 */
final class ClassHierarchy {
    private final Map<String, ClassNode> classes;
    private final ClassLoader resources;

    ClassHierarchy(Map<String, ClassNode> classes, ClassLoader resources) {
        this.classes = new HashMap<>(classes);
        this.resources = resources;
    }

    ClassNode get(String name) {
        return classes.computeIfAbsent(name, this::readResource);
    }

    MethodKey resolve(String owner, String name, String descriptor) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            ClassNode node = get(current);
            if (ClassFiles.findMethod(node, name, descriptor) != null) {
                return new MethodKey(current, name, descriptor);
            }
            if (node.superName != null) {
                pending.addFirst(node.superName);
            }
            pending.addAll(node.interfaces);
        }
        return null;
    }

    boolean hasInheritedMethod(ClassNode node, String name, String descriptor) {
        if (node.superName != null && isInherited(node, resolve(node.superName, name, descriptor))) {
            return true;
        }
        return node.interfaces.stream().anyMatch(parent -> isInherited(node, resolve(parent, name, descriptor)));
    }

    /** 实例派发先选择类方法，再选择最具体接口中的唯一 default 实现。 */
    MethodKey resolveVirtual(String owner, String name, String descriptor) {
        Set<String> interfaces = new HashSet<>();
        Set<String> visited = new HashSet<>();
        String current = owner;
        while (current != null && visited.add(current)) {
            ClassNode node = get(current);
            interfaces.addAll(node.interfaces);
            var method = ClassFiles.findMethod(node, name, descriptor);
            if (method != null && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                return new MethodKey(current, name, descriptor);
            }
            current = node.superName;
        }
        Set<MethodKey> candidates = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(interfaces);
        visited.clear();
        while (!pending.isEmpty()) {
            current = pending.removeFirst();
            if (!visited.add(current)) continue;
            ClassNode node = get(current);
            var method = ClassFiles.findMethod(node, name, descriptor);
            if (method != null && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                candidates.add(new MethodKey(current, name, descriptor));
            }
            pending.addAll(node.interfaces);
        }
        var implementations = candidates.stream()
                .filter(candidate -> candidates.stream().noneMatch(other -> !other.equals(candidate)
                        && isAssignable(candidate.owner(), other.owner())))
                .filter(candidate -> (ClassFiles.findMethod(get(candidate.owner()), name, descriptor).access & Opcodes.ACC_ABSTRACT) == 0)
                .toList();
        return implementations.size() == 1 ? implementations.getFirst() : null;
    }

    private boolean isInherited(ClassNode child, MethodKey key) {
        if (key == null) {
            return false;
        }
        var method = ClassFiles.findMethod(get(key.owner()), key.name(), key.descriptor());
        if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
            return false;
        }
        return (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                || packageName(child.name).equals(packageName(key.owner()));
    }

    private static String packageName(String name) {
        int separator = name.lastIndexOf('/');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return commonSuperType(first, second);
            }
        };
    }

    String commonSuperType(String first, String second) {
        if (isAssignable(first, second)) {
            return first;
        }
        if (isAssignable(second, first)) {
            return second;
        }
        if (first.startsWith("[") && second.startsWith("[")) {
            Type firstElement = Type.getType(first.substring(1));
            Type secondElement = Type.getType(second.substring(1));
            if (isReference(firstElement) && isReference(secondElement)) {
                String common = commonSuperType(firstElement.getInternalName(), secondElement.getInternalName());
                return "[" + referenceDescriptor(common);
            }
            return "java/lang/Object";
        }
        if (first.startsWith("[") || second.startsWith("[")
                || (get(first).access & Opcodes.ACC_INTERFACE) != 0
                || (get(second).access & Opcodes.ACC_INTERFACE) != 0) {
            return "java/lang/Object";
        }
        String parent = get(first).superName;
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            if (isAssignable(parent, second)) {
                return parent;
            }
            parent = get(parent).superName;
        }
        return "java/lang/Object";
    }

    boolean isAssignable(String destination, String source) {
        if (destination.equals(source) || destination.equals("java/lang/Object")) {
            return true;
        }
        if (source.startsWith("[")) {
            if (destination.equals("java/lang/Cloneable") || destination.equals("java/io/Serializable")) {
                return true;
            }
            if (!destination.startsWith("[")) {
                return false;
            }
            Type sourceElement = Type.getType(source.substring(1));
            Type destinationElement = Type.getType(destination.substring(1));
            return isReference(sourceElement) && isReference(destinationElement)
                    && isAssignable(destinationElement.getInternalName(), sourceElement.getInternalName());
        }
        if (destination.startsWith("[")) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(destination)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            ClassNode node = get(current);
            if (node.superName != null) {
                pending.add(node.superName);
            }
            pending.addAll(node.interfaces);
        }
        return false;
    }

    private ClassNode readResource(String name) {
        try (InputStream input = resources.getResourceAsStream(name + ".class")) {
            if (input == null) {
                throw new IllegalArgumentException("Class hierarchy metadata is missing for " + name);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read class hierarchy for " + name, failure);
        }
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static String referenceDescriptor(String name) {
        return name.startsWith("[") ? name : "L" + name + ";";
    }
}
