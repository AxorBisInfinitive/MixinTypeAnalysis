package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.MethodReferences;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 候选仅登记选择器，真实加载的当前输入在进入 Mixin 或类定义前检查。 */
final class DeclarationProtection {
    private record Selector(String name, String originalDescriptor, String implementationDescriptor) {
        boolean matches(String method, String descriptor) {
            return name.equals(method) && !implementationDescriptor.equals(descriptor)
                    && (originalDescriptor == null || originalDescriptor.equals(descriptor));
        }
    }

    private final java.util.Map<String, Set<Selector>> targets = new ConcurrentHashMap<>();
    private final AtomicLong inspections = new AtomicLong();

    void register(ClassNode mixin) {
        Set<Selector> selectors = new LinkedHashSet<>();
        for (var method : mixin.methods) {
            var annotation = ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE);
            if (annotation == null) continue;
            String selector = (String) ClassFiles.value(annotation, "method", "");
            int descriptor = selector.indexOf('(');
            if (descriptor > 0) {
                String original = selector.substring(descriptor);
                if (!original.equals(method.desc)) selectors.add(new Selector(selector.substring(0, descriptor), original, method.desc));
            } else {
                selectors.add(new Selector(method.name, null, method.desc));
                Object aliases = ClassFiles.value(annotation, "aliases", List.of());
                if (aliases instanceof Collection<?> values) {
                    for (Object alias : values) selectors.add(new Selector((String) alias, null, method.desc));
                }
            }
        }
        if (selectors.isEmpty()) return;
        for (String target : MixinDiscovery.targets(mixin)) {
            targets.compute(target, (ignored, previous) -> {
                Set<Selector> combined = new LinkedHashSet<>(selectors);
                if (previous != null) combined.addAll(previous);
                return Set.copyOf(combined);
            });
        }
    }

    boolean protects(String name, byte[] bytes) {
        if (targets.containsKey(name)) return true;
        if (targets.isEmpty() || bytes == null) return false;
        inspections.incrementAndGet();
        boolean[] found = {false};
        MethodVisitor references = new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                Set<Selector> selectors = targets.get(owner);
                if (selectors != null && selectors.stream().anyMatch(selector -> selector.matches(method, descriptor))) found[0] = true;
            }

            @Override
            public void visitLdcInsn(Object constant) {
                MethodReferences.visitConstant(constant, handle ->
                        visitMethodInsn(0, handle.getOwner(), handle.getName(), handle.getDesc(), handle.isInterface()));
            }

            @Override
            public void visitInvokeDynamicInsn(String method, String descriptor, org.objectweb.asm.Handle bootstrap, Object... arguments) {
                visitLdcInsn(bootstrap);
                for (Object argument : arguments) visitLdcInsn(argument);
            }
        };
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    return found[0] ? null : references;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return true;
        }
        return found[0];
    }

    long inspections() { return inspections.get(); }

    void clear() { targets.clear(); }
}
