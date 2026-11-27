package love.crysh.exp.fabric.testmod.initialization;

import love.crysh.mixinta.runtime.TypedOverwriteRuntime;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider.BuiltinTransform;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 测试侧观察正式运行时，加载时序和故障由测试模组制造。 */
public final class InitializationProbeRuntime {
    private static final ClassLoader LOADER = InitializationProbeRuntime.class.getClassLoader();
    private static final List<Thread> THREADS = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Throwable> ERRORS = new ConcurrentLinkedQueue<>();
    private static final CountDownLatch WAITERS = new CountDownLatch(2);
    private static final Set<String> VERIFIED_MIXIN_INPUTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_DEFINITION_INPUTS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger ANALYSES = new AtomicInteger();

    private InitializationProbeRuntime() { }

    public static void bootstrap() {
        System.out.println("ADAPTER_START");
        Logger logger = Logger.getLogger("love.crysh.mixinta.runtime");
        logger.setLevel(Level.FINE);
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (message.startsWith("MixinTA verified Mixin input ")) {
                    VERIFIED_MIXIN_INPUTS.add(message.substring("MixinTA verified Mixin input ".length()));
                }
                if (message.startsWith("MixinTA verified definition input ")) {
                    VERIFIED_DEFINITION_INPUTS.add(message.substring("MixinTA verified definition input ".length()));
                }
                if (message.equals("MixinTA bootstrap connected")) System.out.println("AGENT_CONNECTED");
                if (message.equals("MixinTA declarations prepared")) System.out.println("DECLARATIONS_PREPARED");
                if (message.equals("MixinTA batch ready")) System.out.println("BATCH_READY");
                if (message.equals("MixinTA failure retained") && Boolean.getBoolean("probe.verifyFailure")) {
                    verifyPersistentFailure(false);
                }
                if (message.equals("MixinTA waiting for batch")) {
                    System.out.println("WAIT_FOR_BATCH=" + Thread.currentThread().getName());
                    WAITERS.countDown();
                }
                if (message.equals("MixinTA phase=ANALYZING")) {
                    ANALYSES.incrementAndGet();
                    System.out.println("ANALYSIS_BEGIN");
                    if (Boolean.getBoolean("probe.concurrent")) startConcurrentLoads();
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        if (state().equals("BOOTSTRAPPED")) System.out.println("AGENT_CONNECTED");
        try {
            FabricLauncherBase.getLauncher().getClassByteArray("love.crysh.exp.Main", true);
            throw new AssertionError("Application input unexpectedly unlocked during adapter construction");
        } catch (IllegalStateException expected) {
            System.out.println("EARLY_INPUT_UNAVAILABLE=" + expected.getMessage());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void startConcurrentLoads() {
        for (String name : List.of("fixture.business.Subject", "fixture.business.Caller")) {
            Thread thread = new Thread(() -> {
                try { Class.forName(name, false, LOADER); }
                catch (Throwable failure) { ERRORS.add(failure); }
            }, name);
            THREADS.add(thread);
            thread.start();
        }
        try {
            if (!WAITERS.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent loads did not reach the batch gate");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    public static String state() {
        return runtimeField("phase").toString();
    }

    public static void verify() throws Exception {
        if (state().equals("INACTIVE")) {
            if (runtimeField("result") != null || ANALYSES.get() != 0) throw new AssertionError("Inactive runtime performed analysis");
            System.out.println("INACTIVE_RUNTIME_VERIFIED");
            return;
        }
        if (!state().equals("READY") || ANALYSES.get() != 1) throw new AssertionError("state=" + state());
        for (Thread thread : THREADS) {
            thread.join(10000);
            if (thread.isAlive()) throw new AssertionError("Concurrent loading deadlocked");
        }
        if (!ERRORS.isEmpty()) throw new AssertionError("Concurrent loading failed", ERRORS.peek());
        if (!THREADS.isEmpty()) System.out.println("CONCURRENT_LOADS_COMPLETED");
        if (InitializationProbePlugin.loads != 1 || InitializationProbePlugin.applications != 1
                || InitializationProbePlugin.extensionsApplied != 1) throw new AssertionError("Plugin counts changed");
        System.out.println("COUNTS=1/1/1 ANALYSES=1");
    }

    /** 比较资源、前置结果、Mixin 快照和 TA 输出，并核对实际定义与缓存复用。 */
    public static void verifyPreprocessing(boolean widened) throws Exception {
        for (String name : java.util.List.of("fixture/business/Subject", "fixture/business/Caller")) {
            Class<?> type = Class.forName(name.replace('/', '.'), false, LOADER);
            Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            boolean application = source.equals(Path.of(System.getProperty("exp.fabric.jar")).toRealPath());
            Set<BuiltinTransform> expectedTransforms = application
                    ? Set.of(BuiltinTransform.STRIP_ENVIRONMENT, BuiltinTransform.CLASS_TWEAKS)
                    : Set.of(BuiltinTransform.STRIP_ENVIRONMENT);
            Set<BuiltinTransform> actualTransforms = FabricLoaderImpl.INSTANCE.getGameProvider()
                    .getBuiltinTransforms(name.replace('/', '.'));
            if (!actualTransforms.equals(expectedTransforms)) {
                throw new AssertionError("Unexpected Provider policy " + name + " " + actualTransforms);
            }
            ClassNode raw;
            try (var input = LOADER.getResourceAsStream(name + ".class")) {
                if (input == null) throw new AssertionError("Missing raw resource " + name);
                raw = node(input.readAllBytes());
            }
            if (raw.methods.stream().noneMatch(method -> method.name.equals("clientOnly"))) {
                throw new AssertionError("Missing raw client method " + name);
            }
            if (name.equals("fixture/business/Subject") && raw.methods.stream()
                    .noneMatch(method -> method.name.equals("helper") && Modifier.isPrivate(method.access))) {
                throw new AssertionError("Raw helper is not private");
            }
            System.out.println("RAW_INPUT_VERIFIED " + name + " clientOnly=present");
            var stages = new LinkedHashMap<String, byte[]>();
            stages.put("PRE_MIXIN", snapshotMap("beforeMixins").get(name));
            stages.put("POST_MIXIN", snapshotMap("afterMixins").get(name));
            stages.put("TA_OUTPUT", snapshotMap("outputs").get(name));
            for (var stage : stages.entrySet()) {
                ClassNode snapshot = node(stage.getValue());
                if (snapshot.methods.stream().anyMatch(method -> method.name.equals("clientOnly"))
                        || snapshot.methods.stream().noneMatch(method -> method.name.equals("serverOnly"))) {
                    throw new AssertionError("Environment stripping changed at " + stage.getKey() + " " + name);
                }
                if (name.equals("fixture/business/Subject")) {
                    int access = snapshot.methods.stream().filter(method -> method.name.equals("helper"))
                            .findFirst().orElseThrow().access;
                    if (Modifier.isPublic(access) != widened || Modifier.isPrivate(access) == widened) {
                        throw new AssertionError("ClassTweaker visibility changed at " + stage.getKey());
                    }
                    int protectedAccess = snapshot.methods.stream().filter(method -> method.name.equals("protectedHelper"))
                            .findFirst().orElseThrow().access;
                    int packageAccess = snapshot.methods.stream().filter(method -> method.name.equals("packageHelper"))
                            .findFirst().orElseThrow().access;
                    if (!Modifier.isProtected(protectedAccess)
                            || (packageAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) != 0) {
                        throw new AssertionError("Unexpected package access widening at " + stage.getKey());
                    }
                    String descriptor = stage.getKey().equals("TA_OUTPUT") ? "(DD)I" : "(II)I";
                    if (snapshot.methods.stream().noneMatch(method -> method.name.equals("div")
                            && method.desc.equals(descriptor))) {
                        throw new AssertionError("Unexpected target descriptor at " + stage.getKey());
                    }
                }
                System.out.println(stage.getKey() + "_VERIFIED " + name + " clientOnly=absent");
            }
            try {
                type.getDeclaredMethod("clientOnly");
                throw new AssertionError("Client method present in defined class " + name);
            } catch (NoSuchMethodException expected) { }
            type.getDeclaredMethod("serverOnly");
            if (!VERIFIED_MIXIN_INPUTS.contains(name) || !VERIFIED_DEFINITION_INPUTS.contains(name)) {
                throw new AssertionError("Actual definition did not reuse verified snapshots " + name);
            }
            System.out.println("INPUT_CHAIN_VERIFIED " + name);
        }
        Class<?> subject = Class.forName("fixture.business.Subject", false, LOADER);
        int access = subject.getDeclaredMethod("helper").getModifiers();
        if (Modifier.isPublic(access) != widened || Modifier.isPrivate(access) == widened) {
            throw new AssertionError("Defined helper has unexpected visibility");
        }
        subject.getDeclaredMethod("div", double.class, double.class);
        try {
            subject.getDeclaredMethod("div", int.class, int.class);
            throw new AssertionError("Old descriptor present in defined target");
        } catch (NoSuchMethodException expected) { }
        var run = node(snapshotMap("outputs").get("fixture/business/Caller")).methods.stream()
                .filter(method -> method.name.equals("run")).findFirst().orElseThrow();
        int casts = 0;
        int calls = 0;
        for (var instruction : run.instructions) {
            if (instruction.getOpcode() == Opcodes.I2D) casts++;
            if (instruction instanceof MethodInsnNode call && call.owner.equals("fixture/business/Subject")
                    && call.name.equals("div")) {
                if (!call.desc.equals("(DD)I")) throw new AssertionError("Old call descriptor " + call.desc);
                calls++;
            }
        }
        if (casts != 2 || calls != 1) throw new AssertionError("Missing adapted caller");
        System.out.println("PREPROCESSING_VERIFIED widened=" + widened);
    }

    public static void verifyPersistentFailure(boolean published) {
        Object saved = runtimeField("failure");
        if (saved == null || (runtimeField("result") != null) != published || !state().equals("FAILED")) {
            throw new AssertionError("Failure not retained");
        }
        Object cause = field(saved, "cause");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                TypedOverwriteRuntime.initialize();
                throw new AssertionError("Failure was ignored");
            } catch (IllegalStateException expected) {
                if (expected.getCause() != cause) throw new AssertionError("Failure cause changed", expected);
            }
        }
        System.out.println("FAILURE_RETAINED OUTPUTS_" + (published ? "PUBLISHED" : "ABSENT"));
    }

    private static Object runtimeField(String name) {
        try {
            var field = TypedOverwriteRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> snapshotMap(String name) {
        return (Map<String, byte[]>) field(runtimeField("result"), name);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result, 0);
        return result;
    }
}
