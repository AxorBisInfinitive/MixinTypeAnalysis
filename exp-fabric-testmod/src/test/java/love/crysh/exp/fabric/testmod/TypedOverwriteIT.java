package love.crysh.exp.fabric.testmod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 每个样本通过实际打包的 JiJ 模组在独立 JVM 中运行。 */
class TypedOverwriteIT {
    private static final Path PROJECT = Path.of(System.getProperty("project.directory")).toAbsolutePath();
    private static final String SUBJECT = "love.crysh.exp.Subject";
    private static final String MIXIN_PACKAGE = "fixture.mixin";
    private static final String TYPED = "@love.crysh.mixinta.TypedOverwrite(method=\"div(II)I\")";
    private static final String REFERENCE_MIXIN = """
            @love.crysh.mixinta.TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.ParameterMigration(parameter=0, rules=SubjectMixin.class))
            public static int lookup(love.crysh.exp.BlockPos value){return (int)value.value();}
            @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.SOURCE, method="love.crysh.exp.BlockPos.asLong()J")
            private static love.crysh.exp.BlockPos keep(love.crysh.exp.BlockPos value){return value;}
            @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.ENCODE)
            private static long encode(love.crysh.exp.BlockPos value){return value.value();}
            @love.crysh.mixinta.TypeRule(value=love.crysh.mixinta.TypeRule.Kind.READ)
            private static love.crysh.exp.BlockPos read(love.crysh.exp.BlockPos value){return java.util.Objects.requireNonNull(value);}
            """;

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"caller", "target"})
    void migratesCrossClassCallsInEitherLoadOrderAndMixinIntroducedCalls(String order) throws Exception {
        Map<String, String> app = application("public static int div(int a,int b){return a/b;}", """
                Class.forName("%s", false, Main.class.getClassLoader());
                System.out.println("RESULT=" + Caller.run());
                Subject.class.getDeclaredMethod("div", double.class, double.class);
                var outputField = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("outputs");
                outputField.setAccessible(true);
                var snapshots = (java.util.Map<String, byte[]>) outputField.get(null);
                var callerNode = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(snapshots.get("love/crysh/exp/Caller")).accept(callerNode, 0);
                var run = callerNode.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
                int casts = 0;
                boolean adapted = false;
                for (var instruction : run.instructions) {
                    if (instruction.getOpcode() == org.objectweb.asm.Opcodes.I2D) casts++;
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("div")) {
                        if (!call.desc.equals("(DD)I")) throw new AssertionError(call.desc);
                        adapted = true;
                    }
                }
                if (casts != 2 || !adapted) throw new AssertionError("Missing call-site conversions");
                System.out.println("BYTECODE_VERIFIED");
                try {
                    Subject.class.getDeclaredMethod("div", int.class, int.class);
                    throw new AssertionError("Old descriptor survived");
                } catch (NoSuchMethodException expected) { }
                System.out.println("INTRODUCED=" + Subject.class.getMethod("introduced").invoke(new Subject()));
                System.out.println("ROOT_LOADER=" + love.crysh.mixinta.TypedOverwrite.class.getClassLoader().getName());
                try (var isolated = new java.net.URLClassLoader(
                        new java.net.URL[]{Subject.class.getProtectionDomain().getCodeSource().getLocation()},
                        ClassLoader.getPlatformClassLoader())) {
                    Class<?> separate = isolated.loadClass("love.crysh.exp.Subject");
                    System.out.println("ISOLATED=" + separate.getMethod("div", int.class, int.class).invoke(null, 10, 4));
                }
                """.formatted(order.equals("caller") ? "love.crysh.exp.Caller" : SUBJECT));
        String mixin = mixin("SubjectMixin", 1000, TYPED + " public static int div(double a,double b){return (int)(a/b*10);}");
        String added = mixin("calls.SubjectMixin", 900, """
                @org.spongepowered.asm.mixin.Shadow
                public static int div(int a,int b){throw new AssertionError();}
                public int introduced(){return div(10,4);}
                """);
        if (order.equals("caller")) {
            String target = app.remove(SUBJECT);
            app.put(SUBJECT, target);
        }
        Result result = launch(app, Map.of("SubjectMixin", mixin, "calls.SubjectMixin", added), List.of());
        result.success();
        result.contains("RESULT=25", "INTRODUCED=25", "ROOT_LOADER=knot", "ISOLATED=2", "BYTECODE_VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"private", "protected", ""})
    void preservesStaticVisibility(String visibility) throws Exception {
        Map<String, String> app = application(visibility + " static int div(int a,int b){return a/b;}", """
                var method = Subject.class.getDeclaredMethod("div", double.class, double.class);
                method.setAccessible(true);
                System.out.println("RESULT=" + method.invoke(null,10d,4d));
                System.out.println("ACCESS=" + java.lang.reflect.Modifier.toString(method.getModifiers()));
                """);
        app.remove("love.crysh.exp.Caller");
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                TYPED + " " + visibility + " static int div(double a,double b){return (int)(a/b*10);}")), List.of());
        result.success();
        result.contains("RESULT=25", "ACCESS=" + (visibility.isEmpty() ? "" : visibility + " ") + "static");
    }

    @Test
    void migratesInstanceCallsAndPreservesReceiver() throws Exception {
        Map<String, String> app = application("public int div(int a,int b){return a/b;}",
                "System.out.println(\"RESULT=\" + Caller.run());");
        app.put("love.crysh.exp.Caller", """
                package love.crysh.exp;
                public class Caller {
                    private static int evaluations;
                    private static Subject receiver(){evaluations++;return new Subject();}
                    public static int run(){
                        int result=receiver().div(10,4);
                        if(evaluations!=1)throw new AssertionError("Receiver evaluated twice");
                        return result;
                    }
                }
                """);
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                TYPED + " public int div(double a,double b){return (int)(a/b*10);}")), List.of());
        result.success();
        result.contains("RESULT=25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "queue", "list", "map", "aliases"})
    void migratesReferenceRepresentationThroughRuntime(String scenario) throws Exception {
        String operation = switch (scenario) {
            case "queue" -> """
                    java.util.Deque<Long> queue = new java.util.ArrayDeque<>();
                    queue.offerFirst(new BlockPos(12).asLong());
                    if (queue.isEmpty() || queue.size() != 1) throw new AssertionError();
                    return lookup(queue.removeLast());
                    """;
            case "list" -> """
                    java.util.List<Long> list = new java.util.ArrayList<>();
                    list.add(new BlockPos(12).asLong());
                    long old = list.set(0, new BlockPos(3).asLong());
                    if (lookup(list.get(0)) != 3) throw new AssertionError();
                    list.clear();
                    if (!list.isEmpty()) throw new AssertionError();
                    return lookup(old);
                    """;
            case "map" -> """
                    java.util.Map<Long, Long> map = new java.util.HashMap<>();
                    Long value = new BlockPos(12).asLong();
                    map.put(value, value);
                    if (!map.containsKey(value) || !map.containsValue(value)) throw new AssertionError();
                    int result = lookup(map.get(value));
                    map.remove(value);
                    if (!map.isEmpty()) throw new AssertionError();
                    return result;
                    """;
            case "aliases" -> """
                    java.util.Queue<Long> first = new java.util.ArrayDeque<>();
                    java.util.Queue<Long> second = new java.util.LinkedList<>();
                    first.add(new BlockPos(12).asLong());
                    second.add(new BlockPos(12).asLong());
                    Object alias = first;
                    for (int index = 0; index < 3; index++) alias = alias == first ? second : first;
                    if (!(alias instanceof java.util.Queue)) throw new AssertionError();
                    synchronized (alias) {
                        return lookup(((java.util.Queue<Long>) alias).poll());
                    }
                    """;
            default -> "return lookup(new BlockPos(12).asLong());";
        };
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put("love.crysh.exp.Subject", "package love.crysh.exp; public final class Subject { public static int lookup(long value){return (int)value;} public static int run(){" + operation + "} }");
        app.put("love.crysh.exp.Caller", "package love.crysh.exp; public class Caller {public static int run(){return Subject.run();}}");
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        System.out.println("RESULT=" + Caller.run());
                        Subject.class.getDeclaredMethod("lookup", BlockPos.class);
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("outputs");
                        field.setAccessible(true);
                        var outputs = (java.util.Map<String, byte[]>) field.get(null);
                        var node = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(outputs.get("love/crysh/exp/Subject")).accept(node, 0);
                        var run = node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
                        boolean mapScenario = %s;
                        for (var instruction : run.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.owner.equals("java/lang/Long")
                                    && (call.name.equals("longValue") || !mapScenario)) {
                                throw new AssertionError(call.name);
                            }
                        }
                        if (mapScenario && node.methods.stream().noneMatch(method -> method.name.startsWith("mixinta$containsValue$"))) {
                            throw new AssertionError("Missing value comparison helper");
                        }
                        System.out.println("REPRESENTATION_BYTECODE_VERIFIED");
                    }
                }
                """.formatted(scenario.equals("map")));
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, REFERENCE_MIXIN)), List.of());
        result.success();
        result.contains("RESULT=12", "REPRESENTATION_BYTECODE_VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "array", "call", "capture"})
    void rejectsUncoveredContainerEffectsBeforeBusinessStarts(String scenario) throws Exception {
        String operation = switch (scenario) {
            case "field" -> "saved = queue;";
            case "array" -> "Object[] array = new Object[1]; array[0] = queue;";
            case "call" -> "expose(queue, queue);";
            default -> "Runnable task = () -> queue.clear();";
        };
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put("love.crysh.exp.Subject", """
                package love.crysh.exp;
                public final class Subject {
                    public static Object saved;
                    public static void expose(Object first, Object second) {}
                    public static int lookup(long value) { return (int)value; }
                    public static int run() {
                        java.util.Queue<Long> queue = new java.util.ArrayDeque<>();
                        queue.add(new BlockPos(12).asLong());
                        %s
                        return lookup(queue.poll());
                    }
                }
                """.formatted(operation));
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("BUSINESS_EXECUTED");
                        System.out.println(Subject.run());
                    }
                }
                """);
        List<String> options = scenario.equals("call") ? List.of("-Dmixinta.diagnostics=verbose") : List.of();
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, REFERENCE_MIXIN)), options);
        result.failure(scenario.equals("call") ? "UNKNOWN_CONTAINER_USE" : "ESCAPED_CONTAINER",
                "channels=[0]", "allocation=java/util/ArrayDeque", "lookup", "poll");
        if (scenario.equals("call")) {
            result.contains("2 unresolved constraints", "argument=0", "argument=1", "escape=UNKNOWN");
            result.contains("Callee summary love/crysh/exp/Subject.expose", "body=true", "effects=0", "composition=unresolved");
        } else {
            result.contains("escape=ESCAPED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "native"})
    void sameDescriptorUsesNativeOverwritePriority(String kind) throws Exception {
        String annotation = kind.equals("typed") ? TYPED : "@org.spongepowered.asm.mixin.Overwrite";
        Result result = launch(application("public static int div(int a,int b){return a/b;}",
                "System.out.println(\"RESULT=\" + Caller.run());"), Map.of(
                "high.SubjectMixin", mixin("high.SubjectMixin", 2000, annotation + " public static int div(int a,int b){return 77;}"),
                "low.SubjectMixin", mixin("low.SubjectMixin", 1000, annotation + " public static int div(int a,int b){return 88;}")), List.of());
        result.success();
        result.contains("RESULT=77");
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "native"})
    void sameDescriptorUsesNativeFinalProtection(String kind) throws Exception {
        String annotation = kind.equals("typed") ? TYPED : "@org.spongepowered.asm.mixin.Overwrite";
        Result result = launch(application("@org.spongepowered.asm.mixin.Final public static int div(int a,int b){return a/b;}",
                "System.out.println(\"RESULT=\" + Caller.run());"), Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                annotation + " public static int div(int a,int b){return 77;}")), List.of());
        result.success();
        result.contains("RESULT=2", "Overwrite prohibited for @Final");
    }

    @Test
    void aggregatesHandlesAcrossCallersBeforeBusinessStarts() throws Exception {
        Map<String, String> app = application("public static int div(int a,int b){return a/b;}", "");
        app.put("love.crysh.exp.Caller", "package love.crysh.exp; public class Caller {public static Object ref(){return (java.util.function.IntBinaryOperator)Subject::div;}}");
        app.put("love.crysh.exp.OtherCaller", "package love.crysh.exp; public class OtherCaller {public static Object ref(){return (java.util.function.IntBinaryOperator)Subject::div;}}");
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                TYPED + " public static int div(double a,double b){return (int)(a/b);}")), List.of());
        result.failure("2 unresolved constraints", "METHOD_HANDLE_NOT_IMPLEMENTED",
                "Caller.ref", "OtherCaller.ref");
    }

    @Test
    void aggregatesPreparationErrorsAndPropagatesIgnoredPluginFailure() throws Exception {
        Result result = launch(application("public static int div(int a,int b){return a/b;}", ""),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                        TYPED + " public static double div(boolean a,double b){return 1;}")), List.of());
        result.failure("RETURN_TYPE_NOT_IMPLEMENTED", "PARAMETER_CONVERSION_NOT_IMPLEMENTED");
    }

    @Test
    void rejectsDuplicateClaims() throws Exception {
        String body = TYPED + " public static int div(double a,double b){return 1;}";
        Result result = launch(application("public static int div(int a,int b){return a/b;}", ""),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000, body), "other.SubjectMixin", mixin("other.SubjectMixin", 900, body)), List.of());
        result.failure("DUPLICATE_TARGET", "SIGNATURE_COLLISION");
    }

    @Test
    void parameterMigrationRejectsFinal() throws Exception {
        Result result = launch(application("@org.spongepowered.asm.mixin.Final public static int div(int a,int b){return a/b;}", ""),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                        TYPED + " public static int div(double a,double b){return 1;}")), List.of());
        result.failure("FINAL_TARGET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"overwrite", "inject"})
    void detectsChangesToOriginalMethod(String operation) throws Exception {
        String conflict = operation.equals("overwrite")
                ? "@org.spongepowered.asm.mixin.Overwrite public static int div(int a,int b){return 9;}"
                : """
                    @org.spongepowered.asm.mixin.injection.Inject(method="div(II)I",at=@org.spongepowered.asm.mixin.injection.At("HEAD"))
                    private static void before(int a,int b,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> callback){}
                    """;
        Result result = launch(application("public static int div(int a,int b){return a/b;}", ""), Map.of(
                "SubjectMixin", mixin("SubjectMixin", 1000, TYPED + " public static int div(double a,double b){return 1;}"),
                "conflict.SubjectMixin", mixin("conflict.SubjectMixin", 900, conflict)), List.of());
        result.failure("TARGET_CHANGED_AFTER_PREPARATION");
    }

    @Test
    void agentAttachFailureStopsStartup() throws Exception {
        Result result = launch(application("public static int div(int a,int b){return a/b;}", ""),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                        TYPED + " public static int div(double a,double b){return 1;}")), List.of("-XX:-EnableDynamicAgentLoading"));
        result.failure("MixinTA agent attachment failed");
    }

    @Test
    void earlyDefinitionFailsEvenWhenClassFormatErrorIsCaught() throws Exception {
        String bootstrap = """
                package fixture;
                public class Bootstrap implements net.fabricmc.api.ModInitializer {
                    public void onInitialize() {
                        try {
                            Class.forName("love.crysh.exp.Subject", false, getClass().getClassLoader());
                            throw new AssertionError("Early definition succeeded");
                        } catch (ClassFormatError expected) {
                            System.out.println("EARLY_DEFINITION_REJECTED");
                        } catch (ClassNotFoundException failure) {
                            throw new AssertionError(failure);
                        }
                        love.crysh.mixinta.runtime.TypedOverwriteRuntime.initialize();
                    }
                }
                """;
        Result result = launch(application("public static int div(int a,int b){return a/b;}", ""),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                        TYPED + " public static int div(double a,double b){return 1;}")), List.of(),
                Map.of("fixture.Bootstrap", bootstrap));
        result.failure("MixinTA class loaded before batch initialization", "EARLY_DEFINITION_REJECTED");
    }

    @Test
    void cachesMixinResultsAndInvokesPluginsOnce() throws Exception {
        String plugin = """
                package fixture;
                public class Plugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {
                    public static int applications;
                    public void onLoad(String name){love.crysh.mixinta.runtime.TypedOverwriteRuntime.prepare();}
                    public String getRefMapperConfig(){return null;}
                    public boolean shouldApplyMixin(String target,String mixin){return true;}
                    public void acceptTargets(java.util.Set<String> own,java.util.Set<String> other){}
                    public java.util.List<String> getMixins(){return null;}
                    public void preApply(String name,org.objectweb.asm.tree.ClassNode node,String mixin,
                            org.spongepowered.asm.mixin.extensibility.IMixinInfo info){}
                    public void postApply(String name,org.objectweb.asm.tree.ClassNode node,String mixin,
                            org.spongepowered.asm.mixin.extensibility.IMixinInfo info){
                        var method=node.methods.stream().filter(value->value.name.equals("div") && value.desc.equals("(DD)I")).findFirst().orElseThrow();
                        applications++;
                        for(int index=0;index<applications;index++) method.instructions.insert(new org.objectweb.asm.tree.InsnNode(0));
                    }
                }
                """;
        String bootstrap = """
                package fixture;
                public class Bootstrap implements net.fabricmc.api.ModInitializer {
                    public void onInitialize() {
                        love.crysh.mixinta.runtime.TypedOverwriteRuntime.initialize();
                        try {Class.forName("love.crysh.exp.Subject", false, getClass().getClassLoader());}
                        catch (ClassNotFoundException failure){throw new AssertionError(failure);}
                        System.out.println("APPLICATIONS=" + Plugin.applications);
                    }
                }
                """;
        Result result = launch(application("public static int div(int a,int b){return a/b;}",
                "System.out.println(\"RESULT=\" + Caller.run());"),
                Map.of("SubjectMixin", mixin("SubjectMixin", 1000,
                        TYPED + " public static int div(double a,double b){return 1;}")), List.of(),
                Map.of("fixture.Plugin", plugin, "fixture.Bootstrap", bootstrap));
        result.success();
        result.contains("APPLICATIONS=1", "RESULT=1");
    }

    private static Map<String, String> application(String method, String main) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("love.crysh.exp.Subject", "package love.crysh.exp; public final class Subject {" + method + "}");
        sources.put("love.crysh.exp.Caller", "package love.crysh.exp; public class Caller {public static int run(){return Subject.div(10,4);}}");
        sources.put("love.crysh.exp.Main", "package love.crysh.exp; public final class Main {public static void main(String[] args) throws Exception {"
                + "System.out.println(\"BUSINESS_EXECUTED\");" + main + "}}");
        return sources;
    }

    private static String mixin(String name, int priority, String body) {
        String qualifiedName = MIXIN_PACKAGE + "." + name;
        int separator = qualifiedName.lastIndexOf('.');
        return "package " + qualifiedName.substring(0, separator) + "; @org.spongepowered.asm.mixin.Mixin(targets=\"" + SUBJECT
                + "\", remap=false, priority=" + priority + ") public class " + qualifiedName.substring(separator + 1) + " {" + body + "}";
    }

    private Result launch(Map<String, String> app, Map<String, String> mixins, List<String> vmOptions) throws Exception {
        return launch(app, mixins, vmOptions, Map.of());
    }

    private Result launch(Map<String, String> app, Map<String, String> mixins, List<String> vmOptions,
            Map<String, String> helpers) throws Exception {
        Path appClasses = compile("app", app, List.of());
        Map<String, String> mixinSources = new LinkedHashMap<>();
        mixins.forEach((name, source) -> mixinSources.put(MIXIN_PACKAGE + "." + name, source));
        mixinSources.putAll(helpers);
        Path modClasses = compile("mod", mixinSources, List.of(appClasses));
        Path appJar = temporary.resolve("app.jar");
        jar(appJar, classEntries(appClasses));
        Path run = Files.createDirectories(temporary.resolve("run"));
        Path mods = Files.createDirectories(run.resolve("mods"));
        Map<String, byte[]> modEntries = classEntries(modClasses);
        String nested = "META-INF/jars/mixin-type-analysis-0.1.0-Snapshot.jar";
        String defaultEntrypoint = "love.crysh.exp.fabric.testmod.TestMod";
        String entrypoint = helpers.containsKey("fixture.Bootstrap") ? "fixture.Bootstrap" : defaultEntrypoint;
        try (JarFile packaged = new JarFile(PROJECT.resolve("target/exp-fabric-testmod-0.1.0-Snapshot.jar").toFile())) {
            for (String resource : List.of(nested, defaultEntrypoint.replace('.', '/') + ".class")) {
                try (var input = packaged.getInputStream(packaged.getJarEntry(resource))) {
                    modEntries.put(resource, input.readAllBytes());
                }
            }
        }
        String metadata = """
                {"schemaVersion":1,"id":"integration_fixture","version":"1.0.0","environment":"*",
                 "entrypoints":{"main":["%s"]},"mixins":["fixture.mixins.json"],
                 "depends":{"mixinta":"*"},"jars":[{"file":"%s"}],
                 "custom":{"mixinta":{"classes":[%s],"mixins":[%s]}}}
                """.formatted(entrypoint, nested, strings(app.keySet().stream().toList()),
                strings(mixins.keySet().stream().map(name -> MIXIN_PACKAGE + "." + name).toList()));
        modEntries.put("fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
        modEntries.put("fixture.mixins.json", ("""
                {"required":true,"package":"fixture.mixin","compatibilityLevel":"JAVA_25",
                 "plugin":"%s","mixins":[%s],"injectors":{"defaultRequire":1}}
                """).formatted(helpers.containsKey("fixture.Plugin") ? "fixture.Plugin" : "love.crysh.mixinta.runtime.TypedOverwritePlugin",
                strings(mixins.keySet().stream().toList())).getBytes(StandardCharsets.UTF_8));
        jar(mods.resolve("fixture.jar"), modEntries);
        Path bridge = PROJECT.getParent().resolve("exp-fabric/target");
        assertTrue(Files.isRegularFile(bridge.resolve("exp-fabric-0.1.0-Snapshot.jar")), "Build exp-fabric before verify");
        List<String> command = new ArrayList<>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-Djava.awt.headless=true");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.addAll(vmOptions);
        command.add("-Dexp.fabric.jar=" + appJar);
        command.add("-Dexp.fabric.runDir=" + run);
        command.add("-cp");
        command.add(bridge.resolve("exp-fabric-0.1.0-Snapshot.jar") + File.pathSeparator + bridge.resolve("lib") + "/*");
        command.add("love.crysh.exp.fabric.Launcher");
        Path log = temporary.resolve("child.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Child JVM timeout, log at " + log);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        return new Result(process.exitValue(), Files.readString(log));
    }

    private Path compile(String directory, Map<String, String> sources, List<Path> extraClasspath) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(directory));
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString(), "-classpath"));
        String extras = extraClasspath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        arguments.add(System.getProperty("java.class.path") + File.pathSeparator + extras);
        for (var entry : sources.entrySet()) {
            Path source = root.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return classes;
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

        void contains(String... messages) {
            for (String message : messages) {
                assertTrue(log.contains(message), "Missing " + message + "\n" + log);
            }
        }

        void failure(String... messages) {
            assertNotEquals(0, exitCode, log);
            assertFalse(log.contains("BUSINESS_EXECUTED"), log);
            contains(messages);
        }
    }
}
