package love.crysh.mixinta.analysis;

import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.plan.CallSite;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.bytecode.MethodReferences;
import love.crysh.mixinta.bytecode.PrimitiveConversions;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class PrimitiveCallAnalyzer {
    private PrimitiveCallAnalyzer() { }

    static void analyzeMethod(MethodKey caller, MethodNode method, Map<MethodKey, Migration> migrations,
            ClassHierarchy hierarchy, List<CallSite> calls, List<Diagnostic> diagnostics) {
        List<CallSite> methodCalls = new ArrayList<>();
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (instruction instanceof MethodInsnNode invocation) {
                Migration migration = findMigration(invocation.owner, invocation.name, invocation.desc,
                        migrations, hierarchy, caller, index, diagnostics);
                if (migration != null && migration.representations().isEmpty()) {
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

    static void validateArguments(CallSite call, MethodNode caller, Frame<BasicValue> frame, List<Diagnostic> diagnostics) {
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

    static void checkConstant(Object constant, Map<MethodKey, Migration> migrations, ClassHierarchy hierarchy,
            MethodKey caller, int instruction, List<Diagnostic> diagnostics) {
        MethodReferences.visitConstant(constant, handle -> {
            Migration migration = findMigration(handle.getOwner(), handle.getName(), handle.getDesc(),
                    migrations, hierarchy, caller, instruction, diagnostics);
            if (migration != null) {
                diagnostics.add(problem("METHOD_HANDLE_NOT_IMPLEMENTED", caller, instruction, migration.original().toString()));
            }
        });
    }

    static Migration findMigration(String owner, String name, String descriptor, Map<MethodKey, Migration> migrations,
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

    private static Diagnostic problem(String code, MethodKey method, int instruction, String message) {
        return new Diagnostic(code, method, instruction, -1, message);
    }
}
