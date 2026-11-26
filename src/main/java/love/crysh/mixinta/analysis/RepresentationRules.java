package love.crysh.mixinta.analysis;

import love.crysh.mixinta.TypeRule;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class RepresentationRules {
    static final String RULE = "Llove/crysh/mixinta/TypeRule;";

    private RepresentationRules() {
    }

    static Set<String> requiredClasses(Map<String, byte[]> mixins) {
        Set<String> names = new TreeSet<>();
        for (byte[] bytes : mixins.values()) {
            for (MethodNode method : ClassFiles.read(bytes).methods) {
                AnnotationNode annotation = ClassFiles.annotation(method.visibleAnnotations, ClassFiles.TYPED_OVERWRITE);
                for (AnnotationNode parameter : parameters(annotation)) {
                    Object value = ClassFiles.value(parameter, "rules", null);
                    if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                        names.add(type.getInternalName());
                    }
                }
            }
        }
        return names;
    }

    static List<RepresentationMigration> read(AnnotationNode annotation, MethodKey target, MethodNode implementation,
            Map<String, ClassNode> originals, Map<String, ClassNode> mixins, ClassHierarchy hierarchy,
            List<Diagnostic> diagnostics) {
        List<RepresentationMigration> result = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();
        Type[] before = Type.getArgumentTypes(target.descriptor());
        Type[] after = Type.getArgumentTypes(implementation.desc);
        for (AnnotationNode declaration : parameters(annotation)) {
            int parameter = (int) ClassFiles.value(declaration, "parameter", -1);
            try {
                if (parameter < 0 || parameter >= before.length || parameter >= after.length || !claimed.add(parameter)) {
                    throw new IllegalArgumentException("Invalid or duplicate parameter=" + parameter);
                }
                if (!before[parameter].equals(Type.LONG_TYPE) || after[parameter].getSort() != Type.OBJECT) {
                    throw new IllegalArgumentException("Representation migration requires long -> object");
                }
                Type ruleType = (Type) ClassFiles.value(declaration, "rules", null);
                if (ruleType == null || ruleType.getSort() != Type.OBJECT) {
                    throw new IllegalArgumentException("Missing rules class");
                }
                String owner = ruleType.getInternalName();
                ClassNode ruleClass = mixins.get(owner);
                String mergedOwner = null;
                if (ruleClass != null) {
                    AnnotationNode mixin = ClassFiles.annotation(ruleClass.invisibleAnnotations, ClassFiles.MIXIN);
                    List<?> targets = (List<?>) ClassFiles.value(mixin, "value", List.of());
                    List<?> strings = (List<?>) ClassFiles.value(mixin, "targets", List.of());
                    boolean matches = targets.size() + strings.size() == 1
                            && (targets.size() == 1 && ((Type) targets.getFirst()).getInternalName().equals(target.owner())
                            || strings.size() == 1 && strings.getFirst().toString().replace('.', '/').equals(target.owner()));
                    if (!matches) {
                        throw new IllegalArgumentException("Rules Mixin must have the same single target " + target.owner());
                    }
                    mergedOwner = target.owner();
                } else {
                    ruleClass = originals.containsKey(owner) ? originals.get(owner) : hierarchy.get(owner);
                    if ((ruleClass.access & Opcodes.ACC_PUBLIC) == 0) {
                        throw new IllegalArgumentException("Rules class must be public " + owner);
                    }
                }
                String id = (String) ClassFiles.value(declaration, "id", "");
                if (id.isEmpty()) {
                    id = target + " parameter=" + parameter;
                }
                List<RepresentationMigration.Rule> rules = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (MethodNode helper : ruleClass.methods) {
                    AnnotationNode rule = ClassFiles.annotation(helper.visibleAnnotations, RULE);
                    if (rule == null) {
                        continue;
                    }
                    MethodKey key = new MethodKey(owner, helper.name, helper.desc);
                    String ruleId = (String) ClassFiles.value(rule, "id", "");
                    if (ruleId.isEmpty()) {
                        ruleId = key.toString();
                        set(rule, "id", ruleId);
                    }
                    int version = (int) ClassFiles.value(rule, "version", 1);
                    if (version < 1 || !ids.add(ruleId)) {
                        throw new IllegalArgumentException("Invalid version or duplicate rule id=" + ruleId);
                    }
                    if ((helper.access & Opcodes.ACC_STATIC) == 0
                            || (helper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        throw new IllegalArgumentException("Rule helper must be static and implemented " + key);
                    }
                    TypeRule.Kind kind = TypeRule.Kind.valueOf(enumValue(rule, "value", ""));
                    TypeRule.Binding binding = TypeRule.Binding.valueOf(enumValue(rule, "binding", "RECEIVER"));
                    int boundParameter = (int) ClassFiles.value(rule, "parameter", 0);
                    MethodKey source = null;
                    Type boundType = after[parameter];
                    if (kind == TypeRule.Kind.SOURCE) {
                        source = symbol((String) ClassFiles.value(rule, "method", ""));
                        MethodKey resolved = hierarchy.resolve(source.owner(), source.name(), source.descriptor());
                        if (resolved == null) {
                            throw new IllegalArgumentException("Missing source method " + source);
                        }
                        MethodNode sourceMethod = ClassFiles.findMethod(hierarchy.get(resolved.owner()), resolved.name(), resolved.descriptor());
                        if (!Type.getReturnType(source.descriptor()).equals(Type.LONG_TYPE)) {
                            throw new IllegalArgumentException("Source must return long " + source);
                        }
                        if (binding == TypeRule.Binding.RECEIVER) {
                            if ((sourceMethod.access & Opcodes.ACC_STATIC) != 0) {
                                throw new IllegalArgumentException("Static source has no receiver " + source);
                            }
                            boundType = Type.getObjectType(source.owner());
                        } else {
                            Type[] arguments = Type.getArgumentTypes(source.descriptor());
                            if (boundParameter < 0 || boundParameter >= arguments.length) {
                                throw new IllegalArgumentException("Invalid source parameter " + source);
                            }
                            boundType = arguments[boundParameter];
                        }
                    }
                    Type returned = kind == TypeRule.Kind.ENCODE ? Type.LONG_TYPE : after[parameter];
                    if (!helper.desc.equals(Type.getMethodDescriptor(returned, boundType))) {
                        throw new IllegalArgumentException("Invalid rule helper descriptor " + key);
                    }
                    rules.add(new RepresentationMigration.Rule(ruleId, version, kind, source, key, binding, boundParameter, mergedOwner));
                }
                rules.sort(java.util.Comparator.comparing(RepresentationMigration.Rule::id));
                result.add(new RepresentationMigration(id, target, parameter, after[parameter], owner, rules,
                        ContainerRules.read(ruleClass, hierarchy, target, parameter, diagnostics)));
            } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic("INVALID_REPRESENTATION_RULE", target, -1, parameter, failure.getMessage()));
            }
        }
        if (result.size() > 1) {
            diagnostics.add(new Diagnostic("MULTI_PARAMETER_REPRESENTATION_NOT_IMPLEMENTED", target, -1, -1,
                    "The first propagation implementation accepts one representation parameter per overwrite"));
        }
        return result;
    }

    static RepresentationMigration resolve(RepresentationMigration migration, Map<String, ClassNode> nodes,
            List<Diagnostic> diagnostics) {
        List<RepresentationMigration.Rule> rules = new ArrayList<>();
        for (var rule : migration.rules()) {
            ClassNode owner = nodes.get(rule.mergedOwner() == null ? rule.helper().owner() : rule.mergedOwner());
            List<MethodNode> matches = owner == null ? List.of() : owner.methods.stream().filter(method -> {
                if (rule.mergedOwner() == null) {
                    return method.name.equals(rule.helper().name()) && method.desc.equals(rule.helper().descriptor());
                }
                AnnotationNode annotation = ClassFiles.annotation(method.visibleAnnotations, RULE);
                return annotation != null && rule.id().equals(ClassFiles.value(annotation, "id", ""))
                        && rule.version() == (int) ClassFiles.value(annotation, "version", 1)
                        && method.desc.equals(rule.helper().descriptor());
            }).toList();
            if (matches.size() != 1 || (matches.getFirst().access & Opcodes.ACC_STATIC) == 0) {
                diagnostics.add(new Diagnostic("RULE_HELPER_UNAVAILABLE", migration.target(), -1, migration.parameter(),
                        "Rule=" + rule.id() + " must resolve to one static helper after Mixin"));
                continue;
            }
            MethodNode helper = matches.getFirst();
            rules.add(new RepresentationMigration.Rule(rule.id(), rule.version(), rule.kind(), rule.source(),
                    new MethodKey(owner.name, helper.name, helper.desc), rule.binding(), rule.parameter(), null));
        }
        return new RepresentationMigration(migration.id(), migration.target(), migration.parameter(), migration.representation(),
                migration.rulesOwner(), rules, migration.containers());
    }

    private static List<AnnotationNode> parameters(AnnotationNode annotation) {
        Object value = ClassFiles.value(annotation, "parameters", List.of());
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Invalid parameter declarations");
        }
        return list.stream().map(AnnotationNode.class::cast).toList();
    }

    private static String enumValue(AnnotationNode annotation, String name, String fallback) {
        Object value = ClassFiles.value(annotation, name, null);
        return value instanceof String[] pair ? pair[1] : fallback;
    }

    private static MethodKey symbol(String text) {
        int descriptor = text.indexOf('(');
        int separator = descriptor < 0 ? -1 : Math.max(text.lastIndexOf('.', descriptor), text.lastIndexOf('/', descriptor));
        if (separator <= 0 || separator == descriptor - 1) {
            throw new IllegalArgumentException("Expected owner.name(descriptor) " + text);
        }
        String signature = text.substring(descriptor);
        Type.getArgumentTypes(signature);
        Type.getReturnType(signature);
        return new MethodKey(text.substring(0, separator).replace('.', '/'), text.substring(separator + 1, descriptor), signature);
    }

    private static void set(AnnotationNode annotation, String name, Object value) {
        if (annotation.values == null) {
            annotation.values = new ArrayList<>();
        }
        annotation.values.add(name);
        annotation.values.add(value);
    }
}
