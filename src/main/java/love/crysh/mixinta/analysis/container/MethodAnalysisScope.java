package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.bytecode.MethodReferences;
import love.crysh.mixinta.model.MethodKey;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 轻量引用索引保守连通调用、字段和初始化关系，闭包内的方法才建立完整数据流。 */
public final class MethodAnalysisScope {
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final Map<MethodKey, MethodNode> methods = new LinkedHashMap<>();
    private final Map<String, List<MethodKey>> signatures = new HashMap<>();
    private final Map<MethodKey, Set<MethodKey>> edges = new HashMap<>();
    private final Map<MethodKey, Set<MethodKey>> dispatches = new HashMap<>();
    private final Map<String, MethodKey> fields = new HashMap<>();
    private final Set<MethodKey> seeds = new LinkedHashSet<>();

    private MethodAnalysisScope(Map<String, ClassNode> classes, ClassHierarchy hierarchy) {
        this.classes = classes;
        this.hierarchy = hierarchy;
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                methods.put(key, method);
                signatures.computeIfAbsent(method.name + method.desc, ignored -> new ArrayList<>()).add(key);
                edges.put(key, new HashSet<>());
            }
        }
    }

    public static Set<MethodKey> select(Map<String, ClassNode> classes, Set<MethodKey> targets, ClassHierarchy hierarchy) {
        MethodAnalysisScope index = new MethodAnalysisScope(classes, hierarchy);
        Set<String> targetSignatures = new HashSet<>();
        Set<String> targetOwners = new HashSet<>();
        targets.forEach(target -> {
            targetSignatures.add(target.name() + target.descriptor());
            targetOwners.add(target.owner());
        });
        index.methods.forEach((key, method) -> {
            if (targetOwners.contains(key.owner()) || ClassFiles.annotation(method.visibleAnnotations,
                    "Llove/crysh/mixinta/anno/TypeRule;") != null) index.seeds.add(key);
            if ((method.access & Opcodes.ACC_STATIC) != 0 || method.name.equals("<init>")) index.initializer(key, key.owner());
            MethodReferences.visit(method, (instruction, reference) -> {
                if (targetSignatures.contains(reference.name() + reference.descriptor())) index.seeds.add(key);
                for (MethodKey potential : index.potential(reference)) index.connect(key, potential);
                index.initializer(key, reference.owner());
            });
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    index.field(key, field.name, field.desc);
                    index.initializer(key, field.owner);
                } else if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                    index.initializer(key, type.desc);
                    ClassNode allocated = classes.get(type.desc);
                    if (allocated != null) {
                        for (MethodNode constructor : allocated.methods) {
                            if (constructor.name.equals("<init>")) index.connect(key, new MethodKey(type.desc, constructor.name, constructor.desc));
                        }
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    index.fieldConstants(key, constant.cst);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    index.fieldConstants(key, dynamic.bsm);
                    for (Object argument : dynamic.bsmArgs) index.fieldConstants(key, argument);
                }
            }
        });
        for (ClassNode owner : classes.values()) {
            if (owner.superName != null) index.initializer(new MethodKey(owner.name, "<clinit>", "()V"), owner.superName);
        }
        Set<MethodKey> selected = new HashSet<>();
        ArrayDeque<MethodKey> pending = new ArrayDeque<>(index.seeds);
        while (!pending.isEmpty()) {
            MethodKey method = pending.removeFirst();
            if (selected.add(method)) pending.addAll(index.edges.get(method));
        }
        return Set.copyOf(selected);
    }

    private Set<MethodKey> potential(MethodKey reference) {
        return dispatches.computeIfAbsent(reference, key -> {
            List<MethodKey> matching = signatures.getOrDefault(key.name() + key.descriptor(), List.of());
            if (matching.isEmpty()) return Set.of();
            if (key.name().startsWith("<")) return methods.containsKey(key) ? Set.of(key) : Set.of();
            Set<MethodKey> result = new HashSet<>();
            try {
                MethodKey resolved = hierarchy.resolve(key.owner(), key.name(), key.descriptor());
                if (resolved != null && methods.containsKey(resolved)) result.add(resolved);
                MethodNode declaration = resolved == null ? null : ClassFiles.findMethod(hierarchy.get(resolved.owner()), key.name(), key.descriptor());
                if (declaration != null && (declaration.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) return Set.copyOf(result);
                for (ClassNode candidate : classes.values()) {
                    if (!hierarchy.isAssignable(key.owner(), candidate.name)) continue;
                    MethodKey implementation = hierarchy.resolveVirtual(candidate.name, key.name(), key.descriptor());
                    if (implementation != null && methods.containsKey(implementation)) result.add(implementation);
                }
            } catch (IllegalArgumentException unavailable) {
                // 层次缺失时扩大到所有同签名实现，避免把可能的容器访问裁掉。
                result.addAll(matching);
            }
            return Set.copyOf(result);
        });
    }

    private void initializer(MethodKey method, String owner) {
        connect(method, new MethodKey(owner, "<clinit>", "()V"));
    }

    private void field(MethodKey method, String name, String descriptor) {
        // 同名同类型字段可能通过继承 owner 出现，合并到同一组可以保守覆盖别名。
        MethodKey previous = fields.putIfAbsent(name + descriptor, method);
        if (previous != null) connect(method, previous);
    }

    private void fieldConstants(MethodKey method, Object constant) {
        if (constant instanceof Handle handle && handle.getTag() <= Opcodes.H_PUTSTATIC) {
            field(method, handle.getName(), handle.getDesc());
            initializer(method, handle.getOwner());
        } else if (constant instanceof ConstantDynamic dynamic) {
            fieldConstants(method, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) fieldConstants(method, dynamic.getBootstrapMethodArgument(index));
        }
    }

    private void connect(MethodKey first, MethodKey second) {
        if (!edges.containsKey(first) || !edges.containsKey(second)) return;
        edges.get(first).add(second);
        edges.get(second).add(first);
    }
}
