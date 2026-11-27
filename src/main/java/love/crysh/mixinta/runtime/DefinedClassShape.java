package love.crysh.mixinta.runtime;

import love.crysh.mixinta.bytecode.ClassFiles;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在允许拒绝并发参与类请求的阶段核对已定义类的层次和成员结构。 */
final class DefinedClassShape {
    private DefinedClassShape() { }

    static void verify(String name, byte[] bytes, Class<?> type) {
        var node = ClassFiles.read(bytes);
        String parent = type.getSuperclass() == null ? null : Type.getInternalName(type.getSuperclass());
        if (type.isInterface()) parent = "java/lang/Object";
        Set<String> interfaces = new HashSet<>();
        for (Class<?> implemented : type.getInterfaces()) interfaces.add(Type.getInternalName(implemented));
        Map<String, Integer> methods = new HashMap<>();
        for (var method : type.getDeclaredMethods()) methods.put(method.getName() + Type.getMethodDescriptor(method), method.getModifiers());
        for (var constructor : type.getDeclaredConstructors()) methods.put("<init>" + Type.getConstructorDescriptor(constructor), constructor.getModifiers());
        Map<String, Integer> expectedMethods = new HashMap<>();
        node.methods.stream().filter(method -> !method.name.equals("<clinit>"))
                .forEach(method -> expectedMethods.put(method.name + method.desc, method.access & 0xffff));
        Map<String, String> fields = new HashMap<>();
        for (var field : type.getDeclaredFields()) fields.put(field.getName(), Type.getDescriptor(field.getType()) + "/" + field.getModifiers());
        Map<String, String> expectedFields = new HashMap<>();
        node.fields.forEach(field -> expectedFields.put(field.name, field.desc + "/" + (field.access & 0xffff)));
        int mask = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM;
        if (!Objects.equals(parent, node.superName) || !interfaces.equals(Set.copyOf(node.interfaces))
                || !methods.equals(expectedMethods) || !fields.equals(expectedFields)
                || (type.getModifiers() & mask) != (node.access & mask)) {
            throw new IllegalStateException("DEFINED_HIERARCHY_CHANGED " + name);
        }
    }
}
