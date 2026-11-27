package love.crysh.mixinta.analysis;

import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.analysis.plan.CallSite;
import love.crysh.mixinta.analysis.plan.RepresentationPlan;
import love.crysh.mixinta.analysis.plan.RepresentationPlanApplier;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.bytecode.PrimitiveConversions;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划持有分析时的字节码快照，apply 可重复执行。
 * 所有输出通过栈帧计算和验证后才返回，失败时原始输入和计划均保持可复用。
 */
public final class TransformationPlan {
    private final Map<String, byte[]> classes;
    private final List<Migration> migrations;
    private final List<CallSite> calls;
    private final List<RepresentationPlan> representationPlans;
    private final ClassByteSource resources;

    TransformationPlan(Map<String, byte[]> classes, List<Migration> migrations, List<CallSite> calls,
            List<RepresentationPlan> representationPlans, ClassByteSource resources) {
        this.classes = ClassFiles.snapshot(classes);
        this.migrations = List.copyOf(migrations);
        this.calls = List.copyOf(calls);
        this.representationPlans = List.copyOf(representationPlans);
        this.resources = resources;
    }

    public int callSiteCount() {
        return calls.size();
    }

    /** 返回 TA 的实际写入类，包含调用方和生成规则转发方法的归属类。 */
    public Set<String> modifiedClasses() {
        Set<String> modified = new HashSet<>();
        migrations.forEach(value -> modified.add(value.original().owner()));
        calls.forEach(value -> modified.add(value.caller().owner()));
        for (RepresentationPlan plan : representationPlans) {
            plan.edits().forEach(value -> modified.add(value.location().method().owner()));
            plan.arguments().forEach(value -> modified.add(value.location().method().owner()));
            plan.locals().forEach(value -> modified.add(value.method().owner()));
            plan.bridges().forEach(value -> modified.add(value.method().owner()));
        }
        return Set.copyOf(modified);
    }

    /** 返回发布后失效的原方法标识，供域外引用检查使用。 */
    public Set<MethodKey> retiredMethods() {
        return migrations.stream().map(Migration::original).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Map<String, byte[]> apply() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ClassNode> nodes = AnalysisClassReader.readAll(classes, diagnostics);
        Map<MethodKey, AbstractInsnNode[]> originalInstructions = new HashMap<>();
        Map<MethodKey, Integer> temporaryBases = new HashMap<>();
        Set<String> modified = modifiedClasses();
        Map<MethodKey, AbstractInsnNode[]> representationInstructions = new HashMap<>();
        for (CallSite call : calls) {
            MethodKey key = call.caller();
            MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
            originalInstructions.computeIfAbsent(key, ignored -> method.instructions.toArray());
        }
        for (RepresentationPlan plan : representationPlans) {
            for (RepresentationPlan.Edit edit : plan.edits()) {
                MethodKey key = edit.location().method();
                MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
                representationInstructions.putIfAbsent(key, method.instructions.toArray());
            }
            for (RepresentationPlan.Argument argument : plan.arguments()) {
                MethodKey key = argument.location().method();
                MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
                representationInstructions.putIfAbsent(key, method.instructions.toArray());
            }
        }
        for (RepresentationPlan plan : representationPlans) {
            RepresentationPlanApplier.apply(plan, nodes, representationInstructions);
        }
        for (CallSite call : calls) {
            MethodKey key = call.caller();
            if (representationPlans.stream().flatMap(plan -> plan.arguments().stream())
                    .anyMatch(argument -> argument.location().method().equals(key)
                            && argument.location().instruction() == call.instruction())) continue;
            MethodNode method = ClassFiles.findMethod(nodes.get(key.owner()), key.name(), key.descriptor());
            AbstractInsnNode[] instructions = originalInstructions.computeIfAbsent(key, ignored -> method.instructions.toArray());
            int temporaryBase = temporaryBases.computeIfAbsent(key, ignored -> method.maxLocals);
            adaptCall(method, (MethodInsnNode) instructions[call.instruction()], call.migration(), temporaryBase);
        }
        for (Migration migration : migrations) {
            MethodKey original = migration.original();
            MethodKey implementation = migration.implementation();
            ClassNode owner = nodes.get(original.owner());
            owner.methods.remove(ClassFiles.findMethod(owner, original.name(), original.descriptor()));
            MethodNode replacement = ClassFiles.findMethod(owner, implementation.name(), implementation.descriptor());
            replacement.access = migration.access();
            replacement.visibleAnnotations.removeIf(annotation -> annotation.desc.equals(MixinAnnotations.TYPED_OVERWRITE));
        }

        ClassHierarchy hierarchy = new ClassHierarchy(nodes, resources);
        Map<String, byte[]> output = ClassFiles.snapshot(classes);
        for (String name : modified.stream().sorted().toList()) {
            try {
                for (MethodNode method : nodes.get(name).methods) {
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                                && nodes.containsKey(call.owner)) {
                            call.itf = (nodes.get(call.owner).access & Opcodes.ACC_INTERFACE) != 0;
                        }
                    }
                }
                ClassWriter writer = hierarchy.writer();
                nodes.get(name).accept(writer);
                byte[] bytes = writer.toByteArray();
                ClassNode verified = ClassFiles.read(bytes);
                for (MethodNode method : verified.methods) {
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                        new Analyzer<>(new BasicVerifier()).analyze(name, method);
                    }
                }
                output.put(name, bytes);
            } catch (AnalyzerException | RuntimeException failure) {
                diagnostics.add(new Diagnostic("OUTPUT_VERIFICATION_FAILED", null, -1, -1, name + " " + failure.getMessage()));
            }
        }
        TypedOverwriteException.throwIfErrors(diagnostics);
        return output;
    }

    private void adaptCall(MethodNode method, MethodInsnNode invocation, Migration migration, int temporaryBase) {
        Type[] originalParameters = Type.getArgumentTypes(migration.original().descriptor());
        Type[] newParameters = Type.getArgumentTypes(migration.implementation().descriptor());
        int[] locals = new int[originalParameters.length];
        int nextLocal = temporaryBase;
        for (int index = 0; index < originalParameters.length; index++) {
            locals[index] = nextLocal;
            nextLocal += originalParameters[index].getSize();
        }
        InsnList conversion = new InsnList();
        for (int index = originalParameters.length - 1; index >= 0; index--) {
            conversion.add(new VarInsnNode(originalParameters[index].getOpcode(Opcodes.ISTORE), locals[index]));
        }
        for (int index = 0; index < originalParameters.length; index++) {
            conversion.add(new VarInsnNode(originalParameters[index].getOpcode(Opcodes.ILOAD), locals[index]));
            for (int opcode : PrimitiveConversions.opcodes(originalParameters[index], newParameters[index])) {
                conversion.add(new InsnNode(opcode));
            }
        }
        method.instructions.insertBefore(invocation, conversion);
        invocation.owner = migration.original().owner();
        invocation.desc = migration.implementation().descriptor();
        method.maxLocals = Math.max(method.maxLocals, nextLocal);
    }
}
