package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DeclarationProtectionTest {
    @Test
    void registersWithoutReadingBusinessClassesAndChecksOnlyMatchingCalls() {
        var protection = new DeclarationProtection();
        protection.register(declaration("div(II)I", "(DD)I"));
        assertEquals(0, protection.inspections());
        assertTrue(protection.protects("sample/Subject", null));
        assertTrue(protection.protects("sample/Caller", caller(method ->
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Subject", "div", "(II)I", false))));
        assertFalse(protection.protects("sample/Caller", caller(method ->
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Subject", "div", "(DD)I", false))));
        assertFalse(protection.protects("sample/Caller", caller(method ->
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Other", "div", "(II)I", false))));
        assertEquals(3, protection.inspections());
    }

    @Test
    void nativeOverwriteDoesNotCreateMigrationProtection() {
        var protection = new DeclarationProtection();
        protection.register(declaration("div(II)I", "(II)I"));
        assertFalse(protection.protects("sample/Subject", new byte[] {0}));
        assertFalse(protection.protects("sample/Caller", new byte[] {0}));
        assertEquals(0, protection.inspections());
    }

    @Test
    void checksImplicitSelectorsAndAliasesWithoutProtectingTheNewDescriptor() {
        var protection = new DeclarationProtection();
        var mixin = declaration("", "(DD)I");
        mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("aliases", List.of("divide")));
        protection.register(mixin);
        assertTrue(protection.protects("sample/Caller", caller(method ->
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Subject", "divide", "(II)I", false))));
        assertFalse(protection.protects("sample/Caller", caller(method ->
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Subject", "divide", "(DD)I", false))));
    }

    @Test
    void checksMethodHandlesAndNestedBootstrapConstants() {
        var protection = new DeclarationProtection();
        protection.register(declaration("div(II)I", "(DD)I"));
        var reference = new Handle(Opcodes.H_INVOKESTATIC, "sample/Subject", "div", "(II)I", false);
        var bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "sample/Bootstrap", "create", "()Ljava/lang/Object;", false);
        assertTrue(protection.protects("sample/Caller", caller(method -> method.visitLdcInsn(reference))));
        var constant = new ConstantDynamic("value", "Ljava/lang/Object;", bootstrap, reference);
        assertTrue(protection.protects("sample/Caller", caller(method ->
                method.visitInvokeDynamicInsn("run", "()V", bootstrap, constant))));
    }

    @Test
    void rejectsUninspectableCurrentInputsAndReleasesCandidateProtection() {
        var protection = new DeclarationProtection();
        protection.register(declaration("div(II)I", "(DD)I"));
        assertTrue(protection.protects("sample/Caller", new byte[] {0}));
        protection.clear();
        assertFalse(protection.protects("sample/Subject", new byte[] {0}));
        assertEquals(1, protection.inspections());
    }

    private static ClassNode declaration(String selector, String descriptor) {
        ClassNode node = new ClassNode();
        node.name = "sample/SubjectMixin";
        AnnotationNode mixin = new AnnotationNode(MixinAnnotations.MIXIN);
        mixin.values = new ArrayList<>(List.of("targets", List.of("sample.Subject")));
        node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "div", descriptor, null, null);
        AnnotationNode typed = new AnnotationNode(MixinAnnotations.TYPED_OVERWRITE);
        typed.values = new ArrayList<>(List.of("method", selector));
        method.visibleAnnotations = new ArrayList<>(List.of(typed));
        node.methods.add(method);
        return node;
    }

    private static byte[] caller(Consumer<MethodVisitor> instructions) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "sample/Caller", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        instructions.accept(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
