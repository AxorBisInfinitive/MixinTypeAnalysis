package love.crysh.exp.fabric.testmod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 独立子 JVM 使用正式运行时和测试侧观察器验证启动边界。 */
class RuntimeInitializationIT {
    private static final Path PROJECT = Path.of(System.getProperty("project.directory")).toAbsolutePath();
    private static final String PROBE = "love.crysh.exp.fabric.testmod.initialization.";
    private static final String SUBJECT = "fixture.business.Subject";
    private static final String CALLER = "fixture.business.Caller";

    @TempDir
    Path temporary;

    @Test
    void startsInLanguageAdapterAndMigratesModClassesWithoutManualInitialization() throws Exception {
        Result result = launch("automatic", "", Map.of(), List.of(), false, List.of());
        result.success();
        result.contains("EARLY_INPUT_UNAVAILABLE=early getClassByteArray access", "AGENT_CONNECTED",
                "RESULT=25", "COUNTS=1/1/1 ANALYSES=1", "TARGET_FROM_MOD=true");
        result.ordered("ADAPTER_START", "AGENT_CONNECTED", "PLUGIN_LOAD", "DECLARATIONS_PREPARED",
                "BATCH_READY", "PLAIN_MOD_ENTRY", "BUSINESS_EXECUTED");
    }

    @Test
    void preparesDiscoveredDeclarationsBeforeTargetApplication() throws Exception {
        Result result = launch("support-preloaded", "", Map.of(), List.of(), false, List.of());
        result.success();
        result.ordered("PLUGIN_LOAD state=CONFIGURING", "DECLARATIONS_PREPARED", "BATCH_READY");
    }

    @Test
    void remainsInactiveWithoutMigrationDeclarations() throws Exception {
        Result result = launch("inactive", "", Map.of(), List.of(), false, List.of("-Dprobe.noDeclarations=true"));
        result.success();
        result.contains("INACTIVE_RUNTIME_VERIFIED", "RESULT=2");
        result.contains("AGENT_CONNECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plugin", "server", "client", "value", "excluded", "excluded-environment", "removed", "unchanged"})
    void discoversOnlyEffectiveDeclarationsFromOrdinaryConfigurations(String kind) throws Exception {
        Result result = launch("discovery-" + kind, "", Map.of(), List.of(), false,
                List.of("-Dprobe.discovery=" + kind));
        result.success();
        if (kind.startsWith("excluded") || kind.equals("removed")) {
            result.contains("RESULT=2", "INACTIVE_RUNTIME_VERIFIED");
            assertFalse(result.log().contains("ANALYSIS_BEGIN"), result.log());
        } else if (kind.equals("unchanged")) {
            result.contains("RESULT=6", "LOCAL_OVERWRITE_VERIFIED");
        } else {
            result.contains("RESULT=25", "COUNTS=1/1/1 ANALYSES=1");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparesOnlyTheTargetRetainedByTheNativePlugin(boolean unchanged) throws Exception {
        String descriptor = unchanged ? "int a, int b" : "double a, double b";
        String body = unchanged ? "a - b" : "(int) (a / b * 10)";
        Result result = launch("partial-target-" + unchanged, "", Map.of(
                "fixture.business.OtherSubject", """
                        package fixture.business;
                        public final class OtherSubject { public static int div(int a, int b) { return a / b; } }
                        """,
                "fixture.mixin.SubjectMixin", """
                        package fixture.mixin;
                        @org.spongepowered.asm.mixin.Mixin(targets={"fixture.business.Subject", "fixture.business.OtherSubject"}, remap=false)
                        public class SubjectMixin {
                            @love.crysh.mixinta.anno.TypedOverwrite(method="div(II)I")
                            public static int div(%s) { return %s; }
                        }
                        """.formatted(descriptor, body)), List.of(), false,
                List.of("-Dprobe.discovery=" + (unchanged ? "partial-unchanged" : "partial")));
        result.success();
        result.contains(unchanged ? "RESULT=6" : "RESULT=25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "local", "global"})
    void handlesUnreadableInputsAccordingToTheEffectiveAnalysisScope(String scope) throws Exception {
        List<String> options = new ArrayList<>(List.of("-Dprobe.brokenInput=true"));
        if (scope.equals("inactive")) options.add("-Dprobe.noDeclarations=true");
        if (scope.equals("local")) options.add("-Dprobe.discovery=unchanged");
        Result result = launch("broken-input-" + scope, "", Map.of(), List.of(), false, options);
        if (scope.equals("global")) {
            result.failureBeforeBusiness("UNVERIFIABLE_ANALYSIS_INPUT", "fixture/unused/Broken", "PARSE_FAILED", "outputsPublished=false");
        } else {
            result.success();
            result.contains(scope.equals("local") ? "LOCAL_OVERWRITE_VERIFIED" : "INACTIVE_RUNTIME_VERIFIED");
        }
    }

    @Test
    void reportsAResourceLostBetweenIndexingAndPreprocessing() throws Exception {
        Result result = launch("missing-prepared-input", "", Map.of(), List.of(), false,
                List.of("-Dprobe.missingPreparedInput=true"));
        result.failureBeforeBusiness("UNVERIFIABLE_ANALYSIS_INPUT", "fixture/business/Subject",
                "PREPROCESS_FAILED", "Preprocessed class resource is missing", "outputsPublished=false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "reorder", "replace"})
    void rejectsExistingBindingsChangedAfterPublication(String mutation) throws Exception {
        Result result = launch("late-bindings-" + mutation, """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbePlugin.mutateBindings("%s");
                Class.forName("fixture.business.Caller", false, Main.class.getClassLoader());
                """.formatted(mutation), Map.of("fixture.mixin.OrdinaryMixin", """
                package fixture.mixin;
                @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                public class OrdinaryMixin { private int ordinary; }
                """), List.of(), false, List.of("-Dprobe.bindingMutation=true"));
        result.failure("MIXIN_CONFIGURATION_CHANGED", "effective target bindings changed", "outputsPublished=true");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absorbsConfigurationsAndDevelopmentInputsAddedDuringPreparation(boolean existingRoot) throws Exception {
        Result result = launch("preparation-expansion-" + existingRoot, """
                var caller = Class.forName("fixture.expanded.Caller", true, Main.class.getClassLoader());
                System.out.println("EXPANDED_CALLER=" + caller.getDeclaredMethod("call").invoke(null));
                """, Map.of("fixture.expanded.Caller", """
                package fixture.expanded;
                public class Caller { public static int call() { return fixture.business.Subject.div(10, 4); } }
                """, "fixture.extra.ExtraMixin", """
                package fixture.extra;
                @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                public class ExtraMixin { private int extra; }
                """), List.of(), false, List.of("-Dprobe.expandPreparation=true", "-Dprobe.existingRoot=" + existingRoot));
        result.success();
        result.contains("EXPANDED_CALLER=25", "RESULT=25", "COUNTS=1/1/1 ANALYSES=1");
    }

    @Test
    void discardsTaProblemsFromCandidatesRemovedByNativeValidation() throws Exception {
        Result result = launch("validation-removal", "", Map.of("fixture.mixin.RejectedMixin", """
                package fixture.mixin;
                @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                public class RejectedMixin extends java.util.ArrayList<Object> {
                    @love.crysh.mixinta.anno.TypedOverwrite(method="div(II)I")
                    public static Object div(double a, double b) { return null; }
                }
                """), List.of(), false, List.of("-Dprobe.rejectedCandidate=true"));
        result.success();
        result.contains("RESULT=25");
        assertFalse(result.log().contains("RETURN_TYPE_NOT_IMPLEMENTED"), result.log());
    }

    @Test
    void discoversAnOrdinaryPackagedCallerBeforeItsFirstDefinition() throws Exception {
        Result result = launch("discovered-caller", """
                System.out.println("DISCOVERED_CALLER=" + fixture.other.LateNamedCaller.call());
                """, Map.of("fixture.other.LateNamedCaller", """
                package fixture.other;
                public class LateNamedCaller { public static int call(){ return fixture.business.Subject.div(10,4); } }
                """), List.of(), false, List.of());
        result.success();
        result.contains("DISCOVERED_CALLER=25", "RESULT=25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mod", "nested"})
    void discoversCallersInAnotherModWithoutAMixinTADependency(String source) throws Exception {
        Result result = launch("caller-source-" + source, "", Map.of(), List.of(), false,
                List.of("-Dprobe.callerSource=" + source));
        result.success();
        result.contains("independent_caller 1.0.0", "RESULT=25", "COUNTS=1/1/1 ANALYSES=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mutateBindings", "mutateSources"})
    void rejectsBatchPremiseChangesDuringMixinApplication(String change) throws Exception {
        Result result = launch(change, "", Map.of(), List.of(), false, List.of("-Dprobe." + change + "=true"));
        result.failureBeforeBusiness(change.equals("mutateBindings") ? "effective target bindings changed" : "CLASS_SOURCES_CHANGED",
                "outputsPublished=false");
    }

    @Test
    void rejectsMixinConfigurationsRegisteredAfterPublication() throws Exception {
        Result result = launch("late-configuration", """
                if (Boolean.getBoolean("probe.lateConfig")) {
                    org.spongepowered.asm.mixin.Mixins.addConfiguration("late.mixins.json");
                    Class.forName("fixture.late.Empty", false, Main.class.getClassLoader());
                }
                """, Map.of("fixture.late.Empty", "package fixture.late; public class Empty {}"),
                List.of(), false, List.of("-Dprobe.lateConfig=true"));
        result.failure("MIXIN_CONFIGURATION_CHANGED", "outputsPublished=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"concurrentConfig", "concurrentPlugin", "concurrentConfigUnrelated", "concurrentPluginUnrelated"})
    void rejectsCrossThreadParticipantRequestsDuringMixinWork(String option) throws Exception {
        boolean unrelated = option.endsWith("Unrelated");
        List<String> options = new ArrayList<>(List.of("-Dprobe." + option.replace("Unrelated", "") + "=true"));
        Map<String, String> helpers = Map.of();
        if (unrelated) {
            options.add("-Dprobe.concurrentClass=fixture.support.Unrelated");
            helpers = Map.of("fixture.support.Unrelated", "package fixture.support; public class Unrelated {}");
        }
        Result result = launch(option, "", helpers, List.of(), false, options);
        result.failureBeforeBusiness("CONCURRENT_PREPARATION", "PLUGIN_CONCURRENT_CAUGHT=");
        assertFalse(result.log().contains("BATCH_READY"), result.log());
    }

    @Test
    void initializesBeforeAnotherPreLaunchEntrypointsStaticInitializer() throws Exception {
        String entry = """
                package fixture.entry;
                public class EarlyEntry implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {
                    static { System.out.println("PRELAUNCH_STATIC=" + fixture.business.Caller.run()); }
                    public void onPreLaunch() { System.out.println("PRELAUNCH_EXECUTED"); }
                }
                """;
        Result result = launch("prelaunch", "", Map.of("fixture.entry.EarlyEntry", entry),
                List.of("fixture.entry.EarlyEntry"), false, List.of());
        result.success();
        result.contains("aaa_initialization_early 1.0.0");
        result.ordered("BATCH_READY", "PRELAUNCH_STATIC=25", "PRELAUNCH_EXECUTED", "PLAIN_MOD_ENTRY");
    }

    @Test
    void rejectsPluginReentryAndRetainsThePluginCallChain() throws Exception {
        Result result = launch("plugin-reentry", "", Map.of(), List.of(), false, List.of("-Dprobe.reentrant=true"));
        result.failureBeforeBusiness("REENTRANT_DEFINITION fixture/business/Subject phase=CONFIGURING",
                "InitializationProbePlugin.onLoad", "PLUGIN_CAUGHT");
        assertFalse(result.log().contains("BATCH_READY"), result.log());
    }

    @Test
    void rejectsAnAlreadyDefinedTargetAtMixinPreparation() throws Exception {
        Result result = launch("early-target", "", Map.of(), List.of(), false,
                List.of("-Dprobe.earlyClass=" + SUBJECT));
        result.failureBeforeBusiness("EARLY_DEFINED=" + SUBJECT, "MixinTargetAlreadyLoadedException",
                "target fixture.business.Subject was loaded too early");
    }

    @Test
    void rejectsAnAlreadyDefinedCallerThatNeedsAdaptation() throws Exception {
        Result result = launch("early-caller", "", Map.of(), List.of(), false,
                List.of("-Dprobe.earlyClass=" + CALLER));
        result.failureBeforeBusiness("EARLY_DEFINED=" + CALLER, "EARLY_MODIFIED_CLASS fixture/business/Caller");
        assertFalse(result.log().contains("BATCH_READY"), result.log());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesReferenceRepresentationsDuringAutomaticInitialization(boolean lateWriter) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>(Map.of(
                SUBJECT, """
                        package fixture.business;
                        public final class Subject { public static int lookup(long value) { return (int)value; } }
                        """,
                CALLER, """
                        package fixture.business;
                        public class Caller {
                            public static int run() {
                                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                                queue.add(new BlockPos(12L).asLong());
                                return Subject.lookup(queue.remove());
                            }
                        }
                        """,
                "fixture.business.BlockPos", """
                        package fixture.business;
                        public record BlockPos(long packed) { public long asLong() { return packed; } }
                        """,
                "fixture.mixin.SubjectMixin", """
                        package fixture.mixin;
                        @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                        public class SubjectMixin {
                            @love.crysh.mixinta.anno.TypedOverwrite(method="lookup(J)I",
                                    parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                            public static int lookup(fixture.business.BlockPos value) { return (int)value.packed(); }
                            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE,
                                    method="fixture/business/BlockPos.asLong()J")
                            private static fixture.business.BlockPos keep(fixture.business.BlockPos value) { return value; }
                            @love.crysh.mixinta.anno.TypeRule(love.crysh.mixinta.anno.TypeRule.Kind.READ)
                            private static fixture.business.BlockPos read(fixture.business.BlockPos value) {
                                return java.util.Objects.requireNonNull(value);
                            }
                        }
                        """));
        if (lateWriter) sources.put("fixture.late.LateWriter", """
                package fixture.late;
                public class LateWriter { public static void write(java.util.Queue<Object> queue) { queue.add(12L); } }
                """);
        String defineWriter = lateWriter ? """
                if (Boolean.getBoolean("probe.lateRepresentation")) {
                try {
                    love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.defineLate("fixture.late.LateWriter");
                    throw new AssertionError("Late representation writer defined");
                } catch (ClassFormatError expected) {
                    love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPersistentFailure(true);
                    throw expected;
                }
                }
                """ : "";
        Result result = launch("reference-propagation-" + lateWriter, """
                fixture.business.Subject.class.getDeclaredMethod("lookup", fixture.business.BlockPos.class);
                try {
                    fixture.business.Subject.class.getDeclaredMethod("lookup", long.class);
                    throw new AssertionError("Old lookup survived");
                } catch (NoSuchMethodException expected) { }
                System.out.println("REFERENCE_DESCRIPTOR_VERIFIED");
                """ + defineWriter, sources, List.of(), false,
                lateWriter ? List.of("-Dprobe.lateRepresentation=true") : List.of());
        if (lateWriter) result.failure("LATE_REPRESENTATION_BOUNDARY fixture/late/LateWriter", "FAILURE_RETAINED OUTPUTS_PUBLISHED");
        else {
            result.success();
            result.contains("RESULT=12", "REFERENCE_DESCRIPTOR_VERIFIED", "COUNTS=1/1/1 ANALYSES=1");
        }
    }

    @Test
    void analyzesCallsGeneratedByAnotherMixinPlugin() throws Exception {
        Result result = launch("introduced", """
                System.out.println("INTRODUCED=" + fixture.business.Subject.class.getMethod("introduced").invoke(null));
                """, Map.of(), List.of(), false, List.of("-Dprobe.introducedCall=true"));
        result.success();
        result.contains("INTRODUCED=25", "COUNTS=1/1/1 ANALYSES=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fresh", "generic", "early", "reassigned"})
    void publishesLocalMetadataAndChecksItsOwnerBeforePublication(String scenario) throws Exception {
        boolean generic = scenario.equals("generic");
        String body = scenario.equals("reassigned") ? """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>(); queue.add(new BlockPos(6).asLong());
                int first = Subject.lookup(Forwarder.pass(queue).poll());
                queue = new java.util.ArrayDeque<>(); queue.add(new BlockPos(6).asLong());
                return first + Subject.lookup(Forwarder.pass(queue).poll());
                """ : """
                java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                queue.add(new BlockPos(12).asLong());
                return Subject.lookup(Forwarder.pass(queue).poll());
                """;
        Map<String, String> sources = Map.of(
                SUBJECT, "package fixture.business; public final class Subject { public static int lookup(long pos) { return (int)pos; } }",
                CALLER, "package fixture.business; public class Caller { public static int run() {" + body + "} }",
                "fixture.business.BlockPos", "package fixture.business; public record BlockPos(long value) { public long asLong() { return value; } }",
                "fixture.business.Forwarder", "package fixture.business; public class Forwarder { " + (generic
                        ? "public static <T> java.util.Queue<T> pass(java.util.Queue<T> queue) { return queue; }"
                        : "public static java.util.Queue<Long> pass(java.util.Queue<Long> queue) { return queue; }") + " }",
                "fixture.mixin.SubjectMixin", """
                        package fixture.mixin;
                        @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                        public class SubjectMixin {
                            @love.crysh.mixinta.anno.TypedOverwrite(method="lookup(J)I",
                                    parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
                            public static int lookup(fixture.business.BlockPos value) { return (int)value.value(); }
                            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE,
                                    method="fixture/business/BlockPos.asLong()J")
                            private static fixture.business.BlockPos keep(fixture.business.BlockPos value) { return value; }
                            @love.crysh.mixinta.anno.TypeRule(love.crysh.mixinta.anno.TypeRule.Kind.READ)
                            private static fixture.business.BlockPos read(fixture.business.BlockPos value) { return java.util.Objects.requireNonNull(value); }
                        }
                        """);
        Result result = launch("local-metadata-" + scenario, """
                var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                field.setAccessible(true);
                var snapshot = field.get(null);
                field = snapshot.getClass().getDeclaredField("modifiedClasses");
                field.setAccessible(true);
                var modified = (java.util.Set<String>) field.get(snapshot);
                if (modified.contains("fixture/business/Forwarder") != %s) throw new AssertionError(modified);
                var outputOf = snapshot.getClass().getDeclaredMethod("outputOf", String.class);
                outputOf.setAccessible(true);
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader((byte[]) outputOf.invoke(snapshot, "fixture/business/Forwarder")).accept(node, 0);
                var pass = node.methods.stream().filter(method -> method.name.equals("pass")).findFirst().orElseThrow();
                if (!pass.localVariables.getFirst().signature.equals("%s")) throw new AssertionError(pass.localVariables.getFirst().signature);
                if (%s) {
                    node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader((byte[]) outputOf.invoke(snapshot, "fixture/business/Caller")).accept(node, 0);
                    var run = node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
                    var bridges = java.util.Arrays.stream(run.instructions.toArray())
                            .filter(instruction -> instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.startsWith("mixinta$rule$"))
                            .map(instruction -> ((org.objectweb.asm.tree.MethodInsnNode)instruction).name)
                            .collect(java.util.stream.Collectors.groupingBy(name -> name, java.util.stream.Collectors.counting()));
                    if (bridges.size() != 2 || bridges.values().stream().anyMatch(count -> count != 2L)) throw new AssertionError(bridges);
                    var queue = run.localVariables.stream().filter(local -> local.name.equals("queue")).findFirst().orElseThrow();
                    if (!queue.signature.equals("Ljava/util/Queue<Lfixture/business/BlockPos;>;")) throw new AssertionError(queue.signature);
                    System.out.println("REUSED_RULE_BRIDGES_VERIFIED");
                }
                System.out.println("LOCAL_METADATA_VERIFIED");
                """.formatted(!generic, generic ? "Ljava/util/Queue<TT;>;" : "Ljava/util/Queue<Lfixture/business/BlockPos;>;", scenario.equals("reassigned")),
                sources, List.of(), false,
                scenario.equals("early") ? List.of("-Dprobe.earlyClass=fixture.business.Forwarder") : List.of());
        if (scenario.equals("early")) {
            result.failureBeforeBusiness("EARLY_DEFINED=fixture.business.Forwarder", "EARLY_MODIFIED_CLASS fixture/business/Forwarder");
            assertFalse(result.log().contains("BATCH_READY"), result.log());
        } else {
            result.success();
            result.contains("RESULT=12", "LOCAL_METADATA_VERIFIED", "COUNTS=1/1/1 ANALYSES=1");
            if (scenario.equals("reassigned")) result.contains("REUSED_RULE_BRIDGES_VERIFIED");
        }
    }

    @Test
    void permitsAnAlreadyDefinedReadOnlyClassInsideTheCandidateDomain() throws Exception {
        Result result = launch("read-only", """
                System.out.println("READONLY_SAME_LOADER=" + (fixture.business.ReadOnly.class.getClassLoader()
                        == fixture.business.Subject.class.getClassLoader()));
                """, Map.of("fixture.business.ReadOnly", "package fixture.business; public interface ReadOnly {}"),
                List.of(), false, List.of("-Dprobe.earlyClass=fixture.business.ReadOnly"));
        result.success();
        result.contains("EARLY_DEFINED=fixture.business.ReadOnly", "READONLY_SAME_LOADER=true", "RESULT=25");
    }

    @Test
    void coordinatesConcurrentFirstLoadsOutsideTheMixinMonitor() throws Exception {
        String entry = """
                package fixture.entry;
                public class ConcurrentEntry implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {
                    public void onPreLaunch() {
                        var start = new java.util.concurrent.CountDownLatch(1);
                        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
                        var threads = new java.util.ArrayList<Thread>();
                        for (String name : java.util.List.of("fixture.business.Subject", "fixture.business.Caller")) {
                            Thread thread = new Thread(() -> {
                                try {
                                    start.await();
                                    Class.forName(name, false, getClass().getClassLoader());
                                } catch (Throwable failure) { failures.add(failure); }
                            }, name);
                            threads.add(thread);
                            thread.start();
                        }
                        start.countDown();
                        for (Thread thread : threads) {
                            try { thread.join(15000); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
                            if (thread.isAlive()) throw new AssertionError("Class loading deadlocked");
                        }
                        if (!failures.isEmpty()) throw new AssertionError(failures.toString(), failures.peek());
                        System.out.println("CONCURRENT_LOADS_COMPLETED");
                    }
                }
                """;
        Result result = launch("concurrent", "", Map.of("fixture.entry.ConcurrentEntry", entry),
                List.of("fixture.entry.ConcurrentEntry"), false,
                List.of("-Dprobe.concurrent=true"));
        result.success();
        result.contains("WAIT_FOR_BATCH=", "CONCURRENT_LOADS_COMPLETED", "COUNTS=1/1/1 ANALYSES=1");
    }

    @Test
    void reusesTheSameMixinAndExtensionResultsDuringRealClassDefinition() throws Exception {
        Result result = launch("single-application", """
                for (int count = 0; count < 3; count++) {
                    love.crysh.mixinta.runtime.TypedOverwriteRuntime.bootstrap();
                    love.crysh.mixinta.runtime.TypedOverwriteRuntime.prepare();
                    love.crysh.mixinta.runtime.TypedOverwriteRuntime.initialize();
                    Class.forName("fixture.business.Subject", false, Main.class.getClassLoader());
                    Class.forName("fixture.business.Caller", false, Main.class.getClassLoader());
                }
                """, Map.of(), List.of(), false, List.of());
        result.success();
        result.contains("COUNTS=1/1/1 ANALYSES=1");
        assertEquals(1, result.log().lines().filter(line -> line.equals("ANALYSIS_BEGIN")).count(), result.log());
    }

    @Test
    void usesActualPreMixinBytesIncludingClassTweakerChanges() throws Exception {
        Result result = launch("class-tweaker", """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPreprocessing(true);
                """, Map.of(), List.of(), true, List.of(), Set.of(SUBJECT));
        result.success();
        result.contains("PREPROCESSING_VERIFIED widened=true", "RESULT=25", "TARGET_FROM_MOD=false");
    }

    @Test
    void usesPreprocessedMetadataForHierarchyDependenciesOutsideTheCandidateDomain() throws Exception {
        Map<String, String> sources = Map.of(
                "fixture.external.Parent", "package fixture.external; public class Parent { private int div(int a,int b){return a/b;} }",
                SUBJECT, """
                        package fixture.business;
                        public final class Subject extends fixture.external.Parent {
                            public int div(int a,int b){return a/b;}
                            private static int helper(){return 7;}
                        }
                        """,
                CALLER, "package fixture.business; public class Caller {public static int run(){return new Subject().div(10,4);}}",
                "fixture.mixin.SubjectMixin", """
                        package fixture.mixin;
                        @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                        public class SubjectMixin {
                            @love.crysh.mixinta.anno.TypedOverwrite(method="div(II)I")
                            public int div(double a,double b){return (int)(a/b*10);}
                        }
                        """);
        Result baseline = launch("hierarchy-input-baseline", "", sources, List.of(), false, List.of(),
                Set.of(SUBJECT, "fixture.external.Parent"));
        baseline.success();
        baseline.contains("RESULT=25");
        Result result = launch("hierarchy-input-stage", "", sources, List.of(), true, List.of(),
                Set.of(SUBJECT, "fixture.external.Parent"));
        result.failureBeforeBusiness("OVERRIDE_CHAIN_NOT_IMPLEMENTED");
    }

    @Test
    void preservesModClassTweakerPolicyWhileMigratingModClasses() throws Exception {
        Result result = launch("class-tweaker-disabled", """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPreprocessing(false);
                """, Map.of(), List.of(), true, List.of());
        result.success();
        result.contains("PREPROCESSING_VERIFIED widened=false", "RESULT=25", "TARGET_FROM_MOD=true");
    }

    @Test
    void stripsEnvironmentMembersFromApplicationClassesWithoutClassTweakerRules() throws Exception {
        Result result = launch("environment-application", """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPreprocessing(false);
                """, Map.of(), List.of(), false, List.of(), Set.of(SUBJECT));
        result.success();
        result.contains("PREPROCESSING_VERIFIED widened=false", "TARGET_FROM_MOD=false");
    }

    @Test
    void preservesPreprocessingInTheDevelopmentEnvironment() throws Exception {
        Result result = launch("environment-development", """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPreprocessing(true);
                """, Map.of(), List.of(), true, List.of("-Dfabric.development=true"), Set.of(SUBJECT));
        result.success();
        result.contains("PREPROCESSING_VERIFIED widened=true", "TARGET_FROM_MOD=false", "RESULT=25");
    }

    @Test
    void indexesRuntimeSelectedMultiReleaseApplicationClasses() throws Exception {
        Result result = launch("multi-release-application", """
                love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPreprocessing(true);
                """, Map.of(), List.of(), true, List.of("-Dprobe.multiReleaseApplication=true"), Set.of(SUBJECT));
        result.success();
        result.contains("PREPROCESSING_VERIFIED widened=true", "TARGET_FROM_MOD=false", "RESULT=25");
    }

    @Test
    void keepsModClassesUnderTheApplicationPackageOnTheModPolicy() throws Exception {
        String name = "love.crysh.exp.fabric.testmod.PrefixSubject";
        Result result = launch("mod-package-prefix", """
                var type = love.crysh.exp.fabric.testmod.PrefixSubject.class;
                if (!java.lang.reflect.Modifier.isPrivate(type.getDeclaredMethod("helper").getModifiers())) {
                    throw new AssertionError("Mod class misclassified as application");
                }
                try {
                    type.getDeclaredMethod("clientOnly");
                    throw new AssertionError("Client method survived");
                } catch (NoSuchMethodException expected) { }
                System.out.println("MOD_PACKAGE_POLICY_VERIFIED");
                """, Map.of(name, """
                package love.crysh.exp.fabric.testmod;
                public class PrefixSubject {
                    private static int helper() { return 7; }
                    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                    public static int clientOnly() { return 1; }
                }
                """), List.of(), true, List.of());
        result.success();
        result.contains("MOD_PACKAGE_POLICY_VERIFIED", "RESULT=25");
    }

    @Test
    void rejectsApplicationClassNamesThatAlsoExistInAMod() throws Exception {
        Result result = launch("application-source-conflict", "", Map.of(), List.of(), false,
                List.of("-Dprobe.duplicateApplicationClass=true"), Set.of(SUBJECT));
        result.failureBeforeBusiness("Exp 应用类来源冲突: " + SUBJECT, "expected=", "actual=");
        assertFalse(result.log().contains("ANALYSIS_BEGIN"), result.log());
    }

    @Test
    void rejectsClientOnlyApplicationClassesInTheServerEnvironment() throws Exception {
        Result result = launch("environment-application-class", """
                Class.forName("fixture.environment.ClientOnly", false, Main.class.getClassLoader());
                """, Map.of("fixture.environment.ClientOnly", """
                package fixture.environment;
                @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                public class ClientOnly { }
                """), List.of(), false, List.of(), Set.of("fixture.environment.ClientOnly"));
        result.failure("Cannot load class fixture.environment.ClientOnly in environment type SERVER");
    }

    @Test
    void rejectsClientOnlyModClassesInTheServerEnvironment() throws Exception {
        Result result = launch("environment-mod-class", """
                Class.forName("fixture.environment.ClientOnly", false, Main.class.getClassLoader());
                """, Map.of("fixture.environment.ClientOnly", """
                package fixture.environment;
                @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                public class ClientOnly { }
                """), List.of(), false, List.of());
        result.failure("Cannot load class fixture.environment.ClientOnly in environment type SERVER");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLateOldDescriptorCallsAtClassDefinition(boolean failingLogger) throws Exception {
        String scenario = failingLogger ? "late-caller-logger-failure" : "late-caller";
        Result result = launch(scenario, """
                if (Boolean.getBoolean("probe.lateCheck")) {
                    try {
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.defineLate("fixture.late.LateCaller");
                        Class.forName("fixture.late.LateCaller", false, Main.class.getClassLoader());
                        throw new AssertionError("Late caller unexpectedly defined");
                    } catch (ClassFormatError expected) {
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPersistentFailure(true);
                        throw expected;
                    }
                }
                """, Map.of("fixture.late.LateCaller", """
                package fixture.late;
                public class LateCaller { public static int call(){ return fixture.business.Subject.div(10,4); } }
                """), List.of(), false, List.of("-Dprobe.lateCheck=true", "-Dprobe.failAgentLogger=" + failingLogger));
        result.failure("LATE_OLD_DESCRIPTOR fixture/late/LateCaller.call", "FAILURE_RETAINED OUTPUTS_PUBLISHED",
                "AGENT_ERROR_LOG_VERIFIED=MixinTA class transformation failed for fixture/late/LateCaller",
                "java.lang.IllegalStateException: MixinTA initialization failed",
                "RetiredMethodGuard.check", "java.lang.ClassFormatError");
        if (failingLogger) result.contains("AGENT_LOGGER_FAILURE");
        assertFalse(result.log().contains("NoSuchMethodError"), result.log());
    }

    @ParameterizedTest
    @ValueSource(strings = {"handle", "lambda", "condy", "inherited"})
    void rejectsLateReferencesInBootstrapConstantsAndInheritedOwners(String kind) throws Exception {
        Map<String, String> helpers = new LinkedHashMap<>();
        String body = kind.equals("lambda")
                ? "java.util.function.IntBinaryOperator operation = fixture.business.Subject::div; return operation.applyAsInt(10,4);"
                : "return 0;";
        if (kind.equals("inherited")) {
            helpers.put(SUBJECT, "package fixture.business; public class Subject { public static int div(int a,int b){return a/b;} }");
            helpers.put("fixture.late.Child", "package fixture.late; public class Child extends fixture.business.Subject {}");
            body = "return Child.div(10,4);";
        }
        helpers.put("fixture.late.LateCaller", "package fixture.late; public class LateCaller { public static int call(){" + body + "} }");
        Result baseline = launch("late-baseline-" + kind,
                "System.out.println(\"LATE_BASELINE=\" + fixture.late.LateCaller.call());", helpers, List.of(), false,
                List.of("-Dprobe.lateKind=" + kind, "-Dprobe.noDeclarations=true"));
        baseline.success();
        baseline.contains("LATE_BASELINE=" + (kind.equals("handle") || kind.equals("condy") ? 0 : 2));
        Result result = launch("late-" + kind, """
                if (System.getProperty("probe.lateKind") != null) {
                    try {
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.defineLate("fixture.late.LateCaller");
                        Class.forName("fixture.late.LateCaller", false, Main.class.getClassLoader());
                        throw new AssertionError("Late reference unexpectedly defined");
                    } catch (ClassFormatError expected) {
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verifyPersistentFailure(true);
                        throw expected;
                    }
                }
                """, helpers, List.of(), false, List.of("-Dprobe.lateKind=" + kind));
        result.failure("LATE_OLD_DESCRIPTOR fixture/late/LateCaller.call", "FAILURE_RETAINED OUTPUTS_PUBLISHED");
    }

    @Test
    void retainsAnAnalysisFailureAndPublishesNoOutputs() throws Exception {
        Result result = launch("retained-failure", "", Map.of(), List.of(), false,
                List.of("-Dprobe.changeOriginal=true", "-Dprobe.verifyFailure=true"));
        result.failureBeforeBusiness("TARGET_CHANGED_AFTER_PREPARATION",
                "FAILURE_RETAINED OUTPUTS_ABSENT");
    }

    private Result launch(String scenario, String mainBody, Map<String, String> helpers, List<String> preLaunch,
            boolean classTweaker, List<String> options) throws Exception {
        return launch(scenario, mainBody, helpers, preLaunch, classTweaker, options, Set.of());
    }

    private Result launch(String scenario, String mainBody, Map<String, String> helpers, List<String> preLaunch,
            boolean classTweaker, List<String> options, Set<String> applicationClasses) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(scenario));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(SUBJECT, """
                package fixture.business;
                public final class Subject {
                    public static int div(int a, int b) { return a / b; }
                    private static int helper() { return 7; }
                    protected static int protectedHelper() { return 8; }
                    static int packageHelper() { return 9; }
                    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                    public static int clientOnly() { return 1; }
                    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
                    public static int serverOnly() { return 2; }
                }
                """);
        sources.put(CALLER, """
                package fixture.business;
                public class Caller {
                    public static int run() { return Subject.div(10, 4); }
                    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                    public static int clientOnly() { return 1; }
                    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
                    public static int serverOnly() { return 2; }
                }
                """);
        sources.put("fixture.mixin.SubjectMixin", """
                package fixture.mixin;
                @org.spongepowered.asm.mixin.Mixin(targets="fixture.business.Subject", remap=false)
                public class SubjectMixin {
                    @love.crysh.mixinta.anno.TypedOverwrite(method="div(II)I")
                    public static int div(double a, double b) { return (int) (a / b * 10); }
                }
                """);
        sources.put("fixture.entry.MainEntry", """
                package fixture.entry;
                public class MainEntry implements net.fabricmc.api.ModInitializer {
                    public void onInitialize() { System.out.println("PLAIN_MOD_ENTRY"); }
                }
                """);
        sources.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        %s
                        System.out.println("RESULT=" + fixture.business.Caller.run());
                        System.out.println("TARGET_FROM_MOD=" + fixture.business.Subject.class.getProtectionDomain()
                                .getCodeSource().getLocation().toString().contains("fixture.jar"));
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verify();
                    }
                }
                """.formatted(mainBody));
        sources.putAll(helpers);
        String discovery = options.stream().filter(option -> option.startsWith("-Dprobe.discovery="))
                .map(option -> option.substring("-Dprobe.discovery=".length())).findFirst().orElse("");
        if (discovery.equals("value")) {
            sources.computeIfPresent("fixture.mixin.SubjectMixin", (name, source) -> source.replace(
                    "targets=\"fixture.business.Subject\"", "value=fixture.business.Subject.class"));
        }
        if (discovery.startsWith("excluded") || discovery.equals("removed")) {
            sources.computeIfPresent("fixture.mixin.SubjectMixin", (name, source) -> source.replace(
                    "int div(double a, double b) { return (int) (a / b * 10); }",
                    "Object div(double a, double b) { return null; }"));
        }
        if (discovery.equals("excluded-environment")) {
            sources.computeIfPresent(SUBJECT, (name, source) -> source.replace("public final class Subject",
                    "@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT) public final class Subject"));
            sources.put("love.crysh.exp.Main", """
                    package love.crysh.exp;
                    public class Main { public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        System.out.println("RESULT=2");
                        love.crysh.exp.fabric.testmod.initialization.InitializationProbeRuntime.verify();
                    } }
                    """);
        }
        if (discovery.equals("unchanged")) {
            sources.computeIfPresent("fixture.mixin.SubjectMixin", (name, source) -> source.replace(
                    "int div(double a, double b) { return (int) (a / b * 10); }",
                    "int div(int a, int b) { return a - b; }"));
        }
        if (discovery.equals("client")) sources.put("fixture.bootstrap.ClientLauncher", """
                package fixture.bootstrap;
                public class ClientLauncher { public static void main(String[] args) {
                    net.fabricmc.loader.impl.launch.knot.Knot.launch(args, net.fabricmc.api.EnvType.CLIENT);
                } }
                """);
        if (options.contains("-Dprobe.noDeclarations=true") && options.stream().anyMatch(option -> option.startsWith("-Dprobe.lateKind="))) {
            sources.computeIfPresent("love.crysh.exp.Main", (name, source) -> source.replace("System.out.println(\"BUSINESS_EXECUTED\");",
                    "System.out.println(\"BUSINESS_EXECUTED\"); " + PROBE + "InitializationProbeRuntime.defineLate(\"fixture.late.LateCaller\");"));
        }
        compile(root, classes, sources);
        Map<String, byte[]> modEntries = new LinkedHashMap<>();
        Map<String, byte[]> appEntries = new LinkedHashMap<>();
        for (var entry : classEntries(classes).entrySet()) {
            String className = entry.getKey().substring(0, entry.getKey().length() - 6).replace('/', '.');
            if (className.equals("love.crysh.exp.Main") || className.startsWith("love.crysh.exp.Main$")
                    || applicationClasses.contains(className)) appEntries.put(entry.getKey(), entry.getValue());
            else modEntries.put(entry.getKey(), entry.getValue());
        }
        if (options.contains("-Dprobe.brokenInput=true")) modEntries.put("fixture/unused/Broken.class", new byte[] {0});
        Path preparationRoot = null;
        if (options.contains("-Dprobe.expandPreparation=true")) {
            preparationRoot = Files.createDirectories(root.resolve("expanded-root"));
            String filename = options.contains("-Dprobe.existingRoot=true") ? "Caller.class.staged" : "Caller.class";
            Path caller = preparationRoot.resolve("fixture/expanded/" + filename);
            Files.createDirectories(caller.getParent());
            Files.write(caller, modEntries.remove("fixture/expanded/Caller.class"));
            modEntries.put("extra.mixins.json", """
                    {"required":true,"minVersion":"0.8.7","package":"fixture.extra","compatibilityLevel":"JAVA_25",
                     "mixins":["ExtraMixin"]}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        if (options.contains("-Dprobe.missingPreparedInput=true")) {
            preparationRoot = Files.createDirectories(root.resolve("indexed-root"));
            Path subject = preparationRoot.resolve("fixture/business/Subject.class");
            Files.createDirectories(subject.getParent());
            Files.write(subject, modEntries.remove("fixture/business/Subject.class"));
        }
        Path bootstrapClasses = null;
        if (discovery.equals("client")) {
            bootstrapClasses = Files.createDirectories(root.resolve("bootstrap"));
            Path launcher = bootstrapClasses.resolve("fixture/bootstrap/ClientLauncher.class");
            Files.createDirectories(launcher.getParent());
            Files.write(launcher, modEntries.remove("fixture/bootstrap/ClientLauncher.class"));
        }
        if (options.contains("-Dprobe.duplicateApplicationClass=true")) {
            modEntries.put("fixture/business/Subject.class", appEntries.get("fixture/business/Subject.class"));
        }
        if (options.contains("-Dprobe.multiReleaseApplication=true")) {
            appEntries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            appEntries.put("META-INF/versions/25/fixture/business/Subject.class",
                    appEntries.remove("fixture/business/Subject.class"));
        }
        for (var entry : classEntries(PROJECT.resolve("target/test-classes")).entrySet()) {
            if (entry.getKey().startsWith(PROBE.replace('.', '/'))) modEntries.put(entry.getKey(), entry.getValue());
        }
        if (options.contains("-Dprobe.lateKind=handle") || options.contains("-Dprobe.lateKind=condy")) {
            ClassNode node = new ClassNode();
            new ClassReader(modEntries.get("fixture/late/LateCaller.class")).accept(node, 0);
            var method = node.methods.stream().filter(value -> value.name.equals("call")).findFirst().orElseThrow();
            Handle target = new Handle(Opcodes.H_INVOKESTATIC, "fixture/business/Subject", "div", "(II)I", false);
            Object constant = target;
            if (options.contains("-Dprobe.lateKind=condy")) {
                String prefix = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;";
                Handle cast = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "explicitCast",
                        prefix + "Ljava/lang/Object;)Ljava/lang/Object;", false);
                Handle invoke = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "invoke",
                        prefix + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                var inner = new ConstantDynamic("operation", "Ljava/lang/invoke/MethodHandle;", cast, target);
                constant = new ConstantDynamic("value", "Ljava/lang/Integer;", invoke, inner, 10, 4);
            }
            method.instructions.clear();
            method.instructions.add(new LdcInsnNode(constant));
            method.instructions.add(new InsnNode(Opcodes.POP));
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            modEntries.put("fixture/late/LateCaller.class", writer.toByteArray());
        }
        if (options.contains("-Dprobe.lateCheck=true") || options.stream().anyMatch(option -> option.startsWith("-Dprobe.lateKind="))) {
            byte[] bytes = modEntries.remove("fixture/late/LateCaller.class");
            if (bytes != null) modEntries.put("late/fixture/late/LateCaller.class", bytes);
        }
        if (options.contains("-Dprobe.lateRepresentation=true")) {
            modEntries.put("late/fixture/late/LateWriter.class", modEntries.remove("fixture/late/LateWriter.class"));
        }
        String nested = "META-INF/jars/mixin-type-analysis-0.1.0-Snapshot.jar";
        try (JarFile packaged = new JarFile(PROJECT.resolve("target/exp-fabric-testmod-0.1.0-Snapshot.jar").toFile())) {
            try (var input = packaged.getInputStream(packaged.getJarEntry(nested))) {
                modEntries.put(nested, input.readAllBytes());
            }
        }
        String metadata = """
                {"schemaVersion":1,"id":"initialization_fixture","version":"1.0.0","environment":"*",
                 "languageAdapters":{"initialization_probe":"%sInitializationProbeAdapter"},
                 "entrypoints":{"main":["fixture.entry.MainEntry"],"preLaunch":[%s]},
                 "mixins":["probe.mixins.json"],"depends":{"mixinta":"*"},"jars":[{"file":"%s"}]%s}
                """.formatted(PROBE, strings(preLaunch), nested,
                classTweaker ? ",\"accessWidener\":\"fixture.accesswidener\"" : "");
        if (options.contains("-Dprobe.noDeclarations=true")) {
            metadata = metadata.replace("\"mixins\":[\"probe.mixins.json\"]", "\"mixins\":[]");
        }
        modEntries.put("fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
        modEntries.put("late.mixins.json", "{\"required\":true,\"package\":\"fixture.late.mixin\",\"mixins\":[]}"
                .getBytes(StandardCharsets.UTF_8));
        String declarations = switch (discovery) {
            case "plugin" -> "\"mixins\":[]";
            case "server" -> "\"mixins\":[],\"server\":[\"SubjectMixin\"],\"client\":[\"MissingClientMixin\"]";
            case "client" -> "\"mixins\":[],\"client\":[\"SubjectMixin\"],\"server\":[\"MissingServerMixin\"]";
            default -> "\"mixins\":[\"SubjectMixin\"]";
        };
        modEntries.put("probe.mixins.json", ("""
                {"required":true,"package":"fixture.mixin","compatibilityLevel":"JAVA_25",
                 "plugin":"%sInitializationProbePlugin",%s}
                """).formatted(PROBE, declarations).getBytes(StandardCharsets.UTF_8));
        if (options.contains("-Dprobe.rejectedCandidate=true")) {
            String config = new String(modEntries.get("probe.mixins.json"), StandardCharsets.UTF_8);
            modEntries.put("probe.mixins.json", config.replace("\"SubjectMixin\"", "\"SubjectMixin\",\"RejectedMixin\"")
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (options.contains("-Dprobe.bindingMutation=true")) {
            String config = new String(modEntries.get("probe.mixins.json"), StandardCharsets.UTF_8);
            modEntries.put("probe.mixins.json", config.replace("\"SubjectMixin\"", "\"SubjectMixin\",\"OrdinaryMixin\"")
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (classTweaker) {
            String rules = """
                    accessWidener v2 official
                    accessible method fixture/business/Subject helper ()I
                    """;
            if (helpers.containsKey("love.crysh.exp.fabric.testmod.PrefixSubject")) {
                rules += "accessible method love/crysh/exp/fabric/testmod/PrefixSubject helper ()I\n";
            }
            if (helpers.containsKey("fixture.external.Parent")) {
                rules += "accessible method fixture/external/Parent div (II)I\n";
            }
            modEntries.put("fixture.accesswidener", rules.getBytes(StandardCharsets.UTF_8));
        }
        Path appJar = root.resolve("app.jar");
        jar(appJar, appEntries);
        Path run = Files.createDirectories(root.resolve("run"));
        Path mods = Files.createDirectories(run.resolve("mods"));
        String callerSource = options.stream().filter(option -> option.startsWith("-Dprobe.callerSource="))
                .map(option -> option.substring("-Dprobe.callerSource=".length())).findFirst().orElse("");
        if (!callerSource.isEmpty()) {
            Map<String, byte[]> independent = new LinkedHashMap<>();
            independent.put("fixture/business/Caller.class", modEntries.remove("fixture/business/Caller.class"));
            independent.put("fabric.mod.json", """
                    {"schemaVersion":1,"id":"independent_caller","version":"1.0.0","environment":"*"}
                    """.getBytes(StandardCharsets.UTF_8));
            if (callerSource.equals("mod")) jar(mods.resolve("independent.jar"), independent);
            else {
                Path nestedCaller = root.resolve("independent.jar");
                jar(nestedCaller, independent);
                String resource = "META-INF/jars/independent.jar";
                modEntries.put(resource, Files.readAllBytes(nestedCaller));
                metadata = metadata.replace("\"file\":\"" + nested + "\"}]",
                        "\"file\":\"" + nested + "\"},{\"file\":\"" + resource + "\"}]");
                modEntries.put("fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!preLaunch.isEmpty()) {
            Map<String, byte[]> earlyEntries = new LinkedHashMap<>();
            for (String entrypoint : preLaunch) {
                String prefix = entrypoint.replace('.', '/');
                for (String entry : new ArrayList<>(modEntries.keySet())) {
                    if (entry.equals(prefix + ".class") || entry.startsWith(prefix + "$")) {
                        earlyEntries.put(entry, modEntries.remove(entry));
                    }
                }
            }
            String earlyMetadata = """
                    {"schemaVersion":1,"id":"aaa_initialization_early","version":"1.0.0","environment":"*",
                     "entrypoints":{"preLaunch":[%s]}}
                    """.formatted(strings(preLaunch));
            earlyEntries.put("fabric.mod.json", earlyMetadata.getBytes(StandardCharsets.UTF_8));
            jar(mods.resolve("early-entry.jar"), earlyEntries);
            metadata = metadata.replace("\"preLaunch\":[" + strings(preLaunch) + "]", "\"preLaunch\":[]");
            modEntries.put("fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
        }
        jar(mods.resolve("fixture.jar"), modEntries);
        Path bridge = PROJECT.getParent().resolve("exp-fabric/target");
        Path bridgeJar = bridge.resolve("exp-fabric-0.1.0-Snapshot.jar");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        command.addAll(options);
        String analysis = System.getProperty("mixinta.analysis");
        if (analysis != null) command.add("-Dmixinta.analysis=" + analysis);
        if (preparationRoot != null) command.add("-Dprobe.extraRoot=" + preparationRoot);
        if (options.contains("-Dprobe.mutateSources=true")) {
            command.add("-Dprobe.extraRoot=" + Files.createDirectories(root.resolve("extra-source")));
        }
        command.add("-Dexp.fabric.jar=" + appJar);
        command.add("-Dexp.fabric.runDir=" + run);
        command.add("-cp");
        command.add(bridgeJar + File.pathSeparator + bridge.resolve("lib") + "/*"
                + (bootstrapClasses == null ? "" : File.pathSeparator + bootstrapClasses));
        command.add(bootstrapClasses == null ? "love.crysh.exp.fabric.Launcher" : "fixture.bootstrap.ClientLauncher");
        Path log = root.resolve("child.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Probe JVM timed out, log at " + log);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String output = Files.readString(log);
        Path evidence = Files.createDirectories(PROJECT.resolve("target/initialization-validation"));
        Files.writeString(evidence.resolve(scenario + ".log"), "EXIT=" + child.exitValue() + "\n" + output);
        return new Result(child.exitValue(), output);
    }

    private static void compile(Path root, Path classes, Map<String, String> sources) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-g", "-proc:none", "-encoding", "UTF-8",
                "-d", classes.toString(), "-classpath", System.getProperty("java.class.path")));
        for (var entry : sources.entrySet()) {
            Path source = root.resolve("sources").resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
    }

    private static Map<String, byte[]> classEntries(Path directory) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                entries.put(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return entries;
    }

    private static void jar(Path target, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static String strings(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(","));
    }

    private record Result(int exitCode, String log) {
        void success() {
            assertEquals(0, exitCode, log);
            contains("BUSINESS_EXECUTED");
        }

        void contains(String... values) {
            for (String value : values) assertTrue(log.contains(value), "Missing " + value + "\n" + log);
        }

        void ordered(String... values) {
            int previous = -1;
            for (String value : values) {
                int current = log.indexOf(value);
                assertTrue(current > previous, "Unexpected order for " + value + "\n" + log);
                previous = current;
            }
        }

        void failure(String... values) {
            assertNotEquals(0, exitCode, log);
            contains(values);
        }

        void failureBeforeBusiness(String... values) {
            failure(values);
            assertFalse(log.contains("BUSINESS_EXECUTED"), log);
        }
    }
}
