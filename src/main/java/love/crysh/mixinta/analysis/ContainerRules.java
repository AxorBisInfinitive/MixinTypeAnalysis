package love.crysh.mixinta.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 自定义契约以精确方法键注册，内置摘要与自定义摘要共用同一求解路径。 */
final class ContainerRules {
    private static final String RULE = "Llove/crysh/mixinta/ContainerRule;";
    private static final String LIST = "Llove/crysh/mixinta/ContainerRule$List;";

    record Definition(String id, int version, MethodKey method, ContainerSummary summary) {
    }

    private final Map<MethodKey, Definition> definitions;

    ContainerRules(List<Definition> definitions) {
        Map<MethodKey, Definition> index = new LinkedHashMap<>();
        for (Definition definition : definitions) index.put(definition.method(), definition);
        this.definitions = Map.copyOf(index);
    }

    static List<Definition> read(ClassNode rules, ClassHierarchy hierarchy, MethodKey target, int parameter,
            List<Diagnostic> diagnostics) {
        List<AnnotationNode> declarations = new ArrayList<>();
        AnnotationNode single = ClassFiles.annotation(rules.visibleAnnotations, RULE);
        if (single != null) declarations.add(single);
        AnnotationNode repeated = ClassFiles.annotation(rules.visibleAnnotations, LIST);
        if (repeated != null) {
            for (Object entry : (List<?>) ClassFiles.value(repeated, "value", List.of())) declarations.add((AnnotationNode) entry);
        }
        Set<String> ids = new HashSet<>();
        Set<MethodKey> methods = new HashSet<>();
        List<Definition> result = new ArrayList<>();
        for (AnnotationNode declaration : declarations) {
            try {
                MethodKey method = symbol((String) ClassFiles.value(declaration, "method", ""));
                String id = (String) ClassFiles.value(declaration, "id", method.toString());
                if (id.isEmpty()) id = method.toString();
                int version = (int) ClassFiles.value(declaration, "version", 1);
                if (version < 1 || !ids.add(id) || !methods.add(method)) {
                    throw new IllegalArgumentException("Invalid version, duplicate id or duplicate method " + id + " " + method);
                }
                String operationName = enumValue(declaration, "operation", "");
                love.crysh.mixinta.ContainerRule.Operation.valueOf(operationName);
                ContainerOperation operation = ContainerOperation.valueOf(operationName);
                int channel = (int) ClassFiles.value(declaration, "channel", 0);
                int element = (int) ClassFiles.value(declaration, "parameter", -1);
                String viewName = enumValue(declaration, "view", "NONE");
                ContainerView view = viewName.equals("NONE") ? null : ContainerView.valueOf(viewName);
                ContainerSummary summary = new ContainerSummary(operation, channel, element, -1, -1, view);
                validate(method, summary, hierarchy);
                result.add(new Definition(id, version, method, summary));
            } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic("INVALID_CONTAINER_RULE", target, -1, parameter,
                        "Rules=" + rules.name + " " + failure.getMessage()));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing(Definition::id)).toList();
    }

    static void validate(MethodKey key, ContainerSummary summary, ClassHierarchy hierarchy) {
        ClassNode owner = hierarchy.get(key.owner());
        var method = ClassFiles.findMethod(owner, key.name(), key.descriptor());
        if (method == null) throw new IllegalArgumentException("Missing declared container method " + key);
        if (summary.channel() < 0) throw new IllegalArgumentException("Negative container channel " + key);
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0 || key.name().equals("<clinit>")) {
            throw new IllegalArgumentException("Container rule requires an instance implementation " + key);
        }
        boolean constructor = key.name().equals("<init>");
        if (constructor != (summary.operation() == ContainerOperation.ALLOCATE)) {
            throw new IllegalArgumentException("ALLOCATE must describe a constructor " + key);
        }
        if (!constructor && (owner.access & Opcodes.ACC_FINAL) == 0
                && (method.access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == 0) {
            throw new IllegalArgumentException("Container method requires fixed dispatch " + key);
        }
        int opcode = constructor ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
        if (ContainerSummary.resolve(new MethodInsnNode(opcode, key.owner(), key.name(), key.descriptor(), false)) != null) {
            throw new IllegalArgumentException("Container rule conflicts with a built-in summary " + key);
        }
        Type[] arguments = Type.getArgumentTypes(key.descriptor());
        Type returned = Type.getReturnType(key.descriptor());
        boolean writes = summary.writesElement();
        if (writes ? summary.elementParameter() < 0 || summary.elementParameter() >= arguments.length
                || !arguments[summary.elementParameter()].equals(Type.getType(Object.class)) : summary.elementParameter() != -1) {
            throw new IllegalArgumentException("Invalid erased element parameter " + key);
        }
        for (int index = 0; index < arguments.length; index++) {
            if (index != summary.elementParameter() && reference(arguments[index])) {
                throw new IllegalArgumentException("Undeclared reference argument=" + index + " " + key);
            }
        }
        if (summary.readsElement() && !returned.equals(Type.getType(Object.class))) {
            throw new IllegalArgumentException("Element read must return java/lang/Object " + key);
        }
        if (summary.operation() == ContainerOperation.VIEW) {
            if (summary.view() == null || returned.getSort() != Type.OBJECT) {
                throw new IllegalArgumentException("VIEW requires an object return and a view relation " + key);
            }
            String expected = switch (summary.view()) {
                case ITERATOR -> "java/util/Iterator";
                case LIST_ITERATOR -> "java/util/ListIterator";
                case LIST -> "java/util/List";
                case SEQUENCE -> "java/util/Collection";
                case KEYS, ENTRIES -> "java/util/Set";
                case VALUES -> "java/util/Collection";
                case ENTRY -> "java/util/Map$Entry";
                case ALIAS -> null;
            };
            if (expected != null && !hierarchy.isAssignable(expected, returned.getInternalName())) {
                throw new IllegalArgumentException("View return is incompatible with " + summary.view() + " " + key);
            }
        } else if (summary.view() != null) {
            throw new IllegalArgumentException("Only VIEW accepts a view relation " + key);
        } else if (!summary.readsElement() && reference(returned)) {
            throw new IllegalArgumentException("Undeclared reference return " + key);
        }
        if ((constructor || summary.operation() == ContainerOperation.CLEAR) && !returned.equals(Type.VOID_TYPE)) {
            throw new IllegalArgumentException("Constructor and CLEAR must return void " + key);
        }
    }

    ContainerSummary resolve(MethodInsnNode call) {
        Definition definition = definitions.get(new MethodKey(call.owner, call.name, call.desc));
        if (definition != null && call.getOpcode() != Opcodes.INVOKESTATIC) return definition.summary();
        return ContainerSummary.resolve(call);
    }

    ContainerSummary resolve(MethodInsnNode call, StorageReferences receiver) {
        Definition definition = definitions.get(new MethodKey(call.owner, call.name, call.desc));
        if (definition != null && call.getOpcode() != Opcodes.INVOKESTATIC) return definition.summary();
        return ContainerSummary.resolve(call, receiver);
    }

    boolean supportsAllocation(String owner) {
        return ContainerSummary.supportsAllocation(owner) || definitions.values().stream()
                .anyMatch(definition -> definition.method().owner().equals(owner)
                        && definition.summary().operation() == ContainerOperation.ALLOCATE);
    }

    boolean models(MethodKey method) {
        return definitions.containsKey(method);
    }

    private static boolean reference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static String enumValue(AnnotationNode annotation, String name, String fallback) {
        Object value = ClassFiles.value(annotation, name, null);
        return value instanceof String[] pair ? pair[1] : fallback;
    }

    private static MethodKey symbol(String text) {
        int descriptor = text.indexOf('(');
        int separator = descriptor < 0 ? -1 : Math.max(text.lastIndexOf('.', descriptor), text.lastIndexOf('/', descriptor));
        if (separator <= 0 || separator == descriptor - 1) throw new IllegalArgumentException("Expected owner.name(descriptor) " + text);
        String signature = text.substring(descriptor);
        Type.getArgumentTypes(signature);
        Type.getReturnType(signature);
        return new MethodKey(text.substring(0, separator).replace('.', '/'), text.substring(separator + 1, descriptor), signature);
    }
}
