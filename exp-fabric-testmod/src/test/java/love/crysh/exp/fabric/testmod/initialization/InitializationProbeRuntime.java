package love.crysh.exp.fabric.testmod.initialization;

import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider.BuiltinTransform;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;

/** 测试原型复用正式 Agent 和分析器，单独验证自动初始化调度。 */
public final class InitializationProbeRuntime {
    private static final ClassLoader LOADER = InitializationProbeRuntime.class.getClassLoader();
    private static final Object MONITOR = new Object();
    private static final Set<String> CLASSES = new LinkedHashSet<>();
    private static final Set<String> MIXINS = new LinkedHashSet<>();
    private static final Set<String> DEFINED = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_MIXIN_INPUTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_DEFINITION_INPUTS = ConcurrentHashMap.newKeySet();
    private static final CountDownLatch CONCURRENT_REQUEST = new CountDownLatch(1);
    private static final ThreadLocal<String> EXPECTED = new ThreadLocal<>();
    private static final Map<String, byte[]> ORIGINALS = new LinkedHashMap<>();
    private static final Set<String> RETIRED = new LinkedHashSet<>();
    private static volatile Map<String, byte[]> preparedMixins = Map.of();
    private static volatile Map<String, byte[]> afterMixins = Map.of();
    private static volatile Map<String, byte[]> outputs;
    private static volatile Throwable failure;
    private static volatile String state = "NEW";
    private static PreparedOverwrites prepared;
    private static Thread owner;
    private static int analyses;

    private InitializationProbeRuntime() { }

    public static void bootstrap() throws Exception {
        System.out.println("ADAPTER_START");
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue metadata = mod.getMetadata().getCustomValue("initializationProbe");
            if (metadata == null) continue;
            for (CustomValue value : metadata.getAsObject().get("classes").getAsArray()) {
                CLASSES.add(value.getAsString().replace('.', '/'));
            }
            for (CustomValue value : metadata.getAsObject().get("mixins").getAsArray()) {
                MIXINS.add(value.getAsString().replace('.', '/'));
            }
        }
        try {
            FabricLauncherBase.getLauncher().getClassByteArray("love.crysh.exp.Main", true);
            throw new AssertionError("Application input unexpectedly unlocked during adapter construction");
        } catch (IllegalStateException expected) {
            System.out.println("EARLY_INPUT_UNAVAILABLE=" + expected.getMessage());
        }
        Path jar = Path.of(TypedOverwriteAnalyzer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Probe requires packaged MixinTA");
        if (!Boolean.getBoolean("probe.skipSupportPreload")) {
            try (JarFile support = new JarFile(jar.toFile())) {
                for (var entry : support.stream().filter(value -> value.getName().endsWith(".class")
                        && value.getName().startsWith("love/crysh/mixinta/")
                        && !value.getName().startsWith("love/crysh/mixinta/runtime/")).toList()) {
                    String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                    Class.forName(name, false, LOADER);
                }
            }
            System.out.println("SUPPORT_CLASSES_PRELOADED");
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--add-modules=jdk.attach", "-cp", jar.toString(), "love.crysh.mixinta.runtime.agent.AgentAttacher",
                Long.toString(ProcessHandle.current().pid()), jar.toString()).inheritIO().start();
        try {
            if (!helper.waitFor(30, TimeUnit.SECONDS) || helper.exitValue() != 0) {
                throw new IllegalStateException("Probe Agent attachment failed");
            }
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
        }
        Class<?> agent = Class.forName("love.crysh.mixinta.runtime.agent.RuntimeAgent", true,
                ClassLoader.getSystemClassLoader());
        UnaryOperator<Object> preparation = InitializationProbeRuntime::prepareMixin;
        BiFunction<ClassLoader, Object[], byte[]> publication = InitializationProbeRuntime::published;
        BiFunction<String, byte[], byte[]> cache = InitializationProbeRuntime::beforeTransform;
        agent.getMethod("connect", UnaryOperator.class, BiFunction.class, BiFunction.class)
                .invoke(null, preparation, publication, cache);
        state = "BOOTSTRAPPED";
        System.out.println("AGENT_CONNECTED");
    }

    public static String state() {
        return state;
    }

    public static void verify() {
        checkFailure();
        if (!state.equals("READY") || analyses != 1) throw new AssertionError("state=" + state + " analyses=" + analyses);
        if (InitializationProbePlugin.loads != 1 || InitializationProbePlugin.applications != 1
                || InitializationProbePlugin.extensionsApplied != 1) {
            throw new AssertionError("Plugin counts " + InitializationProbePlugin.loads + "/"
                    + InitializationProbePlugin.applications + "/" + InitializationProbePlugin.extensionsApplied);
        }
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
            stages.put("PRE_MIXIN", ORIGINALS.get(name));
            stages.put("POST_MIXIN", afterMixins.get(name));
            stages.put("TA_OUTPUT", outputs.get(name));
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
        var run = node(outputs.get("fixture/business/Caller")).methods.stream()
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
        Throwable first = failure;
        if (first == null || (outputs != null) != published || !state.equals("FAILED")) throw new AssertionError("Failure not retained");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                beforeTransform("fixture.business.Caller", new byte[0]);
                throw new AssertionError("Failure was ignored");
            } catch (IllegalStateException expected) {
                if (expected.getCause() != first) throw new AssertionError("Failure cause changed", expected);
            }
        }
        System.out.println("FAILURE_RETAINED OUTPUTS_" + (published ? "PUBLISHED" : "ABSENT"));
    }

    private static Object prepareMixin(Object value) {
        ClassNode original = (ClassNode) value;
        byte[] replacement = preparedMixins.get(original.name);
        if (replacement == null) return value;
        ClassNode result = new ClassNode();
        new ClassReader(replacement).accept(result, 0);
        return result;
    }

    private static byte[] beforeTransform(String binaryName, byte[] input) {
        if (binaryName == null) return null;
        String name = binaryName.replace('.', '/');
        if (name.equals(EXPECTED.get())) {
            EXPECTED.remove();
            return null;
        }
        boolean participant = CLASSES.contains(name);
        boolean run = false;
        synchronized (MONITOR) {
            if (failure != null) {
                if (participant) checkFailure();
                return null;
            }
            if (owner == Thread.currentThread()) {
                if (participant) throw fail("REENTRANT_DEFINITION " + name + " state=" + state);
                return null;
            }
            while (owner != null && participant && failure == null) {
                System.out.println("WAIT_FOR_BATCH=" + name);
                CONCURRENT_REQUEST.countDown();
                try {
                    MONITOR.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw fail("INTERRUPTED_BATCH_WAIT " + name);
                }
            }
            if (participant) checkFailure();
            boolean eager = !Boolean.getBoolean("probe.deferAnalysis");
            if (owner == null && outputs == null && (prepared == null || eager || participant)) {
                owner = Thread.currentThread();
                state = "PREPARING";
                run = true;
            }
        }
        if (run) {
            try {
                prepareDeclarations();
                if (!Boolean.getBoolean("probe.deferAnalysis") || participant) analyze();
            } catch (Throwable cause) {
                retain(cause);
                checkFailure();
            } finally {
                synchronized (MONITOR) {
                    owner = null;
                    if (failure == null && outputs == null) state = "BOOTSTRAPPED";
                    MONITOR.notifyAll();
                }
            }
        }
        if (!participant || outputs == null) return null;
        checkFailure();
        if (!Arrays.equals(ORIGINALS.get(name), input)) throw fail("INPUT_CHANGED " + name);
        VERIFIED_MIXIN_INPUTS.add(name);
        return afterMixins.get(name).clone();
    }

    private static void prepareDeclarations() throws Exception {
        if (prepared != null) return;
        for (String name : CLASSES) ORIGINALS.put(name, input(name));
        Map<String, byte[]> mixins = new LinkedHashMap<>();
        for (String name : MIXINS) mixins.put(name, input(name));
        for (String name : TypedOverwriteAnalyzer.requiredRuleClasses(mixins)) {
            if (!ORIGINALS.containsKey(name) && !mixins.containsKey(name)) ORIGINALS.put(name, input(name));
        }
        prepared = new TypedOverwriteAnalyzer(LOADER).prepare(ORIGINALS, mixins);
        preparedMixins = prepared.mixins();
        System.out.println("DECLARATIONS_PREPARED");
    }

    private static byte[] input(String name) throws Exception {
        byte[] bytes = FabricLauncherBase.getLauncher().getClassByteArray(name.replace('/', '.'), true);
        if (bytes == null) throw new IllegalStateException("Missing probe input " + name);
        return bytes;
    }

    private static void analyze() throws Exception {
        IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        transformer.couldTransformClass(MixinEnvironment.getCurrentEnvironment(),
                "love.crysh.exp.fabric.testmod.initialization.BootstrapSentinel");
        checkFailure();
        state = "ANALYZING";
        analyses++;
        System.out.println("ANALYSIS_BEGIN");
        if (Boolean.getBoolean("probe.pauseAnalysis") && !CONCURRENT_REQUEST.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent load did not reach the batch gate");
        }
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
        for (var entry : ORIGINALS.entrySet()) {
            EXPECTED.set(entry.getKey());
            try {
                String name = entry.getKey().replace('/', '.');
                snapshots.put(entry.getKey(), transformer.transformClassBytes(name, name, entry.getValue().clone()));
            } finally {
                EXPECTED.remove();
            }
        }
        Map<String, byte[]> transformed = prepared.analyze(snapshots).apply();
        for (String name : CLASSES) {
            if (DEFINED.contains(name) && !Arrays.equals(ORIGINALS.get(name), transformed.get(name))) {
                throw fail("EARLY_MODIFIED_CLASS " + name);
            }
        }
        for (var entry : ORIGINALS.entrySet()) {
            ClassNode before = node(entry.getValue());
            ClassNode after = node(transformed.get(entry.getKey()));
            for (var method : before.methods) {
                if (after.methods.stream().noneMatch(value -> value.name.equals(method.name) && value.desc.equals(method.desc))) {
                    RETIRED.add(before.name + "." + method.name + method.desc);
                }
            }
        }
        checkFailure();
        afterMixins = snapshots;
        outputs = transformed;
        state = "READY";
        System.out.println("BATCH_READY");
    }

    private static byte[] published(ClassLoader loader, Object[] arguments) {
        if (loader != LOADER) return null;
        String name = (String) arguments[0];
        byte[] bytes = (byte[]) arguments[1];
        if (CLASSES.contains(name)) {
            DEFINED.add(name);
            if (bytes == null || outputs == null) return null;
            checkFailure();
            if (!Arrays.equals(afterMixins.get(name), bytes)) throw fail("POST_MIXIN_INPUT_CHANGED " + name);
            VERIFIED_DEFINITION_INPUTS.add(name);
            return outputs.get(name).clone();
        }
        if (bytes == null || outputs == null || name.startsWith("love/crysh/mixinta/")
                || name.startsWith("love/crysh/exp/fabric/testmod/initialization/")) return null;
        ClassNode caller = node(bytes);
        for (var method : caller.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && RETIRED.contains(call.owner + "." + call.name + call.desc)) {
                    throw fail("LATE_OLD_DESCRIPTOR " + name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return null;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result, 0);
        return result;
    }

    private static IllegalStateException fail(String message) {
        IllegalStateException cause = new IllegalStateException(message + " " + java.util.List.of(Thread.currentThread().getStackTrace()));
        retain(cause);
        return new IllegalStateException("PROBE_FAILED", failure);
    }

    private static void retain(Throwable cause) {
        synchronized (MONITOR) {
            if (failure == null) failure = cause;
            state = "FAILED";
            MONITOR.notifyAll();
        }
    }

    private static void checkFailure() {
        if (failure != null) throw new IllegalStateException("PROBE_FAILED", failure);
    }
}
