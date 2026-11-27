package love.crysh.mixinta.analysis;

import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRuleReader;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.bytecode.PrimitiveConversions;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个分析批次对应一个 ClassLoader 的固定类集合。
 * originals 必须是 Mixin 应用前的快照，mixinClasses 按内部类名索引。
 */
public final class TypedOverwriteAnalyzer {
    private final ClassByteSource resources;

    public TypedOverwriteAnalyzer() {
        this(TypedOverwriteAnalyzer.class.getClassLoader());
    }

    public TypedOverwriteAnalyzer(ClassLoader resources) {
        this(ClassByteSource.from(resources));
    }

    public TypedOverwriteAnalyzer(ClassByteSource resources) {
        this.resources = resources;
    }

    /** 返回规则容器的内部类名，运行时应在准备批次前读取其 class 资源。 */
    public static Set<String> requiredRuleClasses(Map<String, byte[]> mixinClasses) {
        return RepresentationRuleReader.requiredClasses(mixinClasses);
    }

    public PreparedOverwrites prepare(Map<String, byte[]> originals, Map<String, byte[]> mixinClasses) {
        Map<String, byte[]> originalSnapshot = ClassFiles.snapshot(originals);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ClassNode> targets = AnalysisClassReader.readAll(originalSnapshot, diagnostics);
        Map<String, ClassNode> mixins = AnalysisClassReader.readAll(ClassFiles.snapshot(mixinClasses), diagnostics);
        ClassHierarchy hierarchy = new ClassHierarchy(targets, resources);
        List<Migration> migrations = new ArrayList<>();
        Set<MethodKey> claimed = new HashSet<>();
        Set<MethodKey> implementations = new HashSet<>();
        for (ClassNode mixin : mixins.values()) {
            AnnotationNode mixinAnnotation = ClassFiles.annotation(mixin.invisibleAnnotations, MixinAnnotations.MIXIN);
            if (mixinAnnotation == null) {
                mixinAnnotation = ClassFiles.annotation(mixin.visibleAnnotations, MixinAnnotations.MIXIN);
            }
            List<String> targetNames = targetNames(mixinAnnotation);
            for (MethodNode method : mixin.methods) {
                AnnotationNode annotation = ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE);
                if (annotation == null) {
                    continue;
                }
                MethodKey location = new MethodKey(mixin.name, method.name, method.desc);
                if (targetNames.isEmpty()) {
                    diagnostics.add(problem("MISSING_MIXIN_TARGET", location, "@Mixin must declare a target"));
                    continue;
                }
                if (ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.OVERWRITE) != null) {
                    diagnostics.add(problem("CONFLICTING_ANNOTATIONS", location, "@TypedOverwrite and @Overwrite are both present"));
                    continue;
                }
                boolean nativeOverwrite = true;
                for (String targetName : targetNames) {
                    ClassNode target = targets.get(targetName);
                    if (target == null) {
                        diagnostics.add(problem("MISSING_TARGET_CLASS", location, targetName));
                        continue;
                    }
                    MethodNode original = findTarget(target, method, annotation, location, diagnostics);
                    if (original == null) {
                        continue;
                    }
                    validateModifiers(target, original, method, location, diagnostics);
                    if (original.desc.equals(method.desc)) {
                        if (!((List<?>) ClassFiles.value(annotation, "parameters", List.of())).isEmpty()) {
                            diagnostics.add(problem("INVALID_REPRESENTATION_RULE", location, "Unchanged descriptor has parameter migration declarations"));
                        }
                        if (!original.name.equals(method.name)) {
                            addAlias(annotation, original.name);
                        }
                        continue;
                    }
                    nativeOverwrite = false;
                    MethodKey targetKey = new MethodKey(target.name, original.name, original.desc);
                    List<RepresentationMigration> representations = RepresentationRuleReader.read(annotation, targetKey, method,
                            targets, mixins, hierarchy, diagnostics);
                    validateMigration(target, original, method, location, hierarchy, representations, diagnostics);
                    if (targetNames.size() > 1) {
                        diagnostics.add(problem("MULTI_TARGET_NOT_IMPLEMENTED", location, "Parameter migration needs one target class"));
                    }
                    MethodKey originalKey = new MethodKey(target.name, original.name, original.desc);
                    if (!claimed.add(originalKey)) {
                        diagnostics.add(problem("DUPLICATE_TARGET", location, originalKey.toString()));
                    }
                    MethodKey implementationKey = new MethodKey(target.name, method.name, method.desc);
                    if (!implementations.add(implementationKey)) {
                        diagnostics.add(problem("SIGNATURE_COLLISION", location, implementationKey.toString()));
                    }
                    migrations.add(new Migration(originalKey, implementationKey, method.access, representations));
                }
                if (nativeOverwrite) {
                    annotation.desc = MixinAnnotations.OVERWRITE;
                    removeValue(annotation, "method");
                    removeValue(annotation, "parameters");
                } else if ((method.access & Opcodes.ACC_STATIC) != 0
                        && (method.access & Opcodes.ACC_PRIVATE) == 0) {
                    // Mixin 会重命名非 public synthetic 方法，最终 access 从声明快照恢复。
                    method.access = (method.access & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
                }
            }
        }
        TypedOverwriteException.throwIfErrors(diagnostics);
        Map<String, byte[]> preparedMixins = new LinkedHashMap<>();
        mixins.forEach((name, node) -> preparedMixins.put(name, ClassFiles.write(node)));
        return new PreparedOverwrites(originalSnapshot, preparedMixins, migrations, resources);
    }

    private MethodNode findTarget(ClassNode target, MethodNode implementation, AnnotationNode annotation,
            MethodKey location, List<Diagnostic> diagnostics) {
        String selector = (String) ClassFiles.value(annotation, "method", "");
        List<String> names = new ArrayList<>();
        names.add(implementation.name);
        Object aliases = ClassFiles.value(annotation, "aliases", List.of());
        if (aliases instanceof Collection<?> values) {
            values.forEach(value -> names.add((String) value));
        }
        List<MethodNode> matches;
        if (selector.isEmpty()) {
            MethodNode exact = ClassFiles.findMethod(target, implementation.name, implementation.desc);
            if (exact != null) {
                return exact;
            }
            int parameterCount = Type.getArgumentTypes(implementation.desc).length;
            matches = target.methods.stream().filter(method -> names.contains(method.name)
                    && Type.getArgumentTypes(method.desc).length == parameterCount).toList();
        } else {
            int descriptorStart = selector.indexOf('(');
            if (descriptorStart <= 0 || !validDescriptor(selector.substring(descriptorStart))) {
                diagnostics.add(problem("INVALID_SELECTOR", location, selector));
                return null;
            }
            String name = selector.substring(0, descriptorStart);
            String descriptor = selector.substring(descriptorStart);
            matches = target.methods.stream()
                    .filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        }
        if (matches.size() != 1) {
            String code = matches.isEmpty() ? "TARGET_NOT_FOUND" : "AMBIGUOUS_TARGET";
            diagnostics.add(problem(code, location, target.name + " candidates=" + matches.size()));
            return null;
        }
        return matches.getFirst();
    }

    private void validateModifiers(ClassNode target, MethodNode original, MethodNode implementation,
            MethodKey location, List<Diagnostic> diagnostics) {
        if (original.name.startsWith("<") || implementation.name.startsWith("<")) {
            diagnostics.add(problem("INITIALIZER_NOT_SUPPORTED", location, original.name));
        }
        if ((original.access & Opcodes.ACC_STATIC) != (implementation.access & Opcodes.ACC_STATIC)) {
            diagnostics.add(problem("STATIC_MISMATCH", location, original.name));
        }
        if (visibility(implementation.access) < visibility(original.access)) {
            diagnostics.add(problem("VISIBILITY_REDUCTION", location, target.name + "." + original.name));
        }
        if (!original.name.equals(implementation.name) && (original.access & Opcodes.ACC_PRIVATE) == 0) {
            diagnostics.add(problem("NON_PRIVATE_ALIAS", location, original.name));
        }
    }

    private void validateMigration(ClassNode target, MethodNode original, MethodNode implementation,
            MethodKey location, ClassHierarchy hierarchy, List<RepresentationMigration> representations, List<Diagnostic> diagnostics) {
        if (!Type.getReturnType(original.desc).equals(Type.getReturnType(implementation.desc))) {
            diagnostics.add(problem("RETURN_TYPE_NOT_IMPLEMENTED", location,
                    Type.getReturnType(original.desc) + " -> " + Type.getReturnType(implementation.desc)));
        }
        Type[] originalParameters = Type.getArgumentTypes(original.desc);
        Type[] newParameters = Type.getArgumentTypes(implementation.desc);
        if (originalParameters.length != newParameters.length) {
            diagnostics.add(problem("PARAMETER_COUNT_MISMATCH", location, original.desc + " -> " + implementation.desc));
            return;
        }
        for (int index = 0; index < originalParameters.length; index++) {
            int parameter = index;
            if (representations.stream().noneMatch(value -> value.parameter() == parameter)
                    && !PrimitiveConversions.isSupported(originalParameters[index], newParameters[index])) {
                diagnostics.add(new Diagnostic("PARAMETER_CONVERSION_NOT_IMPLEMENTED", location, -1, index,
                        originalParameters[index] + " -> " + newParameters[index]));
            }
        }
        if (!original.name.equals(implementation.name)) {
            diagnostics.add(problem("MIGRATION_ALIAS_NOT_IMPLEMENTED", location, original.name));
        }
        if (ClassFiles.findMethod(target, original.name, implementation.desc) != null) {
            diagnostics.add(problem("SIGNATURE_COLLISION", location, original.name + implementation.desc));
        }
        if ((implementation.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || implementation.instructions.size() == 0) {
            diagnostics.add(problem("MISSING_IMPLEMENTATION", location, implementation.name));
        }
        boolean fixedDispatch = (original.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0
                || (target.access & Opcodes.ACC_FINAL) != 0;
        if (!fixedDispatch || (target.access & Opcodes.ACC_INTERFACE) != 0) {
            diagnostics.add(problem("VIRTUAL_DISPATCH_NOT_IMPLEMENTED", location, original.name));
        }
        try {
            if ((original.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
                    && hierarchy.hasInheritedMethod(target, original.name, original.desc)) {
                diagnostics.add(problem("OVERRIDE_CHAIN_NOT_IMPLEMENTED", location, original.name));
            }
        } catch (IllegalArgumentException failure) {
            diagnostics.add(problem("MISSING_HIERARCHY", location, failure.getMessage()));
        }
    }

    private static int visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return 3;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return 2;
        }
        return (access & Opcodes.ACC_PRIVATE) == 0 ? 1 : 0;
    }

    private static boolean validDescriptor(String descriptor) {
        try {
            Type[] parameters = Type.getArgumentTypes(descriptor);
            Type result = Type.getReturnType(descriptor);
            return result.getSort() != Type.METHOD
                    && java.util.Arrays.stream(parameters).allMatch(type -> type.getSort() != Type.VOID && type.getSort() != Type.METHOD)
                    && descriptor.equals(Type.getMethodDescriptor(result, parameters));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static List<String> targetNames(AnnotationNode annotation) {
        List<String> names = new ArrayList<>();
        Object classTargets = ClassFiles.value(annotation, "value", List.of());
        if (classTargets instanceof Collection<?> values) {
            values.forEach(value -> names.add(((Type) value).getInternalName()));
        }
        Object stringTargets = ClassFiles.value(annotation, "targets", List.of());
        if (stringTargets instanceof Collection<?> values) {
            values.forEach(value -> names.add(((String) value).replace('.', '/')));
        }
        return names.stream().distinct().toList();
    }

    private static void removeValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return;
        }
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) {
                annotation.values.remove(index);
                annotation.values.remove(index);
                return;
            }
        }
    }

    private static void addAlias(AnnotationNode annotation, String name) {
        List<String> aliases = new ArrayList<>();
        Object existing = ClassFiles.value(annotation, "aliases", List.of());
        if (existing instanceof Collection<?> values) {
            values.forEach(value -> aliases.add((String) value));
        }
        if (!aliases.contains(name)) {
            aliases.add(name);
        }
        removeValue(annotation, "aliases");
        if (annotation.values == null) {
            annotation.values = new ArrayList<>();
        }
        annotation.values.addAll(List.of("aliases", aliases));
    }

    private static Diagnostic problem(String code, MethodKey method, String message) {
        return new Diagnostic(code, method, -1, -1, message);
    }
}
