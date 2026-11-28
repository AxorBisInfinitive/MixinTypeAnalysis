package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.AnalysisMetrics;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 从当前 Knot 的配置与来源建立分析批次，在实际修改类定义前整批发布。 */
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
    private static final MixinDiscovery DISCOVERY = new MixinDiscovery();
    private static final DeclarationProtection PROTECTION = new DeclarationProtection();
    private static final Map<String, Class<?>> EXISTING = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> DEFINITION_INPUTS = new ConcurrentHashMap<>();
    private static final Set<String> DEPENDENCIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_SHAPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> SUPPORT_CLASSES = ConcurrentHashMap.newKeySet();
    private static final Set<String> FROZEN_INPUTS = ConcurrentHashMap.newKeySet();
    private static final java.net.URL SUPPORT_SOURCE = TypedOverwriteRuntime.class.getProtectionDomain().getCodeSource().getLocation();
    private static final ThreadLocal<SnapshotRequest> REQUEST = new ThreadLocal<>();
    private static final Map<String, byte[]> ORIGINALS = new LinkedHashMap<>();
    private static final Map<String, byte[]> RAW_CLASSES = new LinkedHashMap<>();
    private static KnotClassSources classSources;
    private static KnotClassSources.State sourceState;
    private static ClassSourceIndex sourceIndex;
    private static BatchPremises premises;
    private static Object batchTransformer;
    private static boolean configuring;
    private static volatile RuntimeSnapshot result;
    private static volatile Failure failure;
    private static volatile Phase phase = Phase.NEW;
    private static volatile PreparedOverwrites prepared;
    private static boolean representationBatch;
    private static Map<String, byte[]> analysisInputs = Map.of();
    private static Thread owner;
    private static AgentBootstrap.Connection agent;
    private static long definitionVersion;
    private static volatile boolean inputReady;
    private static MixinEnvironment batchEnvironment;
    private static long discoveryNanos;
    private static long declarationNanos;
    private static long snapshotNanos;
    private static long analysisNanos;
    private static long batchStarted;
    private static boolean completionReported;
    private static boolean failureReported;
    private static boolean inputMetricsRecorded;
    private static int activeChecks;
    private static final AnalysisMetrics METRICS = new AnalysisMetrics();

    private TypedOverwriteRuntime() { }

    public static void bootstrap() {
        synchronized (MONITOR) {
            checkFailure();
            if (phase != Phase.NEW) return;
            owner = Thread.currentThread();
            batchStarted = System.nanoTime();
            phase = Phase.BOOTSTRAPPING;
        }
        try {
            agent = AgentBootstrap.connect(LOADER, TypedOverwriteRuntime::prepareMixin,
                    TypedOverwriteRuntime::publishedClass, TypedOverwriteRuntime::beforeTransform, TypedOverwriteRuntime::fail);
            classSources = new KnotClassSources(LOADER);
            transition(Phase.BOOTSTRAPPED);
            logFine("MixinTA bootstrap connected");
        } catch (Throwable cause) {
            retain(cause);
            checkFailure();
        } finally {
            METRICS.add(AnalysisMetrics.Stage.BOOTSTRAP, System.nanoTime() - batchStarted);
            releaseOwner();
        }
    }

    public static void prepare() {
        bootstrap();
        if (phase == Phase.INACTIVE) {
            checkConfiguration();
            return;
        }
        synchronized (MONITOR) {
            checkFailure();
            if (owner == Thread.currentThread() && configuring) return;
        }
        if (prepared != null || phase == Phase.READY) {
            checkFailure();
            if (phase == Phase.READY) checkConfiguration();
            return;
        }
        advance(false);
    }

    public static void initialize() {
        bootstrap();
        if (phase == Phase.READY || phase == Phase.INACTIVE) checkConfiguration();
        if (phase != Phase.INACTIVE) advance(true);
    }

    private static void advance(boolean complete) {
        if (!acquireOwner(complete)) return;
        try {
            if (!inputsAvailable()) throw fail("INPUT_NOT_READY", "application classpath is locked");
            prepareDeclarations();
            if (complete && phase != Phase.INACTIVE && phase != Phase.READY) analyze();
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
                if (phase == Phase.READY || phase == Phase.INACTIVE || (!complete && prepared != null)) return false;
                if (owner == Thread.currentThread()) throw fail("REENTRANT_INITIALIZATION", "complete=" + complete);
                if (owner == null) {
                    owner = Thread.currentThread();
                    return true;
                }
                if (phase != Phase.ANALYZING) throw fail("CONCURRENT_PREPARATION", "owner=" + owner.getName());
            }
            logFine("MixinTA waiting for batch");
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
        if (phase == Phase.INACTIVE) {
            checkConfiguration();
            return checkedReturn(null);
        }
        SnapshotRequest request = REQUEST.get();
        if (request != null && !request.consumed && request.name.equals(name)) {
            request.consumed = true;
            return null;
        }
        boolean participant = protectsDefinition(name, input);
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
            if (owner != null && owner != Thread.currentThread()
                    && (phase == Phase.CONFIGURING || phase == Phase.SNAPSHOTTING)) {
                // plugin 等待的线程不能继续争用 owner 可能持有的 Mixin monitor。
                throw fail("CONCURRENT_PREPARATION", name + " owner=" + owner.getName());
            }
            if (owner != null && !participant) return null;
        }
        if (result != null || phase == Phase.READY) checkConfiguration();
        if (phase == Phase.READY && result == null) return checkedReturn(null);
        if (result == null) {
            if (!inputsAvailable()) {
                if (participant) throw fail("INPUT_NOT_READY", name);
                return null;
            }
            // 在外层转换检查点完成批次，避免进入目标应用过程后嵌套调度。
            advance(true);
        }
        if (phase == Phase.INACTIVE || phase == Phase.READY && result == null) return checkedReturn(null);
        participant = PARTICIPANTS.contains(name);
        if (!participant) return null;
        checkFailure();
        RuntimeSnapshot snapshot = result;
        checkSource(name);
        if (!Arrays.equals(snapshot.beforeMixin(name), input)) throw fail("INPUT_CHANGED", name);
        logFine("MixinTA verified Mixin input " + name);
        return checkedReturn(snapshot.afterMixin(name));
    }

    private static Object prepareMixin(Object value) {
        Object[] event = (Object[]) value;
        try {
            checkFailure();
            if (owner != null && owner != Thread.currentThread()) {
                throw fail("CONCURRENT_PREPARATION", "Mixin preparation on " + Thread.currentThread().getName());
            }
            if (event[0].equals("static")) {
                Object context = event[1];
                var getMixin = context.getClass().getMethod("getMixin");
                var getTarget = context.getClass().getMethod("getTargetClassRef");
                getMixin.setAccessible(true);
                getTarget.setAccessible(true);
                return DISCOVERY.allowsStatic(getMixin.invoke(context), (String) getTarget.invoke(context),
                        (org.objectweb.asm.tree.MethodNode) event[2]);
            }
            if (event[0].equals("validation") || event[0].equals("application")) {
                DISCOVERY.project(event[1], (ClassNode) event[2], TypedOverwriteRuntime::readCandidate,
                        event[0].equals("validation"));
                return event[2];
            }
            if (event[0].equals("configuration")) {
                if (batchEnvironment != null && !DISCOVERY.knows(event[1])) {
                    throw fail("MIXIN_CONFIGURATION_CHANGED", "new configuration after declaration binding");
                }
                for (String name : DISCOVERY.selected(event[1])) {
                    try {
                        byte[] bytes = readInput(name);
                        if (bytes != null) protectDeclaration(ClassFiles.read(bytes));
                    } catch (IOException | RuntimeException unavailable) {
                        logFine("MixinTA candidate hint unavailable " + name + ": " + unavailable);
                    }
                }
                return null;
            }
            ClassNode original = (ClassNode) event[2];
            protectDeclaration(original);
            return DISCOVERY.prepare(event[1], original);
        } catch (Throwable cause) {
            retain(cause);
            throw failureException();
        }
    }

    private static void prepareDeclarations() throws Exception {
        if (prepared != null) return;
        long started = System.nanoTime();
        try {
            discoverSources();
            transition(Phase.CONFIGURING);
            IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer == null) throw fail("MIXIN_NOT_READY", "active transformer is missing");
            configuring = true;
            try {
                int remaining;
                do {
                    Set<?> previous = Set.copyOf(Mixins.getConfigs());
                    transformer.couldTransformClass(MixinEnvironment.getCurrentEnvironment(), TypedOverwriteRuntime.class.getName());
                    remaining = Mixins.getUnvisitedCount();
                    if (remaining > 0 && previous.equals(Set.copyOf(Mixins.getConfigs()))) {
                        throw fail("MIXIN_CONFIGURATION_NOT_READY", "unvisited=" + remaining);
                    }
                } while (remaining > 0);
            } finally {
                configuring = false;
            }
            checkFailure();
            discoverSources();
            batchEnvironment = MixinEnvironment.getCurrentEnvironment();
            batchTransformer = transformer;
            var configurations = DISCOVERY.configurationSnapshot(transformer);
            if (!configurations.pending().isEmpty()) throw fail("MIXIN_CONFIGURATION_NOT_READY", "processor has pending configurations");
            var bindings = DISCOVERY.bind(configurations);
            premises = new BatchPremises(batchEnvironment, batchTransformer, sourceState, configurations, sourceIndex.sources());
            PARTICIPANTS.clear();
            PROTECTION.clear();
            if (bindings.mixins().isEmpty()) {
                DISCOVERY.releaseCandidates();
                RAW_CLASSES.clear();
                sourceIndex.releaseInputs();
                transition(Phase.INACTIVE);
                return;
            }
            for (byte[] mixin : bindings.mixins().values()) protectDeclaration(ClassFiles.read(mixin));
            transition(Phase.PREPARING);
            for (List<String> targets : bindings.targets().values()) {
                for (String name : targets) {
                    ClassSourceIndex.Input targetInput = sourceIndex.inspect(name, net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType());
                    if (targetInput != null && targetInput.failed()) {
                        throw new love.crysh.mixinta.diagnostic.TypedOverwriteException(List.of(targetInput.diagnostic()));
                    }
                    if (targetInput == null || targetInput.status() != ClassSourceIndex.Status.AVAILABLE) {
                        throw fail("TARGET_OUTSIDE_KNOT_SOURCES", name + " status=" + (targetInput == null ? "MISSING" : targetInput.status()));
                    }
                    try {
                        logFine("MixinTA target input ready " + name);
                        ORIGINALS.put(name, readIndexedInput(name));
                    } catch (IOException | RuntimeException unavailable) {
                        sourceIndex.failed(name, unavailable);
                        throw new love.crysh.mixinta.diagnostic.TypedOverwriteException(List.of(sourceIndex.inspect(name,
                                net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()).diagnostic()));
                    }
                    PARTICIPANTS.add(name);
                }
            }
            for (String name : TypedOverwriteAnalyzer.requiredRuleClasses(bindings.mixins())) {
                if (!bindings.mixins().containsKey(name)) {
                    byte[] bytes = readInput(name);
                    if (bytes != null && MixinDiscovery.isMixin(ClassFiles.read(bytes))) {
                        throw fail("RULE_MIXIN_NOT_ACTIVE", name);
                    }
                    if (bytes != null) ORIGINALS.putIfAbsent(name, bytes);
                }
            }
            prepared = new TypedOverwriteAnalyzer((ClassByteSource) TypedOverwriteRuntime::readDependency)
                    .prepareRuntime(ORIGINALS, bindings.mixins(), bindings.targets());
            DISCOVERY.seal(prepared.mixins(), bindings);
            representationBatch = prepared.hasRepresentationMigrations();
            logFine("MixinTA declarations prepared");
            if (!prepared.hasMigrations()) {
                checkConfiguration();
                checkSources();
                DISCOVERY.releaseCandidates();
                RAW_CLASSES.clear();
                ORIGINALS.clear();
                sourceIndex.releaseInputs();
                prepared = null;
                transition(Phase.READY);
            }
        } finally {
            declarationNanos += System.nanoTime() - started;
        }
    }

    private static void analyze() throws Exception {
        checkFailure();
        IMixinTransformer transformer = (IMixinTransformer) batchTransformer;
        transition(Phase.SNAPSHOTTING);
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            captureSnapshots(transformer, snapshots);
        } finally {
            snapshotNanos += System.nanoTime() - started;
            METRICS.set(AnalysisMetrics.Count.SNAPSHOT_CLASSES, snapshots.size());
        }
        transition(Phase.ANALYZING);
        started = System.nanoTime();
        try {
            var plan = prepared.analyze(snapshots, METRICS);
            long outputStarted = System.nanoTime();
            Map<String, byte[]> transformed;
            try {
                transformed = plan.applyChanges();
            } finally {
                METRICS.add(AnalysisMetrics.Stage.OUTPUT, System.nanoTime() - outputStarted);
            }
            RuntimeSnapshot snapshot = new RuntimeSnapshot(ORIGINALS, snapshots, transformed,
                    plan.modifiedClasses(), plan.retiredMethods(), sourceIndex.sources());
            refreshExisting();
            for (String name : snapshot.modifiedClasses()) {
                if (EXISTING.containsKey(name)) throw fail("EARLY_MODIFIED_CLASS", name);
            }
            for (var entry : EXISTING.entrySet()) {
                byte[] bytes = snapshots.get(entry.getKey());
                if (bytes != null && !VERIFIED_SHAPES.contains(entry.getKey())) {
                    throw fail("UNVERIFIED_EARLY_CLASS", entry.getKey());
                }
            }
            METRICS.set(AnalysisMetrics.Count.MODIFIED_CLASSES, snapshot.modifiedClasses().size());
            long publishStarted = System.nanoTime();
            try {
                publish(snapshot);
            } finally {
                METRICS.add(AnalysisMetrics.Stage.PUBLISH, System.nanoTime() - publishStarted);
            }
        } finally {
            analysisNanos += System.nanoTime() - started;
        }
        logFine("MixinTA batch ready");
    }

    private static void captureSnapshots(IMixinTransformer transformer, Map<String, byte[]> snapshots) throws Exception {
        inspectSources();
        refreshExisting();
        sourceIndex.verifyIncluded(TypedOverwriteRuntime::analysisCandidate);
        for (var entry : RAW_CLASSES.entrySet()) {
            if (!analysisCandidate(entry.getKey())) continue;
            ClassNode node;
            try {
                METRICS.count(AnalysisMetrics.Count.SNAPSHOT_PARSES, 1);
                node = ClassFiles.read(entry.getValue());
            } catch (RuntimeException malformed) {
                sourceIndex.parsingFailed(entry.getKey(), malformed);
                continue;
            }
            if (MixinDiscovery.isMixin(node) && !DISCOVERY.loadable(entry.getKey())) continue;
            try {
                ORIGINALS.putIfAbsent(entry.getKey(), readIndexedInput(entry.getKey()));
            } catch (IOException | RuntimeException failure) {
                sourceIndex.failed(entry.getKey(), failure);
            }
        }
        sourceIndex.verifyIncluded(TypedOverwriteRuntime::analysisCandidate);
        refreshExisting();
        for (var entry : ORIGINALS.entrySet()) {
            SnapshotRequest previous = REQUEST.get();
            REQUEST.set(new SnapshotRequest(entry.getKey()));
            try {
                String name = entry.getKey().replace('/', '.');
                byte[] observed = DEFINITION_INPUTS.get(entry.getKey());
                Class<?> existing = EXISTING.get(entry.getKey());
                if (existing != null && observed == null) {
                    observed = agent.captureDefinition(existing);
                    DefinedClassShape.verify(entry.getKey(), observed, existing);
                    DEFINITION_INPUTS.put(entry.getKey(), observed.clone());
                }
                if (EXISTING.containsKey(entry.getKey()) && observed != null) {
                    snapshots.put(entry.getKey(), observed.clone());
                    VERIFIED_SHAPES.add(entry.getKey());
                } else {
                    long mixinStarted = System.nanoTime();
                    long inputBefore = METRICS.nanos().getOrDefault(AnalysisMetrics.Stage.PREPROCESS, 0L);
                    try {
                        snapshots.put(entry.getKey(), transformer.transformClassBytes(name, name, entry.getValue().clone()));
                    } finally {
                        METRICS.add(AnalysisMetrics.Stage.MIXIN, System.nanoTime() - mixinStarted
                                - (METRICS.nanos().getOrDefault(AnalysisMetrics.Stage.PREPROCESS, 0L) - inputBefore));
                    }
                }
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
            byte[] observed = DEFINITION_INPUTS.get(entry.getKey());
            if (bytes != null && observed != null && Arrays.equals(bytes, observed)) {
                VERIFIED_SHAPES.add(entry.getKey());
            }
            if (bytes != null && ClassFiles.read(bytes).methods.stream().allMatch(method -> method.instructions.size() == 0)) {
                DefinedClassShape.verify(entry.getKey(), bytes, entry.getValue());
                VERIFIED_SHAPES.add(entry.getKey());
            }
        }
        for (var entry : DEFINITION_INPUTS.entrySet()) {
            if (!snapshots.containsKey(entry.getKey()) && EXISTING.containsKey(entry.getKey())
                    && !SUPPORT_CLASSES.contains(entry.getKey())) {
                // 快照期间已经完成定义的生成类以观察到的方法体参与只读分析。
                ORIGINALS.put(entry.getKey(), entry.getValue().clone());
                snapshots.put(entry.getKey(), entry.getValue().clone());
                VERIFIED_SHAPES.add(entry.getKey());
            }
        }
        PARTICIPANTS.addAll(ORIGINALS.keySet());
    }

    private static void discoverSources() throws Exception {
        KnotClassSources.State current = classSources.state();
        long started = System.nanoTime();
        try {
            RAW_CLASSES.clear();
            ClassSourceIndex next = classSources.index(current);
            if (sourceIndex != null) METRICS.add(AnalysisMetrics.Stage.RAW_INPUT, sourceIndex.inspectionNanos());
            sourceIndex = next;
            sourceState = current;
        } finally {
            long elapsed = System.nanoTime() - started;
            discoveryNanos += elapsed;
            METRICS.add(AnalysisMetrics.Stage.SOURCE, elapsed);
        }
        logFine("MixinTA discovered classes=" + sourceIndex.sources().size());
    }

    private static void inspectSources() {
        for (String name : sourceIndex.sources().keySet().stream().sorted().toList()) {
            checkSource(name, true);
            var input = sourceIndex.inspect(name, net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType());
            if (input.status() == ClassSourceIndex.Status.AVAILABLE) RAW_CLASSES.put(name, input.bytes());
        }
    }

    private static byte[] readCandidate(String name) throws IOException {
        return readInput(name);
    }

    private static boolean analysisCandidate(String name) {
        return !DISCOVERY.ownsPackage(name) || DISCOVERY.loadable(name) || EXISTING.containsKey(name);
    }

    private static void protectDeclaration(ClassNode node) {
        PROTECTION.register(node);
    }

    private static boolean protectsDefinition(String name, byte[] bytes) {
        if (PARTICIPANTS.contains(name)) return true;
        if (PROTECTION.protects(name, bytes)) {
            PARTICIPANTS.add(name);
            return true;
        }
        return false;
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
            checkSources();
            Map<String, byte[]> observed;
            synchronized (MONITOR) {
                checkFailure();
                if (checkedVersion == definitionVersion) {
                    result = snapshot;
                    phase = Phase.READY;
                    DEFINITION_INPUTS.keySet().removeIf(name -> PARTICIPANTS.contains(name) || !DEPENDENCIES.contains(name));
                    analysisInputs = Map.of();
                    ORIGINALS.clear();
                    RAW_CLASSES.clear();
                    DISCOVERY.releaseCandidates();
                    PROTECTION.clear();
                    sourceIndex.releaseInputs();
                    prepared = null;
                    return;
                }
                checkedVersion = definitionVersion;
                observed = new HashMap<>(DEFINITION_INPUTS);
            }
            for (var entry : observed.entrySet()) {
                if (!PARTICIPANTS.contains(entry.getKey())) {
                    checkBoundary(snapshot, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static byte[] publishedClass(ClassLoader loader, Object[] arguments) {
        if (loader != LOADER || arguments[0] == null) return null;
        String name = (String) arguments[0];
        if (phase == Phase.INACTIVE) {
            checkConfiguration();
            return checkedReturn(null);
        }
        byte[] bytes = (byte[]) arguments[1];
        if (bytes == null) {
            if (arguments.length > 2 && arguments[2] instanceof Class<?> type && !type.isArray()) {
                EXISTING.put(name, type);
                recordSupport(name, type.getProtectionDomain());
            }
            return checkedReturn(null);
        }
        if (arguments.length > 2 && arguments[2] instanceof java.security.ProtectionDomain domain) recordSupport(name, domain);
        checkFailure();
        if (phase == Phase.READY && result == null) {
            checkConfiguration();
            checkDefinition(name, loader,
                    arguments.length > 2 && arguments[2] instanceof java.security.ProtectionDomain domain ? domain : null);
            return checkedReturn(null);
        }
        RuntimeSnapshot snapshot;
        synchronized (MONITOR) {
            checkFailure();
            snapshot = result;
            if (snapshot == null) {
                DEFINITION_INPUTS.put(name, bytes.clone());
                definitionVersion++;
                if (protectsDefinition(name, bytes)) {
                    ClassNode candidate = ClassFiles.read(bytes);
                    boolean readOnlyShape = (candidate.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0
                            && candidate.methods.stream().allMatch(method -> method.instructions.size() == 0);
                    if (!readOnlyShape) throw fail("EARLY_DEFINITION", name);
                }
                return null;
            }
        }
        checkConfiguration();
        checkDefinition(name, loader,
                arguments.length > 2 && arguments[2] instanceof java.security.ProtectionDomain domain ? domain : null);
        if (!PARTICIPANTS.contains(name)) {
            checkBoundary(snapshot, name, bytes);
            return checkedReturn(null);
        }
        checkFailure();
        if (!Arrays.equals(snapshot.afterMixin(name), bytes)) throw fail("POST_MIXIN_INPUT_CHANGED", name);
        logFine("MixinTA verified definition input " + name);
        return checkedReturn(snapshot.outputOf(name));
    }

    private static void recordSupport(String name, java.security.ProtectionDomain domain) {
        if (domain != null && domain.getCodeSource() != null
                && SUPPORT_SOURCE.equals(domain.getCodeSource().getLocation())) SUPPORT_CLASSES.add(name);
    }

    private static void checkDefinition(String name, ClassLoader loader, java.security.ProtectionDomain domain) {
        if (!FROZEN_INPUTS.contains(name)) return;
        beginCheck();
        long started = System.nanoTime();
        try {
            verifySource(name, false);
            premises.verifyDefinition(name, loader, domain, TypedOverwriteRuntime::fail);
            checkedReturn(null);
        } finally {
            METRICS.add(AnalysisMetrics.Stage.PREMISES, System.nanoTime() - started);
            endCheck();
        }
    }

    private static void checkBoundary(RuntimeSnapshot snapshot, String name, byte[] bytes) {
        if (SUPPORT_CLASSES.contains(name)) return;
        RetiredMethodGuard.check(snapshot, name, bytes, TypedOverwriteRuntime::readDependency, TypedOverwriteRuntime::fail);
        if (representationBatch) {
            throw fail("LATE_REPRESENTATION_BOUNDARY", name + " has no verified representation analysis input");
        }
    }

    private static byte[] readInput(String name) throws IOException {
        beginCheck();
        long started = System.nanoTime();
        try {
            boolean frozen = premises != null && premises.inputSources().containsKey(name);
            if (frozen) {
                FROZEN_INPUTS.add(name);
                checkSource(name, true);
            }
            byte[] bytes = FabricLauncherBase.getLauncher().getClassByteArray(name.replace('/', '.'), true);
            if (frozen && bytes != null) checkSource(name);
            return bytes;
        }
        finally {
            METRICS.add(AnalysisMetrics.Stage.PREPROCESS, System.nanoTime() - started);
            endCheck();
        }
    }

    private static ClassSourceIndex.Source currentSource(String name) {
        try {
            return classSources.resolve(name, sourceState);
        } catch (ReflectiveOperationException | net.fabricmc.loader.impl.util.UrlConversionException | RuntimeException unavailable) {
            throw fail(new IllegalStateException("CLASS_SOURCE_CHANGED " + name + " source resolution failed", unavailable));
        }
    }

    private static void checkSource(String name, boolean allowMissing) {
        if (premises == null || !premises.inputSources().containsKey(name)) return;
        beginCheck();
        long started = System.nanoTime();
        try {
            verifySource(name, allowMissing);
            checkedReturn(null);
        } finally {
            METRICS.add(AnalysisMetrics.Stage.PREMISES, System.nanoTime() - started);
            endCheck();
        }
    }

    private static void verifySource(String name, boolean allowMissing) {
        METRICS.count(AnalysisMetrics.Count.SOURCE_CHECKS, 1);
        ClassSourceIndex.Source current = currentSource(name);
        if (allowMissing && current != null && current.status() == ClassSourceIndex.Status.MISSING) return;
        premises.verifySource(name, current, TypedOverwriteRuntime::fail);
    }

    private static void checkSource(String name) {
        checkSource(name, false);
    }

    private static void checkSources() {
        for (String name : FROZEN_INPUTS.stream().sorted().toList()) {
            Class<?> defined = EXISTING.get(name);
            if (defined == null) checkSource(name);
            else checkDefinition(name, defined.getClassLoader(), defined.getProtectionDomain());
        }
    }

    private static byte[] readIndexedInput(String name) throws IOException {
        byte[] bytes = readInput(name);
        if (bytes == null) throw new IOException("Preprocessed class resource is missing for " + name);
        return bytes;
    }

    private static void checkConfiguration() {
        beginCheck();
        long started = System.nanoTime();
        try {
            verifyConfiguration();
            checkedReturn(null);
        }
        finally {
            METRICS.add(AnalysisMetrics.Stage.PREMISES, System.nanoTime() - started);
            endCheck();
        }
    }

    private static void verifyConfiguration() {
        if (batchEnvironment != null && (batchEnvironment != MixinEnvironment.getCurrentEnvironment()
                || batchTransformer != MixinEnvironment.getCurrentEnvironment().getActiveTransformer()
                || Mixins.getUnvisitedCount() != 0)) {
            throw fail("MIXIN_CONFIGURATION_CHANGED", "batch environment or registered configurations changed");
        }
        if (batchEnvironment != null) {
            KnotClassSources.State currentSources;
            MixinDiscovery.ConfigurationSnapshot currentConfigurations;
            try {
                currentSources = classSources.state();
                currentConfigurations = DISCOVERY.configurationSnapshot(batchTransformer);
            } catch (ReflectiveOperationException | RuntimeException cause) {
                retain(cause);
                throw failureException();
            }
            premises.verify(MixinEnvironment.getCurrentEnvironment(), MixinEnvironment.getCurrentEnvironment().getActiveTransformer(),
                    currentSources, currentConfigurations, TypedOverwriteRuntime::fail);
        }
    }

    private static byte[] readDependency(String name) throws IOException {
        if (FROZEN_INPUTS.contains(name)) checkSource(name);
        RuntimeSnapshot published = result;
        byte[] bytes = published == null ? analysisInputs.get(name) : published.outputOf(name);
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
            // 支持类已在 bootstrap 预加载，其只读结构仍可供轻量层次索引核对。
            if ((phase == Phase.ANALYZING || phase == Phase.READY) && !SUPPORT_CLASSES.contains(name)) {
                throw fail("UNVERIFIED_LOADED_DEPENDENCY", name);
            }
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
        logFine("MixinTA phase=" + next);
    }

    private static void releaseOwner() {
        synchronized (MONITOR) {
            owner = null;
            if (failure == null && result == null && phase != Phase.INACTIVE && phase != Phase.READY) phase = Phase.BOOTSTRAPPED;
            MONITOR.notifyAll();
        }
        reportMetrics();
    }

    private static IllegalStateException fail(String code, String detail) {
        return fail(new IllegalStateException(code + " " + detail + " phase=" + phase));
    }

    private static IllegalStateException fail(IllegalStateException cause) {
        retain(cause);
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
        if (first) logFine("MixinTA failure retained");
        reportMetrics();
    }

    private static void beginCheck() {
        synchronized (MONITOR) {
            activeChecks++;
        }
    }

    private static void endCheck() {
        boolean settled;
        synchronized (MONITOR) {
            settled = --activeChecks == 0;
        }
        if (settled) reportMetrics();
    }

    private static void reportMetrics() {
        String message;
        synchronized (MONITOR) {
            // 检查可嵌套且可跨线程执行，最后一个 finally 结算后才能拍摄终态快照。
            if (owner != null || activeChecks != 0) return;
            Failure stopped = failure;
            if (stopped != null) {
                if (failureReported) return;
                failureReported = true;
            } else {
                if (phase != Phase.READY && phase != Phase.INACTIVE || completionReported) return;
                completionReported = true;
            }
            if (sourceIndex != null) {
                if (!inputMetricsRecorded) {
                    METRICS.add(AnalysisMetrics.Stage.RAW_INPUT, sourceIndex.inspectionNanos());
                    inputMetricsRecorded = true;
                }
                METRICS.set(AnalysisMetrics.Count.CANDIDATE_CLASSES, sourceIndex.sources().size());
                METRICS.set(AnalysisMetrics.Count.INSPECTED_CLASSES, sourceIndex.inspectedCount());
                METRICS.set(AnalysisMetrics.Count.UNAVAILABLE_CLASSES, sourceIndex.unavailableCount());
                METRICS.set(AnalysisMetrics.Count.FAILED_INPUTS, sourceIndex.failedCount());
            }
            METRICS.set(AnalysisMetrics.Count.PROTECTION_CHECKS, PROTECTION.inspections());
            long diagnostics = stopped != null && stopped.cause() instanceof love.crysh.mixinta.diagnostic.TypedOverwriteException typed
                    ? typed.diagnostics().size() : 0;
            message = "MixinTA metrics classes=" + METRICS.count(AnalysisMetrics.Count.SNAPSHOT_CLASSES)
                    + " modified=" + METRICS.count(AnalysisMetrics.Count.MODIFIED_CLASSES)
                    + " status=" + phase + " phase=" + (stopped == null ? phase : stopped.phase())
                    + " outputsPublished=" + (stopped == null ? result != null : stopped.published())
                    + " failureType=" + (stopped == null ? "none" : stopped.cause().getClass().getName())
                    + " diagnostics=" + diagnostics
                    + " discoveryMs=" + discoveryNanos / 1_000_000 + " declarationMs=" + declarationNanos / 1_000_000
                    + " snapshotMs=" + snapshotNanos / 1_000_000 + " analysisMs=" + analysisNanos / 1_000_000
                    + " totalMs=" + (System.nanoTime() - batchStarted) / 1_000_000 + METRICS.format();
        }
        logFine(message);
    }

    private static void logFine(String message) {
        try {
            LOG.fine(message);
        } catch (Throwable loggingFailure) {
            // 日志设施的异常不能改变生命周期、分析结果或类定义。
        }
    }

    private static IllegalStateException failureException() {
        Failure saved = failure;
        return new IllegalStateException("MixinTA initialization failed phase=" + saved.phase()
                + " outputsPublished=" + saved.published(), saved.cause());
    }

    private static void checkFailure() {
        if (failure != null) throw failureException();
    }

    private static byte[] checkedReturn(byte[] bytes) {
        synchronized (MONITOR) {
            checkFailure();
            return bytes;
        }
    }
}
