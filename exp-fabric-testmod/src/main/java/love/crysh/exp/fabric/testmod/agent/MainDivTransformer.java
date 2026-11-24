package love.crysh.exp.fabric.testmod.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

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

        for (MethodNode method : classNode.methods) {
            if ("div".equals(method.name) && "(II)I".equals(method.desc)) {
                if (replaceDivision(method)) {
                    ClassWriter writer = new ClassWriter(reader, 0);
                    classNode.accept(writer);
                    System.out.println("ASM changed Main.div from division to subtraction");
                    return writer.toByteArray();
                }

                throw new IllegalClassFormatException("Main.div(II)I has no integer division instruction");
            }
        }

        throw new IllegalClassFormatException("Main.div(II)I was not found");
    }

    private boolean replaceDivision(MethodNode method) {
        boolean replaced = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction.getOpcode() == Opcodes.IDIV) {
                method.instructions.set(instruction, new InsnNode(Opcodes.ISUB));
                replaced = true;
            }
            instruction = next;
        }
        return replaced;
    }
}
