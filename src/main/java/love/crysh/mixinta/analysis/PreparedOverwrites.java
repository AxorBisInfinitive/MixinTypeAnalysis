package love.crysh.mixinta.analysis;

import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.analysis.declaration.ContainerRuleReader;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRuleReader;
import love.crysh.mixinta.analysis.plan.CallSite;
import love.crysh.mixinta.analysis.plan.RepresentationPlan;
import love.crysh.mixinta.analysis.representation.RepresentationBatch;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mixins 的返回值用于后续 Mixin 处理。
 * analyze 接收同一批类在 Mixin 处理后的完整快照，其中应同时存在原方法和 typed 实现。
 */
public final class PreparedOverwrites {
    private final Map<String, byte[]> originals;
    private final Map<String, byte[]> mixins;
    private final List<Migration> migrations;
    private final ClassByteSource resources;

    PreparedOverwrites(Map<String, byte[]> originals, Map<String, byte[]> mixins,
            List<Migration> migrations, ClassByteSource resources) {
        this.originals = ClassFiles.snapshot(originals);
        this.mixins = ClassFiles.snapshot(mixins);
        this.migrations = List.copyOf(migrations);
        this.resources = resources;
    }

    public Map<String, byte[]> mixins() {
        return ClassFiles.snapshot(mixins);
    }

    public TransformationPlan analyze(Map<String, byte[]> afterMixins) {
        Map<String, byte[]> snapshot = ClassFiles.snapshot(afterMixins);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ClassNode> nodes = AnalysisClassReader.readAll(snapshot, diagnostics);
        Map<String, ClassNode> originalNodes = AnalysisClassReader.readAll(originals, diagnostics);
        for (String name : originals.keySet()) {
            if (!nodes.containsKey(name)) {
                diagnostics.add(new Diagnostic("MISSING_INPUT_CLASS", null, -1, -1, name));
            }
        }
        Map<String, ClassNode> hierarchyNodes = new HashMap<>(nodes);
        hierarchyNodes.putAll(originalNodes);
        ClassHierarchy hierarchy = new ClassHierarchy(hierarchyNodes, resources);
        ClassHierarchy outputHierarchy = new ClassHierarchy(nodes, resources);
        Map<MethodKey, Migration> referenceTargets = new HashMap<>();
        migrations.stream().filter(migration -> !migration.representations().isEmpty())
                .forEach(migration -> referenceTargets.put(migration.original(), migration));
        Map<MethodKey, ContainerContract> containerContracts = new HashMap<>();
        for (Migration migration : migrations) {
            for (RepresentationMigration representation : migration.representations()) {
                for (ContainerContract definition : representation.containers()) {
                    try {
                        ContainerRuleReader.validate(definition.method(), definition.summary(), outputHierarchy);
                        ContainerContract previous = containerContracts.putIfAbsent(definition.method(), definition);
                        if (previous != null && !previous.equals(definition)) {
                            throw new IllegalArgumentException("Conflicting container contracts " + previous.id() + " and " + definition.id());
                        }
                        if (migrations.stream().anyMatch(other -> other.original().equals(definition.method())
                                || other.implementation().equals(definition.method()))) {
                            throw new IllegalArgumentException("Container rule overlaps a TypedOverwrite target " + definition.method());
                        }
                    } catch (RuntimeException failure) {
                        diagnostics.add(new Diagnostic("CONTAINER_RULE_CONFLICT", representation.target(), -1,
                                representation.parameter(), definition.id() + " version=" + definition.version() + " " + failure.getMessage()));
                    }
                }
            }
        }
        for (ContainerContract definition : containerContracts.values()) {
            ClassNode owner = nodes.get(definition.method().owner());
            MethodNode method = owner == null ? null : ClassFiles.findMethod(owner, definition.method().name(), definition.method().descriptor());
            if (method == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                Migration migration = PrimitiveCallAnalyzer.findMigration(call.owner, call.name, call.desc, referenceTargets,
                        outputHierarchy, definition.method(), -1, diagnostics);
                if (migration != null) {
                    diagnostics.add(new Diagnostic("CONTAINER_RULE_CONFLICT", definition.method(), -1, -1,
                            definition.id() + " contains a call requiring reference migration " + migration.original()));
                }
            }
        }
        TypedOverwriteException.throwIfErrors(diagnostics);
        Map<MethodKey, Migration> byTarget = new HashMap<>();
        List<RepresentationMigration> representations = new ArrayList<>();
        for (Migration migration : migrations) {
            byTarget.put(migration.original(), migration);
            validateMergedMethods(migration, nodes, originalNodes, diagnostics);
            if (!migration.representations().isEmpty()) {
                List<RepresentationMigration> resolved = migration.representations().stream()
                        .map(value -> RepresentationRuleReader.resolve(value, nodes, diagnostics)).toList();
                representations.addAll(resolved);
            }
        }
        TypedOverwriteException.throwIfErrors(diagnostics);
        List<RepresentationPlan> representationPlans = new ArrayList<>();
        if (!representations.isEmpty()) {
            var batch = RepresentationBatch.analyze(representations, nodes, byTarget, hierarchy);
            diagnostics.addAll(batch.diagnostics());
            batch.plan().ifPresent(representationPlans::add);
        }
        Set<MethodKey> replacedMethods = byTarget.keySet();
        List<CallSite> calls = new ArrayList<>();
        for (ClassNode node : nodes.values()) {
            for (MethodNode method : node.methods) {
                MethodKey caller = new MethodKey(node.name, method.name, method.desc);
                if (replacedMethods.contains(caller)) {
                    continue;
                }
                PrimitiveCallAnalyzer.analyzeMethod(caller, method, byTarget, hierarchy, calls, diagnostics);
            }
        }
        TypedOverwriteException.throwIfErrors(diagnostics);
        return new TransformationPlan(snapshot, migrations, calls, representationPlans, resources);
    }

    private void validateMergedMethods(Migration migration, Map<String, ClassNode> nodes,
            Map<String, ClassNode> originalNodes, List<Diagnostic> diagnostics) {
        MethodKey key = migration.original();
        ClassNode owner = nodes.get(key.owner());
        if (owner == null) {
            return;
        }
        MethodNode original = ClassFiles.findMethod(owner, key.name(), key.descriptor());
        MethodKey implementationKey = migration.implementation();
        MethodNode implementation = ClassFiles.findMethod(owner, implementationKey.name(), implementationKey.descriptor());
        if (original == null) {
            diagnostics.add(problem("ORIGINAL_METHOD_MISSING", key, -1, "Original method is absent after Mixin processing"));
        } else {
            MethodNode baseline = ClassFiles.findMethod(originalNodes.get(key.owner()), key.name(), key.descriptor());
            if (ClassFiles.annotation(original.visibleAnnotations, MixinAnnotations.FINAL) != null) {
                diagnostics.add(problem("FINAL_TARGET", key, -1, "Parameter migration conflicts with @Final"));
            }
            if (ClassFiles.annotation(original.visibleAnnotations, MixinAnnotations.MERGED) != null
                    || !Arrays.equals(body(baseline), body(original))) {
                diagnostics.add(problem("TARGET_CHANGED_AFTER_PREPARATION", key, -1,
                        "The original method was modified before parameter migration"));
            }
        }
        if (implementation == null
                || ClassFiles.annotation(implementation.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE) == null) {
            diagnostics.add(problem("IMPLEMENTATION_MISSING", implementationKey, -1,
                    "The prepared typed implementation must be merged before analysis"));
        }
    }

    private static byte[] body(MethodNode method) {
        MethodNode copy = new MethodNode(Opcodes.ASM9, 0, "body", method.desc, null, null);
        method.accept(copy);
        copy.access = 0;
        copy.name = "body";
        copy.signature = null;
        copy.exceptions.clear();
        copy.visibleAnnotations = null;
        copy.invisibleAnnotations = null;
        copy.visibleTypeAnnotations = null;
        copy.invisibleTypeAnnotations = null;
        copy.visibleParameterAnnotations = null;
        copy.invisibleParameterAnnotations = null;
        copy.visibleLocalVariableAnnotations = null;
        copy.invisibleLocalVariableAnnotations = null;
        copy.localVariables = null;
        copy.parameters = null;
        copy.attrs = null;
        copy.maxLocals = 0;
        copy.maxStack = 0;
        for (AbstractInsnNode instruction : copy.instructions.toArray()) {
            if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) {
                copy.instructions.remove(instruction);
            }
        }
        ClassNode container = new ClassNode();
        container.version = Opcodes.V25;
        container.access = Opcodes.ACC_PUBLIC;
        container.name = "MethodBody";
        container.superName = "java/lang/Object";
        container.methods.add(copy);
        return ClassFiles.write(container);
    }

    private static Diagnostic problem(String code, MethodKey method, int instruction, String message) {
        return new Diagnostic(code, method, instruction, -1, message);
    }
}
