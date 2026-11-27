package love.crysh.mixinta.runtime.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
    private static final String MIXIN_CONFIG = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String MIXIN_STATE = MIXIN_INFO + "$State";
    private static final String MIXIN_APPLICATOR = "org/spongepowered/asm/mixin/transformer/MixinApplicatorStandard";
    private record Callbacks(UnaryOperator<Object> preparation,
            BiFunction<ClassLoader, Object[], byte[]> publication, BiFunction<Object[], byte[], byte[]> cache) { }

    private static volatile Callbacks callbacks;
    private static volatile boolean hooked;
    private static volatile boolean cacheHooked;
    private static volatile boolean configurationHooked;
    private static volatile boolean applicationHooked;
    private static volatile boolean validationHooked;
    private static volatile boolean visibilityHooked;
    private static Instrumentation instrumentation;
    private static Throwable installationFailure;
    private static final Object CAPTURE_MONITOR = new Object();
    private static volatile Class<?> captureRequested;
    private static volatile byte[] capturedDefinition;

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
            instrumentation.retransformClasses(Class.forName(MIXIN_CONFIG.replace('/', '.'), false,
                    RuntimeAgent.class.getClassLoader()));
            instrumentation.retransformClasses(Class.forName(MIXIN_STATE.replace('/', '.'), false,
                    RuntimeAgent.class.getClassLoader()));
            instrumentation.retransformClasses(Class.forName(MIXIN_APPLICATOR.replace('/', '.'), false,
                    RuntimeAgent.class.getClassLoader()));
            if (!hooked || !cacheHooked || !configurationHooked || !applicationHooked || !validationHooked || !visibilityHooked) {
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
            if (!hooked || !cacheHooked || !configurationHooked || !applicationHooked || !validationHooked
                    || !visibilityHooked || callbacks != null) {
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

    /** 使用 JVM 的重转换输入读取既有定义，Mixin 转换链不会在此重复执行。 */
    public static byte[] captureDefinition(Class<?> type) throws java.lang.instrument.UnmodifiableClassException {
        synchronized (CAPTURE_MONITOR) {
            captureRequested = type;
            capturedDefinition = null;
            try {
                instrumentation.retransformClasses(type);
                if (capturedDefinition == null) throw new IllegalStateException("MISSING_DEFINITION_INPUT " + type.getName());
                return capturedDefinition;
            } finally {
                captureRequested = null;
                capturedDefinition = null;
            }
        }
    }

    public static Object prepare(Object node, Object mixin) {
        Callbacks current = callbacks;
        return current == null ? node : current.preparation().apply(new Object[] {"mixin", mixin, node});
    }

    public static void selected(Object configuration) {
        Callbacks current = callbacks;
        if (current != null) current.preparation().apply(new Object[] {"configuration", configuration});
    }

    public static Object project(Object node, Object mixin, boolean validating) {
        Callbacks current = callbacks;
        if (current != null) current.preparation().apply(new Object[] {validating ? "validation" : "application", mixin, node});
        return node;
    }

    public static boolean allowsStatic(Object context, Object method) {
        Callbacks current = callbacks;
        return current != null && Boolean.TRUE.equals(current.preparation().apply(new Object[] {"static", context, method}));
    }

    public static byte[] cached(Object transformer, String name, byte[] bytes) {
        Callbacks current = callbacks;
        return current == null ? null : current.cache().apply(new Object[] {transformer, name}, bytes);
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (redefined != null && redefined == captureRequested) {
            capturedDefinition = bytes.clone();
            return null;
        }
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_TRANSFORMER.equals(name)) {
            return hookCache(bytes);
        }
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_CONFIG.equals(name)) {
            return hookConfiguration(bytes);
        }
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_STATE.equals(name)) return hookValidation(bytes);
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_APPLICATOR.equals(name)) return hookVisibility(bytes);
        if (loader == RuntimeAgent.class.getClassLoader() && MIXIN_INFO.equals(name)) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            int exits = 0;
            int applications = 0;
            for (var method : node.methods) {
                if (method.name.equals("createContextFor")) {
                    for (var instruction : method.instructions.toArray()) {
                        if (instruction instanceof MethodInsnNode call && call.owner.equals(MIXIN_INFO)
                                && call.name.equals("getClassNode") && call.desc.equals("(I)L" + MIXIN_INFO + "$MixinClassNode;")) {
                            InsnList projection = new InsnList();
                            projection.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            projection.add(new InsnNode(Opcodes.ICONST_0));
                            projection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeAgent.class.getName().replace('.', '/'),
                                    "project", "(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", false));
                            projection.add(new TypeInsnNode(Opcodes.CHECKCAST, MIXIN_INFO + "$MixinClassNode"));
                            method.instructions.insert(instruction, projection);
                            method.maxStack += 2;
                            applications++;
                        }
                    }
                }
                if (method.name.equals("loadMixinClass")
                        && method.desc.equals("(Ljava/lang/String;)Lorg/objectweb/asm/tree/ClassNode;")) {
                    for (var instruction : method.instructions.toArray()) {
                        // 原方法在返回处丢弃 this 的帧类型，新增回调需要保留该局部变量。
                        if (instruction instanceof FrameNode frame) {
                            if (frame.local.isEmpty()) frame.local.add(MIXIN_INFO);
                            else frame.local.set(0, MIXIN_INFO);
                        }
                        if (instruction.getOpcode() == Opcodes.ARETURN) {
                            method.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, 0));
                            method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    RuntimeAgent.class.getName().replace('.', '/'), "prepare",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                            method.instructions.insertBefore(instruction,
                                    new TypeInsnNode(Opcodes.CHECKCAST, "org/objectweb/asm/tree/ClassNode"));
                            exits++;
                        }
                    }
                }
            }
            if (exits != 1 || applications != 1) {
                return null;
            }
            ClassWriter writer = new ClassWriter(0);
            node.methods.stream().filter(method -> method.name.equals("loadMixinClass"))
                    .forEach(method -> method.maxStack += 1);
            node.accept(writer);
            hooked = true;
            applicationHooked = true;
            return writer.toByteArray();
        }
        Callbacks current = callbacks;
        if (current == null || redefined != null) {
            return null;
        }
        try {
            return current.publication().apply(loader, new Object[] {name, bytes, domain});
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

    private byte[] hookConfiguration(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hooks = 0;
        for (var method : node.methods) {
            if (!method.name.equals("onSelect") || !method.desc.equals("()V")) continue;
            InsnList entry = new InsnList();
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeAgent.class.getName().replace('.', '/'),
                    "selected", "(Ljava/lang/Object;)V", false));
            method.instructions.insert(entry);
            method.maxStack = Math.max(method.maxStack, 1);
            hooks++;
        }
        if (hooks != 1) return null;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        configurationHooked = true;
        return writer.toByteArray();
    }

    private byte[] hookValidation(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hooks = 0;
        for (var method : node.methods) {
            if (!method.name.equals("validate") || !method.desc.equals("(L" + MIXIN_INFO + "$SubType;Ljava/util/List;)V")) continue;
            InsnList entry = new InsnList();
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_STATE, "validationClassNode", "L" + MIXIN_INFO + "$MixinClassNode;"));
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_STATE, "this$0", "L" + MIXIN_INFO + ";"));
            entry.add(new InsnNode(Opcodes.ICONST_1));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeAgent.class.getName().replace('.', '/'), "project",
                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", false));
            entry.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(entry);
            method.maxStack = Math.max(method.maxStack, 3);
            hooks++;
        }
        if (hooks != 1) return null;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        validationHooked = true;
        return writer.toByteArray();
    }

    private byte[] hookVisibility(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hooks = 0;
        for (var method : node.methods) {
            if (!method.name.equals("checkMethodVisibility") || !method.desc.equals(
                    "(Lorg/spongepowered/asm/mixin/transformer/MixinTargetContext;Lorg/objectweb/asm/tree/MethodNode;)V")) continue;
            LabelNode original = new LabelNode();
            InsnList entry = new InsnList();
            entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
            entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeAgent.class.getName().replace('.', '/'),
                    "allowsStatic", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            entry.add(new JumpInsnNode(Opcodes.IFEQ, original));
            entry.add(new InsnNode(Opcodes.RETURN));
            entry.add(original);
            entry.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(entry);
            method.maxStack = Math.max(method.maxStack, 2);
            hooks++;
        }
        if (hooks != 1) return null;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        visibilityHooked = true;
        return writer.toByteArray();
    }
}
