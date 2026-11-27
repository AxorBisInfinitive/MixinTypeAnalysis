package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;

import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 一个 Knot ClassLoader 的显式分析域在首次定义前完成分析并整批发布。 */
public final class TypedOverwriteRuntime {
    private enum Phase { NEW, BOOTSTRAPPING, BOOTSTRAPPED, PREPARING, CONFIGURING, SNAPSHOTTING, ANALYZING, READY, INACTIVE, FAILED }

    private record Failure(Throwable cause, Phase phase, boolean published) { }

    private static final class SnapshotRequest {
        final String name;
        boolean consumed;

        SnapshotRequest(String name) { this.name = name; }
    }

    private static final ClassLoader LOADER = TypedOverwriteRuntime.class.getClassLoader();
    private static final Logger LOG = Logger.getLogger("love.crysh.mixinta.runtime");
    private static final Object MONITOR = new Object();
    private static final Set<String> PARTICIPANTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MIXINS = new LinkedHashSet<>();
    private static final Map<String, Class<?>> EXISTING = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> DEFINITION_INPUTS = new ConcurrentHashMap<>();
    private static final Set<String> DEPENDENCIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_SHAPES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SnapshotRequest> REQUEST = new ThreadLocal<>();
    private static final Map<String, byte[]> ORIGINALS = new LinkedHashMap<>();
    private static volatile Map<String, byte[]> preparedMixins = Map.of();
    private static volatile RuntimeSnapshot result;
    private static volatile Failure failure;
    private static volatile Phase phase = Phase.NEW;
    private static volatile PreparedOverwrites prepared;
    private static Map<String, byte[]> analysisInputs = Map.of();
    private static Thread owner;
    private static AgentBootstrap.Connection agent;
    private static long definitionVersion;
    private static volatile boolean inputReady;
    private static MixinEnvironment batchEnvironment;

    private TypedOverwriteRuntime() { }

    public static void bootstrap() {
        synchronized (MONITOR) {
            checkFailure();
            if (phase != Phase.NEW) return;
            owner = Thread.currentThread();
            phase = Phase.BOOTSTRAPPING;
        }
        try {
            RuntimeDeclarations declarations = RuntimeDeclarations.read();
            PARTICIPANTS.addAll(declarations.classes());
            MIXINS.addAll(declarations.mixins());
            if (PARTICIPANTS.isEmpty() && MIXINS.isEmpty()) {
                transition(Phase.INACTIVE);
                return;
            }
            agent = AgentBootstrap.connect(LOADER, TypedOverwriteRuntime::prepareMixin,
                    TypedOverwriteRuntime::publishedClass, TypedOverwriteRuntime::beforeTransform, TypedOverwriteRuntime::fail);
            transition(Phase.BOOTSTRAPPED);
            LOG.fine("MixinTA bootstrap connected");
        } catch (Throwable cause) {
            retain(cause);
            checkFailure();
        } finally {
            releaseOwner();
        }
    }

    public static void prepare() {
        bootstrap();
        if (phase == Phase.INACTIVE) return;
        if (prepared != null) {
            checkFailure();
            return;
        }
        advance(false);
    }

    public static void initialize() {
        bootstrap();
        if (phase != Phase.INACTIVE) advance(true);
    }

    private static void advance(boolean complete) {
        if (!acquireOwner(complete)) return;
        try {
            if (!inputsAvailable()) throw fail("INPUT_NOT_READY", "application classpath is locked");
            prepareDeclarations();
            if (complete) analyze();
        } catch (Throwable cause) {
            retain(cause);
            checkFailure();
        } finally {
            releaseOwner();
        }
    }

    private static boolean acquireOwner(boolean complete) {
        while (true) {
            synchronized (MONITOR) {
                checkFailure();
                if (result != null || (!complete && prepared != null)) return false;
                if (owner == Thread.currentThread()) throw fail("REENTRANT_INITIALIZATION", "complete=" + complete);
                if (owner == null) {
                    owner = Thread.currentThread();
                    return true;
                }
                if (phase != Phase.ANALYZING) throw fail("CONCURRENT_PREPARATION", "owner=" + owner.getName());
            }
            LOG.fine("MixinTA waiting for batch");
            synchronized (MONITOR) {
                try {
                    while (owner != null && result == null && failure == null) MONITOR.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    retain(interrupted);
                    checkFailure();
                }
                checkFailure();
            }
        }
    }

    private static byte[] beforeTransform(Object[] arguments, byte[] input) {
        if (arguments[0] != MixinEnvironment.getCurrentEnvironment().getActiveTransformer()) return null;
        String binaryName = (String) arguments[1];
        if (binaryName == null) return null;
        String name = binaryName.replace('.', '/');
        SnapshotRequest request = REQUEST.get();
        if (request != null && !request.consumed && request.name.equals(name)) {
            request.consumed = true;
            return null;
        }
        boolean participant = PARTICIPANTS.contains(name);
        synchronized (MONITOR) {
            if (failure != null) {
                if (participant) checkFailure();
                return null;
            }
            if (phase == Phase.BOOTSTRAPPING) {
                if (participant) throw fail("INPUT_NOT_READY", name);
                return null;
            }
            if (owner == Thread.currentThread() && result == null) {
                if (participant) throw fail("REENTRANT_DEFINITION", name);
                return null;
            }
            if (owner != null && !participant) return null;
        }
        if (result != null) checkConfiguration();
        if (result == null) {
            if (!inputsAvailable()) {
                if (participant) throw fail("INPUT_NOT_READY", name);
                return null;
            }
            // 在外层转换检查点完成批次，避免进入目标应用过程后嵌套调度。
            advance(true);
        }
        if (!participant) return null;
        checkFailure();
        RuntimeSnapshot snapshot = result;
        if (!Arrays.equals(snapshot.beforeMixins().get(name), input)) throw fail("INPUT_CHANGED", name);
        LOG.fine("MixinTA verified Mixin input " + name);
        return snapshot.afterMixins().get(name).clone();
    }

    private static Object prepareMixin(Object value) {
        ClassNode original = (ClassNode) value;
        if (!MIXINS.contains(original.name)) return value;
        checkFailure();
        byte[] replacement = preparedMixins.get(original.name);
        if (replacement == null) throw fail("DECLARATIONS_NOT_READY", original.name);
        return ClassFiles.read(replacement);
    }

    private static void prepareDeclarations() throws IOException {
        if (prepared != null) return;
        transition(Phase.PREPARING);
        for (String name : PARTICIPANTS.stream().sorted().toList()) ORIGINALS.put(name, readInput(name));
        Map<String, byte[]> mixins = new LinkedHashMap<>();
        for (String name : MIXINS) mixins.put(name, readInput(name));
        for (String name : TypedOverwriteAnalyzer.requiredRuleClasses(mixins)) {
            if (!ORIGINALS.containsKey(name) && !mixins.containsKey(name)) {
                PARTICIPANTS.add(name);
                ORIGINALS.put(name, readInput(name));
            }
        }
        prepared = new TypedOverwriteAnalyzer((ClassByteSource) TypedOverwriteRuntime::readDependency).prepare(ORIGINALS, mixins);
        preparedMixins = prepared.mixins();
        LOG.fine("MixinTA declarations prepared");
    }

    private static void analyze() throws Exception {
        transition(Phase.CONFIGURING);
        IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        if (transformer == null) throw fail("MIXIN_NOT_READY", "active transformer is missing");
        int remaining;
        do {
            int previous = Mixins.getUnvisitedCount();
            transformer.couldTransformClass(MixinEnvironment.getCurrentEnvironment(), TypedOverwriteRuntime.class.getName());
            remaining = Mixins.getUnvisitedCount();
            if (remaining > 0 && remaining >= previous) throw fail("MIXIN_CONFIGURATION_NOT_READY", "unvisited=" + remaining);
        } while (remaining > 0);
        batchEnvironment = MixinEnvironment.getCurrentEnvironment();
        checkFailure();
        transition(Phase.SNAPSHOTTING);
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
        for (var entry : ORIGINALS.entrySet()) {
            SnapshotRequest previous = REQUEST.get();
            REQUEST.set(new SnapshotRequest(entry.getKey()));
            try {
                String name = entry.getKey().replace('/', '.');
                snapshots.put(entry.getKey(), transformer.transformClassBytes(name, name, entry.getValue().clone()));
            } finally {
                if (previous == null) REQUEST.remove();
                else REQUEST.set(previous);
            }
        }
        checkConfiguration();
        analysisInputs = snapshots;
        refreshExisting();
        for (var entry : EXISTING.entrySet()) {
            byte[] bytes = snapshots.get(entry.getKey());
            if (bytes != null && ClassFiles.read(bytes).methods.stream().allMatch(method -> method.instructions.size() == 0)) {
                DefinedClassShape.verify(entry.getKey(), bytes, entry.getValue());
                VERIFIED_SHAPES.add(entry.getKey());
            }
        }
        transition(Phase.ANALYZING);
        var plan = prepared.analyze(snapshots);
        Map<String, byte[]> transformed = plan.apply();
        RuntimeSnapshot snapshot = new RuntimeSnapshot(ORIGINALS, snapshots, transformed,
                plan.modifiedClasses(), plan.retiredMethods());
        refreshExisting();
        for (String name : snapshot.modifiedClasses()) {
            if (EXISTING.containsKey(name)) throw fail("EARLY_MODIFIED_CLASS", name);
        }
        for (var entry : EXISTING.entrySet()) {
            byte[] bytes = snapshots.get(entry.getKey());
            if (bytes != null && (!VERIFIED_SHAPES.contains(entry.getKey())
                    || ClassFiles.read(bytes).methods.stream().anyMatch(method -> method.instructions.size() != 0))) {
                throw fail("UNVERIFIED_EARLY_CLASS", entry.getKey());
            }
        }
        publish(snapshot);
        LOG.fine("MixinTA batch ready");
    }

    private static void refreshExisting() throws ReflectiveOperationException {
        for (Class<?> type : agent.loadedClasses(LOADER)) {
            EXISTING.put(type.getName().replace('.', '/'), type);
        }
    }

    private static void publish(RuntimeSnapshot snapshot) {
        long checkedVersion = -1;
        while (true) {
            checkConfiguration();
            Map<String, byte[]> observed;
            synchronized (MONITOR) {
                checkFailure();
                if (checkedVersion == definitionVersion) {
                    result = snapshot;
                    phase = Phase.READY;
                    DEFINITION_INPUTS.keySet().removeIf(name -> !PARTICIPANTS.contains(name) && !DEPENDENCIES.contains(name));
                    return;
                }
                checkedVersion = definitionVersion;
                observed = new HashMap<>(DEFINITION_INPUTS);
            }
            for (var entry : observed.entrySet()) {
                if (!PARTICIPANTS.contains(entry.getKey())) {
                    RetiredMethodGuard.check(snapshot, entry.getKey(), entry.getValue(),
                            TypedOverwriteRuntime::readDependency, TypedOverwriteRuntime::fail);
                }
            }
        }
    }

    private static byte[] publishedClass(ClassLoader loader, Object[] arguments) {
        if (loader != LOADER || arguments[0] == null) return null;
        String name = (String) arguments[0];
        byte[] bytes = (byte[]) arguments[1];
        if (bytes == null) {
            if (arguments.length > 2 && arguments[2] instanceof Class<?> type && !type.isArray()) EXISTING.put(name, type);
            return null;
        }
        RuntimeSnapshot snapshot;
        synchronized (MONITOR) {
            snapshot = result;
            if (snapshot == null) {
                DEFINITION_INPUTS.put(name, bytes.clone());
                definitionVersion++;
                if (PARTICIPANTS.contains(name)) {
                    ClassNode candidate = ClassFiles.read(bytes);
                    boolean readOnlyShape = (candidate.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0
                            && candidate.methods.stream().allMatch(method -> method.instructions.size() == 0);
                    if (!readOnlyShape) throw fail("EARLY_DEFINITION", name);
                }
                return null;
            }
        }
        if (!PARTICIPANTS.contains(name)) {
            checkConfiguration();
            RetiredMethodGuard.check(snapshot, name, bytes, TypedOverwriteRuntime::readDependency, TypedOverwriteRuntime::fail);
            return null;
        }
        checkFailure();
        if (!Arrays.equals(snapshot.afterMixins().get(name), bytes)) throw fail("POST_MIXIN_INPUT_CHANGED", name);
        LOG.fine("MixinTA verified definition input " + name);
        return snapshot.outputs().get(name).clone();
    }

    private static byte[] readInput(String name) throws IOException {
        byte[] bytes = FabricLauncherBase.getLauncher().getClassByteArray(name.replace('/', '.'), true);
        if (bytes == null) throw new IOException("Missing registered class " + name);
        return bytes;
    }

    private static void checkConfiguration() {
        if (batchEnvironment != null && (batchEnvironment != MixinEnvironment.getCurrentEnvironment()
                || Mixins.getUnvisitedCount() != 0)) {
            throw fail("MIXIN_CONFIGURATION_CHANGED", "batch environment or registered configurations changed");
        }
    }

    private static byte[] readDependency(String name) throws IOException {
        RuntimeSnapshot published = result;
        byte[] bytes = published == null ? analysisInputs.get(name) : published.outputs().get(name);
        if (bytes != null) return bytes.clone();
        bytes = ORIGINALS.get(name);
        if (bytes != null) return bytes.clone();
        DEPENDENCIES.add(name);
        bytes = DEFINITION_INPUTS.get(name);
        if (bytes != null) return bytes.clone();
        bytes = name.startsWith("java/") || name.startsWith("jdk/")
                ? ClassByteSource.from(LOADER).read(name) : readInput(name);
        Class<?> defined = EXISTING.get(name);
        if (defined != null && bytes != null && !VERIFIED_SHAPES.contains(name)) {
            if (phase == Phase.ANALYZING || phase == Phase.READY) throw fail("UNVERIFIED_LOADED_DEPENDENCY", name);
            DefinedClassShape.verify(name, bytes, defined);
            VERIFIED_SHAPES.add(name);
        }
        return bytes;
    }

    private static boolean inputsAvailable() {
        if (inputReady) return true;
        try {
            FabricLauncherBase.getLauncher().getClassByteArray(FabricLauncherBase.getLauncher().getEntrypoint(), true);
            inputReady = true;
            return true;
        } catch (IllegalStateException locked) {
            if ("early getClassByteArray access".equals(locked.getMessage())) return false;
            throw locked;
        } catch (IOException missing) {
            throw fail("INPUT_UNAVAILABLE", missing.getMessage());
        }
    }

    private static void transition(Phase next) {
        synchronized (MONITOR) {
            checkFailure();
            phase = next;
        }
        LOG.fine("MixinTA phase=" + next);
    }

    private static void releaseOwner() {
        synchronized (MONITOR) {
            owner = null;
            if (failure == null && result == null && phase != Phase.INACTIVE) phase = Phase.BOOTSTRAPPED;
            MONITOR.notifyAll();
        }
    }

    private static IllegalStateException fail(String code, String detail) {
        retain(new IllegalStateException(code + " " + detail + " phase=" + phase));
        return failureException();
    }

    private static void retain(Throwable cause) {
        if (cause instanceof InvocationTargetException wrapped && wrapped.getCause() != null) cause = wrapped.getCause();
        boolean first;
        synchronized (MONITOR) {
            first = failure == null;
            if (first) failure = new Failure(cause, phase, result != null);
            phase = Phase.FAILED;
            MONITOR.notifyAll();
        }
        if (first) LOG.fine("MixinTA failure retained");
    }

    private static IllegalStateException failureException() {
        Failure saved = failure;
        return new IllegalStateException("MixinTA initialization failed phase=" + saved.phase()
                + " outputsPublished=" + saved.published(), saved.cause());
    }

    private static void checkFailure() {
        if (failure != null) throw failureException();
    }
}
