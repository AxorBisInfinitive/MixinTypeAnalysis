package love.crysh.exp.fabric.testmod.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

public final class MainDivTransformer implements ClassFileTransformer {
    private static final String MAIN_CLASS = "love/crysh/exp/Main";

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {
        if (!MAIN_CLASS.equals(className)) {
            return null;
        }

        ClassReader reader = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        MethodNode mainMethod = null;
        MethodNode divMethod = null;
        for (MethodNode method : classNode.methods) {
            if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) {
                mainMethod = method;
            } else if ("div".equals(method.name) && "(II)I".equals(method.desc)) {
                divMethod = method;
            }
        }

        if (mainMethod == null || divMethod == null) {
            throw new IllegalClassFormatException("Main.main or Main.div(II)I was not found");
        }

        rewriteDivisionAsDouble(divMethod);
        updateMainMethod(mainMethod);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        System.out.println("ASM changed Main.div to double division and println(double)");
        return writer.toByteArray();
    }

    private void rewriteDivisionAsDouble(MethodNode method) {
        method.desc = "(II)D";
        method.instructions.clear();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.I2D));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.I2D));
        method.instructions.add(new InsnNode(Opcodes.DDIV));
        method.instructions.add(new InsnNode(Opcodes.DRETURN));
    }

    private void updateMainMethod(MethodNode method) throws IllegalClassFormatException {
        boolean updatedCall = false;
        boolean updatedStore = false;
        boolean updatedLoad = false;
        boolean updatedPrint = false;

        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode invocation) {
                if ("div".equals(invocation.name) && "(II)I".equals(invocation.desc)) {
                    invocation.desc = "(II)D";
                    updatedCall = true;
                } else if ("println".equals(invocation.name) && "(I)V".equals(invocation.desc)) {
                    invocation.desc = "(D)V";
                    updatedPrint = true;
                }
            } else if (instruction instanceof VarInsnNode variable && variable.var == 2) {
                if (variable.getOpcode() == Opcodes.ISTORE) {
                    variable.setOpcode(Opcodes.DSTORE);
                    updatedStore = true;
                } else if (variable.getOpcode() == Opcodes.ILOAD) {
                    variable.setOpcode(Opcodes.DLOAD);
                    updatedLoad = true;
                }
            }
            instruction = next;
        }

        if (method.localVariables != null) {
            for (var local : method.localVariables) {
                if ("r2".equals(local.name)) {
                    local.desc = "D";
                }
            }
        }

        if (!updatedCall || !updatedStore || !updatedLoad || !updatedPrint) {
            throw new IllegalClassFormatException("Main.main does not match the expected div-to-double call pattern");
        }
    }
}
