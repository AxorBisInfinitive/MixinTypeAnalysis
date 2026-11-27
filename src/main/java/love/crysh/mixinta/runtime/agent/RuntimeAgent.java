package love.crysh.mixinta.runtime.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 此类由 app ClassLoader 定义，回调使用 JDK 接口跨越 app 与 Knot 的边界。
 * ASM 和 Mixin API 必须由共同父 ClassLoader 提供。
 */
public final class RuntimeAgent implements ClassFileTransformer {
    private static final Logger LOG = Logger.getLogger("love.crysh.mixinta.runtime.agent");
    private static final String MIXIN_INFO = "org/spongepowered/asm/mixin/transformer/MixinInfo";
    private static final String MIXIN_TRANSFORMER = "org/spongepowered/asm/mixin/transformer/MixinTransformer";
    private record Callbacks(UnaryOperator<Object> preparation,
            BiFunction<ClassLoader, Object[], byte[]> publication, BiFunction<Object[], byte[], byte[]> cache) { }

    private static volatile Callbacks callbacks;
    private static volatile boolean hooked;
    private static volatile boolean cacheHooked;
    private static Instrumentation instrumentation;
    private static Throwable installationFailure;

    private RuntimeAgent() {
    }

    public static synchronized void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        if (RuntimeAgent.instrumentation != null) {
            if (installationFailure != null) throw new IllegalStateException("MixinTA agent installation failed", installationFailure);
            return;
        }
        RuntimeAgent.instrumentation = instrumentation;
        try {
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("MixinTA requires class retransformation");
            }
            RuntimeAgent agent = new RuntimeAgent();
            instrumentation.addTransformer(agent, true);
            Class<?> mixinInfo = Class.forName(MIXIN_INFO.replace('/', '.'), false, RuntimeAgent.class.getClassLoader());
            instrumentation.retransformClasses(mixinInfo);
            instrumentation.retransformClasses(Class.forName(MIXIN_TRANSFORMER.replace('/', '.'), false,
                    RuntimeAgent.class.getClassLoader()));
            if (!hooked || !cacheHooked) {
                throw new IllegalStateException("Unsupported MixinInfo or MixinTransformer implementation");
            }
        } catch (Exception | Error failure) {
            installationFailure = failure;
            throw failure;
        }
    }

    public static void connect(UnaryOperator<Object> prepare, BiFunction<ClassLoader, Object[], byte[]> publish,
            BiFunction<Object[], byte[], byte[]> cached) {
        Callbacks installed = new Callbacks(Objects.requireNonNull(prepare), Objects.requireNonNull(publish),
                Objects.requireNonNull(cached));
        synchronized (RuntimeAgent.class) {
            if (!hooked || !cacheHooked || callbacks != null) {
                throw new IllegalStateException("MixinTA agent is unavailable or already connected");
            }
            callbacks = installed;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            publish.apply(loaded.getClassLoader(), new Object[] {loaded.getName().replace('.', '/'), null, loaded});
        }
    }

    public static Class<?>[] loadedClasses(ClassLoader loader) {
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getClassLoader() == loader && !type.isArray()).toArray(Class<?>[]::new);
    }

    public static Object prepare(Object node) {
        Callbacks current = callbacks;
        return current == null ? node : current.preparation().apply(node);
    }

    public static byte[] cached(Object transformer, String name, byte[] bytes) {
        Callbacks current = callbacks;
        return current == null ? null : current.cache().apply(new Object[] {transformer, name}, bytes);
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_TRANSFORMER.equals(name)) {
            return hookCache(bytes);
        }
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_INFO.equals(name)) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int exits = 0;
            for (var method : node.methods) {
                if (method.name.equals("loadMixinClass")
                        && method.desc.equals("(Ljava/lang/String;)Lorg/objectweb/asm/tree/ClassNode;")) {
                    for (var instruction : method.instructions.toArray()) {
                        if (instruction.getOpcode() == Opcodes.ARETURN) {
                            method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    RuntimeAgent.class.getName().replace('.', '/'), "prepare",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                            method.instructions.insertBefore(instruction,
                                    new TypeInsnNode(Opcodes.CHECKCAST, "org/objectweb/asm/tree/ClassNode"));
                            exits++;
                        }
                    }
                }
            }
            if (exits != 1) {
                return null;
            }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            hooked = true;
            return writer.toByteArray();
        }
        Callbacks current = callbacks;
        if (current == null || redefined != null) {
            return null;
        }
        try {
            return current.publication().apply(loader, new Object[] {name, bytes});
        } catch (Throwable failure) {
            // JVM 会忽略 transformer 抛出的异常。非法 class 数据确保 defineClass 失败。
            try {
                LOG.log(Level.SEVERE, "MixinTA class transformation failed for " + name, failure);
            } catch (Throwable ignored) {
                // 日志设施失败时继续拒绝类定义。
            }
            return new byte[] {0};
        }
    }

    private byte[] hookCache(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hooks = 0;
        for (var method : node.methods) {
            if (method.name.equals("transformClassBytes")
                    && method.desc.equals("(Ljava/lang/String;Ljava/lang/String;[B)[B")) {
                LabelNode original = new LabelNode();
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeAgent.class.getName().replace('.', '/'),
                        "cached", "(Ljava/lang/Object;Ljava/lang/String;[B)[B", false));
                entry.add(new InsnNode(Opcodes.DUP));
                entry.add(new JumpInsnNode(Opcodes.IFNULL, original));
                entry.add(new InsnNode(Opcodes.ARETURN));
                entry.add(original);
                entry.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {"[B"}));
                entry.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(entry);
                method.maxStack = Math.max(method.maxStack, 3);
                hooks++;
            }
        }
        if (hooks != 1) {
            return null;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        cacheHooked = true;
        return writer.toByteArray();
    }
}
