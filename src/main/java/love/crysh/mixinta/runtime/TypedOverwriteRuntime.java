package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.MethodReferences;

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
    private static final Map<String, Class<?>> EXISTING = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> DEFINITION_INPUTS = new ConcurrentHashMap<>();
    private static final Set<String> DEPENDENCIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> VERIFIED_SHAPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> SUPPORT_CLASSES = ConcurrentHashMap.newKeySet();
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
    private static long batchStarted;
    private static final AnalysisMetrics METRICS = new AnalysisMetrics();

    private TypedOverwriteRuntime() { }

    public static void bootstrap() {
        synchronized (MONITOR) {
            checkFailure();
            if (phase != Phase.NEW) return;
            owner = Thread.currentThread();
            phase = Phase.BOOTSTRAPPING;
        }
        try {
            agent = AgentBootstrap.connect(LOADER, TypedOverwriteRuntime::prepareMixin,
                    TypedOverwriteRuntime::publishedClass, TypedOverwriteRuntime::beforeTransform, TypedOverwriteRuntime::fail);
            classSources = new KnotClassSources(LOADER);
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
        if (phase == Phase.READY) checkConfiguration();
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
        if (phase == Phase.INACTIVE) {
            checkConfiguration();
            return null;
        }
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
            if (owner != null && owner != Thread.currentThread()
                    && (phase == Phase.CONFIGURING || phase == Phase.SNAPSHOTTING)) {
                // plugin 等待的线程不能继续争用 owner 可能持有的 Mixin monitor。
                throw fail("CONCURRENT_PREPARATION", name + " owner=" + owner.getName());
            }
            if (owner != null && !participant) return null;
        }
        if (result != null || phase == Phase.READY) checkConfiguration();
        if (phase == Phase.READY && result == null) return null;
        if (result == null) {
            if (!inputsAvailable()) {
                if (participant) throw fail("INPUT_NOT_READY", name);
                return null;
            }
            // 在外层转换检查点完成批次，避免进入目标应用过程后嵌套调度。
            advance(true);
        }
        if (phase == Phase.INACTIVE || phase == Phase.READY && result == null) return null;
        participant = PARTICIPANTS.contains(name);
        if (!participant) return null;
        checkFailure();
        RuntimeSnapshot snapshot = result;
        if (!Arrays.equals(snapshot.beforeMixin(name), input)) throw fail("INPUT_CHANGED", name);
        LOG.fine("MixinTA verified Mixin input " + name);
        return snapshot.afterMixin(name);
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
                        LOG.fine("MixinTA candidate hint unavailable " + name + ": " + unavailable);
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
        batchStarted = started;
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
        var bindings = DISCOVERY.bind();
        premises = new BatchPremises(batchEnvironment, batchTransformer, sourceState, DISCOVERY.configurationBindings());
        PARTICIPANTS.clear();
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
        declarationNanos = System.nanoTime() - started;
        LOG.fine("MixinTA declarations prepared");
        if (!prepared.hasMigrations()) {
            DISCOVERY.releaseCandidates();
            RAW_CLASSES.clear();
            ORIGINALS.clear();
            sourceIndex.releaseInputs();
            prepared = null;
            transition(Phase.READY);
        }
    }

    private static void analyze() throws Exception {
        checkFailure();
        long started = System.nanoTime();
        IMixinTransformer transformer = (IMixinTransformer) batchTransformer;
        transition(Phase.SNAPSHOTTING);
        inspectSources();
        refreshExisting();
        sourceIndex.verifyIncluded(TypedOverwriteRuntime::analysisCandidate);
        for (var entry : RAW_CLASSES.entrySet()) {
            if (!analysisCandidate(entry.getKey())) continue;
            ClassNode node;
            try {
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
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
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
                    snapshots.put(entry.getKey(), transformer.transformClassBytes(name, name, entry.getValue().clone()));
                    METRICS.add(AnalysisMetrics.Stage.MIXIN, System.nanoTime() - mixinStarted
                            - (METRICS.nanos().getOrDefault(AnalysisMetrics.Stage.PREPROCESS, 0L) - inputBefore));
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
        long snapshotNanos = System.nanoTime() - started;
        started = System.nanoTime();
        transition(Phase.ANALYZING);
        var plan = prepared.analyze(snapshots, METRICS);
        long outputStarted = System.nanoTime();
        Map<String, byte[]> transformed = plan.applyChanges();
        METRICS.add(AnalysisMetrics.Stage.OUTPUT, System.nanoTime() - outputStarted);
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
        METRICS.add(AnalysisMetrics.Stage.RAW_INPUT, sourceIndex.inspectionNanos());
        METRICS.count(AnalysisMetrics.Count.CANDIDATE_CLASSES, sourceIndex.sources().size());
        METRICS.count(AnalysisMetrics.Count.UNAVAILABLE_CLASSES, sourceIndex.inputs().stream()
                .filter(input -> input.status() != ClassSourceIndex.Status.AVAILABLE).count());
        METRICS.count(AnalysisMetrics.Count.FAILED_INPUTS, sourceIndex.inputs().stream().filter(ClassSourceIndex.Input::failed).count());
        METRICS.count(AnalysisMetrics.Count.SNAPSHOT_CLASSES, snapshots.size());
        METRICS.count(AnalysisMetrics.Count.MODIFIED_CLASSES, snapshot.modifiedClasses().size());
        publish(snapshot);
        LOG.fine("MixinTA metrics classes=" + snapshots.size() + " modified=" + snapshot.modifiedClasses().size()
                + " discoveryMs=" + discoveryNanos / 1_000_000 + " declarationMs=" + declarationNanos / 1_000_000
                + " snapshotMs=" + snapshotNanos / 1_000_000 + " analysisMs=" + (System.nanoTime() - started) / 1_000_000
                + " totalMs=" + (System.nanoTime() - batchStarted) / 1_000_000 + METRICS.format());
        LOG.fine("MixinTA batch ready");
    }

    private static void discoverSources() throws Exception {
        KnotClassSources.State current = classSources.state();
        long started = System.nanoTime();
        RAW_CLASSES.clear();
        if (sourceIndex != null) METRICS.add(AnalysisMetrics.Stage.RAW_INPUT, sourceIndex.inspectionNanos());
        sourceIndex = classSources.index(current);
        sourceState = current;
        discoveryNanos += System.nanoTime() - started;
        METRICS.add(AnalysisMetrics.Stage.SOURCE, System.nanoTime() - started);
        LOG.fine("MixinTA discovered classes=" + sourceIndex.sources().size());
    }

    private static void inspectSources() {
        for (String name : sourceIndex.sources().keySet().stream().sorted().toList()) {
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
        if (!MixinDiscovery.hasDeclarations(node)) return;
        if (sourceIndex != null) inspectSources();
        Set<String> targets = new LinkedHashSet<>(MixinDiscovery.targets(node));
        Set<String> methods = new LinkedHashSet<>();
        for (var method : node.methods) {
            var annotation = ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE);
            if (annotation == null) continue;
            methods.add(method.name);
            String selector = (String) ClassFiles.value(annotation, "method", "");
            int descriptor = selector.indexOf('(');
            if (descriptor > 0) methods.add(selector.substring(0, descriptor));
        }
        PARTICIPANTS.addAll(targets);
        for (var entry : RAW_CLASSES.entrySet()) {
            try {
                ClassNode candidate = ClassFiles.read(entry.getValue());
                for (var method : candidate.methods) {
                    MethodReferences.visit(method, (instruction, reference) -> {
                        if (targets.contains(reference.owner()) && methods.contains(reference.name())) PARTICIPANTS.add(entry.getKey());
                    });
                }
            } catch (RuntimeException failure) {
                sourceIndex.parsingFailed(entry.getKey(), failure);
            }
        }
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
                    DEFINITION_INPUTS.keySet().removeIf(name -> PARTICIPANTS.contains(name) || !DEPENDENCIES.contains(name));
                    analysisInputs = Map.of();
                    ORIGINALS.clear();
                    RAW_CLASSES.clear();
                    DISCOVERY.releaseCandidates();
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
        if (phase == Phase.INACTIVE) return null;
        byte[] bytes = (byte[]) arguments[1];
        if (bytes == null) {
            if (arguments.length > 2 && arguments[2] instanceof Class<?> type && !type.isArray()) {
                EXISTING.put(name, type);
                recordSupport(name, type.getProtectionDomain());
            }
            return null;
        }
        if (arguments.length > 2 && arguments[2] instanceof java.security.ProtectionDomain domain) recordSupport(name, domain);
        checkFailure();
        if (phase == Phase.READY && result == null) {
            checkConfiguration();
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
        checkConfiguration();
        if (!PARTICIPANTS.contains(name)) {
            checkBoundary(snapshot, name, bytes);
            return null;
        }
        checkFailure();
        if (!Arrays.equals(snapshot.afterMixin(name), bytes)) throw fail("POST_MIXIN_INPUT_CHANGED", name);
        LOG.fine("MixinTA verified definition input " + name);
        return snapshot.outputOf(name);
    }

    private static void recordSupport(String name, java.security.ProtectionDomain domain) {
        if (domain != null && domain.getCodeSource() != null
                && SUPPORT_SOURCE.equals(domain.getCodeSource().getLocation())) SUPPORT_CLASSES.add(name);
    }

    private static void checkBoundary(RuntimeSnapshot snapshot, String name, byte[] bytes) {
        if (SUPPORT_CLASSES.contains(name)) return;
        RetiredMethodGuard.check(snapshot, name, bytes, TypedOverwriteRuntime::readDependency, TypedOverwriteRuntime::fail);
        if (representationBatch) {
            throw fail("LATE_REPRESENTATION_BOUNDARY", name + " has no verified representation analysis input");
        }
    }

    private static byte[] readInput(String name) throws IOException {
        long started = System.nanoTime();
        try { return FabricLauncherBase.getLauncher().getClassByteArray(name.replace('/', '.'), true); }
        finally { METRICS.add(AnalysisMetrics.Stage.PREPROCESS, System.nanoTime() - started); }
    }

    private static byte[] readIndexedInput(String name) throws IOException {
        byte[] bytes = readInput(name);
        if (bytes == null) throw new IOException("Preprocessed class resource is missing for " + name);
        return bytes;
    }

    private static void checkConfiguration() {
        long started = System.nanoTime();
        try { verifyConfiguration(); }
        finally { METRICS.add(AnalysisMetrics.Stage.PREMISES, System.nanoTime() - started); }
    }

    private static void verifyConfiguration() {
        if (batchEnvironment != null && (batchEnvironment != MixinEnvironment.getCurrentEnvironment()
                || batchTransformer != MixinEnvironment.getCurrentEnvironment().getActiveTransformer()
                || Mixins.getUnvisitedCount() != 0)) {
            throw fail("MIXIN_CONFIGURATION_CHANGED", "batch environment or registered configurations changed");
        }
        if (batchEnvironment != null) {
            try {
                premises.verify(MixinEnvironment.getCurrentEnvironment(), MixinEnvironment.getCurrentEnvironment().getActiveTransformer(),
                        classSources.state(), DISCOVERY.configurationBindings(), TypedOverwriteRuntime::fail);
            } catch (ReflectiveOperationException | java.util.ConcurrentModificationException cause) {
                retain(cause);
                throw failureException();
            }
        }
    }

    private static byte[] readDependency(String name) throws IOException {
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
        LOG.fine("MixinTA phase=" + next);
    }

    private static void releaseOwner() {
        synchronized (MONITOR) {
            owner = null;
            if (failure == null && result == null && phase != Phase.INACTIVE && phase != Phase.READY) phase = Phase.BOOTSTRAPPED;
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
