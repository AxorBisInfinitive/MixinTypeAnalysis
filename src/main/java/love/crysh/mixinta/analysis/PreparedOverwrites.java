package love.crysh.mixinta.analysis;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

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
    private final ClassLoader resources;

    PreparedOverwrites(Map<String, byte[]> originals, Map<String, byte[]> mixins,
            List<Migration> migrations, ClassLoader resources) {
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
        Map<String, ClassNode> nodes = ClassFiles.readAll(snapshot, diagnostics);
        Map<String, ClassNode> originalNodes = ClassFiles.readAll(originals, diagnostics);
        for (String name : originals.keySet()) {
            if (!nodes.containsKey(name)) {
                diagnostics.add(new Diagnostic("MISSING_INPUT_CLASS", null, -1, -1, name));
            }
        }
        Map<String, ClassNode> hierarchyNodes = new HashMap<>(nodes);
        hierarchyNodes.putAll(originalNodes);
        ClassHierarchy hierarchy = new ClassHierarchy(hierarchyNodes, resources);
        Map<MethodKey, Migration> byTarget = new HashMap<>();
        for (Migration migration : migrations) {
            byTarget.put(migration.original(), migration);
            validateMergedMethods(migration, nodes, originalNodes, diagnostics);
        }
        Set<MethodKey> replacedMethods = byTarget.keySet();
        List<CallSite> calls = new ArrayList<>();
        for (ClassNode node : nodes.values()) {
            for (MethodNode method : node.methods) {
                MethodKey caller = new MethodKey(node.name, method.name, method.desc);
                if (replacedMethods.contains(caller)) {
                    continue;
                }
                analyzeMethod(caller, method, byTarget, hierarchy, calls, diagnostics);
            }
        }
        ClassFiles.throwIfErrors(diagnostics);
        return new TransformationPlan(snapshot, migrations, calls, resources);
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
            if (ClassFiles.annotation(original.visibleAnnotations, ClassFiles.FINAL) != null) {
                diagnostics.add(problem("FINAL_TARGET", key, -1, "Parameter migration conflicts with @Final"));
            }
            if (ClassFiles.annotation(original.visibleAnnotations, ClassFiles.MERGED) != null
                    || !Arrays.equals(body(baseline), body(original))) {
                diagnostics.add(problem("TARGET_CHANGED_AFTER_PREPARATION", key, -1,
                        "The original method was modified before parameter migration"));
            }
        }
        if (implementation == null
                || ClassFiles.annotation(implementation.visibleAnnotations, ClassFiles.TYPED_OVERWRITE) == null) {
            diagnostics.add(problem("IMPLEMENTATION_MISSING", implementationKey, -1,
                    "The prepared typed implementation must be merged before analysis"));
        }
    }

    private void analyzeMethod(MethodKey caller, MethodNode method, Map<MethodKey, Migration> migrations,
            ClassHierarchy hierarchy, List<CallSite> calls, List<Diagnostic> diagnostics) {
        List<CallSite> methodCalls = new ArrayList<>();
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (instruction instanceof MethodInsnNode invocation) {
                Migration migration = findMigration(invocation.owner, invocation.name, invocation.desc,
                        migrations, hierarchy, caller, index, diagnostics);
                if (migration != null) {
                    methodCalls.add(new CallSite(caller, index, migration));
                }
            } else if (instruction instanceof LdcInsnNode constant) {
                checkConstant(constant.cst, migrations, hierarchy, caller, index, diagnostics);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                checkConstant(dynamic.bsm, migrations, hierarchy, caller, index, diagnostics);
                for (Object argument : dynamic.bsmArgs) {
                    checkConstant(argument, migrations, hierarchy, caller, index, diagnostics);
                }
            }
        }
        if (methodCalls.isEmpty()) {
            return;
        }
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicInterpreter()).analyze(caller.owner(), method);
        } catch (AnalyzerException | RuntimeException failure) {
            diagnostics.add(problem("INVALID_CALLER_BYTECODE", caller, -1, failure.getMessage()));
            return;
        }
        for (CallSite call : methodCalls) {
            validateArguments(call, method, frames[call.instruction()], diagnostics);
        }
        calls.addAll(methodCalls);
    }

    private void validateArguments(CallSite call, MethodNode caller, Frame<BasicValue> frame, List<Diagnostic> diagnostics) {
        Type[] parameters = Type.getArgumentTypes(call.migration().original().descriptor());
        int temporarySlots = Arrays.stream(parameters).mapToInt(Type::getSize).sum();
        if (caller.maxLocals + temporarySlots > 65535) {
            diagnostics.add(problem("LOCAL_VARIABLE_LIMIT", call.caller(), call.instruction(), "Argument temporaries exceed max_locals"));
        }
        if (frame == null) {
            return;
        }
        if (frame.getStackSize() < parameters.length) {
            diagnostics.add(problem("INVALID_ARGUMENT_STACK", call.caller(), call.instruction(), "Argument stack is incomplete"));
            return;
        }
        int start = frame.getStackSize() - parameters.length;
        for (int index = 0; index < parameters.length; index++) {
            Type actual = frame.getStack(start + index).getType();
            if (actual == null || PrimitiveConversions.computationalSort(actual)
                    != PrimitiveConversions.computationalSort(parameters[index])) {
                diagnostics.add(new Diagnostic("ARGUMENT_TYPE_MISMATCH", call.caller(), call.instruction(), index,
                        String.valueOf(actual) + " expected " + parameters[index]));
            }
        }
    }

    private void checkConstant(Object constant, Map<MethodKey, Migration> migrations, ClassHierarchy hierarchy,
            MethodKey caller, int instruction, List<Diagnostic> diagnostics) {
        if (constant instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
            Migration migration = findMigration(handle.getOwner(), handle.getName(), handle.getDesc(),
                    migrations, hierarchy, caller, instruction, diagnostics);
            if (migration != null) {
                diagnostics.add(problem("METHOD_HANDLE_NOT_IMPLEMENTED", caller, instruction, migration.original().toString()));
            }
        } else if (constant instanceof ConstantDynamic dynamic) {
            checkConstant(dynamic.getBootstrapMethod(), migrations, hierarchy, caller, instruction, diagnostics);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkConstant(dynamic.getBootstrapMethodArgument(index), migrations, hierarchy, caller, instruction, diagnostics);
            }
        }
    }

    private Migration findMigration(String owner, String name, String descriptor, Map<MethodKey, Migration> migrations,
            ClassHierarchy hierarchy, MethodKey caller, int instruction, List<Diagnostic> diagnostics) {
        boolean candidate = migrations.keySet().stream().anyMatch(key -> key.name().equals(name) && key.descriptor().equals(descriptor));
        if (!candidate) {
            return null;
        }
        try {
            MethodKey resolved = hierarchy.resolve(owner, name, descriptor);
            return migrations.get(resolved);
        } catch (IllegalArgumentException failure) {
            diagnostics.add(problem("MISSING_HIERARCHY", caller, instruction, failure.getMessage()));
            return null;
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
