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
    private static final String TYPED = "@love.crysh.mixinta.anno.TypedOverwrite(method=\"div(II)I\")";
    private static final String REFERENCE_MIXIN = """
            @love.crysh.mixinta.anno.TypedOverwrite(method="lookup(J)I", parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=SubjectMixin.class))
            public static int lookup(love.crysh.exp.BlockPos value){return (int)value.value();}
            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.SOURCE, method="love.crysh.exp.BlockPos.asLong()J")
            private static love.crysh.exp.BlockPos keep(love.crysh.exp.BlockPos value){return value;}
            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.ENCODE)
            private static long encode(love.crysh.exp.BlockPos value){return value.value();}
            @love.crysh.mixinta.anno.TypeRule(value=love.crysh.mixinta.anno.TypeRule.Kind.READ)
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
                var outputField = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                outputField.setAccessible(true);
                var snapshotHolder = outputField.get(null);
                outputField = snapshotHolder.getClass().getDeclaredField("outputs");
                outputField.setAccessible(true);
                var snapshots = (java.util.Map<String, byte[]>) outputField.get(snapshotHolder);
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
                System.out.println("ROOT_LOADER=" + love.crysh.mixinta.anno.TypedOverwrite.class.getClassLoader().getName());
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
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                        field.setAccessible(true);
                        var snapshotHolder = field.get(null);
                        field = snapshotHolder.getClass().getDeclaredField("outputs");
                        field.setAccessible(true);
                        var outputs = (java.util.Map<String, byte[]>) field.get(snapshotHolder);
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
    @ValueSource(strings = {"factory", "dispatch"})
    void composesContainerSummariesAcrossClassesThroughRuntime(String scenario) throws Exception {
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put(SUBJECT, """
                package love.crysh.exp;
                public final class Subject {
                    public static int lookup(long value) { return (int)value; }
                    public static int run(boolean first) { return other.Worker.run(first); }
                }
                """);
        app.put("other.Worker", """
                package other;
                import java.util.Queue;
                import love.crysh.exp.BlockPos;
                import love.crysh.exp.Subject;
                public final class Worker {
                    public static Queue<Long> create(int depth) {
                        return depth == 0 ? new java.util.ArrayDeque<>() : create(depth - 1);
                    }
                    public static Queue<Long> identity(Queue<Long> queue, int depth) {
                        return depth == 0 ? queue : identity(queue, depth - 1);
                    }
                    public static void write(Queue<Long> queue, BlockPos value) { queue.add(value.asLong()); }
                    public static long legacy(Queue<Long> queue) { return queue.peek(); }
                    public static int read(Queue<Long> queue) { return Subject.lookup(queue.poll()); }
                    public static int run(boolean first) {
                        Queue<Long> queue = create(3);
                        write(queue, new BlockPos(12));
                        if (legacy(queue) != 12) throw new AssertionError("Legacy read changed");
                        %s
                    }
                }
                """.formatted(scenario.equals("factory")
                ? "return read(identity(queue, 4));"
                : "Reader reader = first ? new First() : new Second(); return reader.read(identity(queue, 4));"));
        if (scenario.equals("dispatch")) {
            app.put("other.Reader", "package other; public interface Reader { int read(java.util.Queue<Long> queue); }");
            app.put("other.First", "package other; public class First implements Reader { public int read(java.util.Queue<Long> queue) { return Worker.read(queue); } }");
            app.put("other.Second", "package other; public class Second implements Reader { public int read(java.util.Queue<Long> queue) { return Worker.read(queue); } }");
        }
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        if (Subject.run(true) != 12 || Subject.run(false) != 12) throw new AssertionError();
                        Subject.class.getDeclaredMethod("lookup", BlockPos.class);
                        other.Worker.class.getDeclaredMethod("create", int.class);
                        if (other.Worker.class.getDeclaredMethod("legacy", java.util.Queue.class).getReturnType() != long.class) {
                            throw new AssertionError("Legacy descriptor changed");
                        }
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                        field.setAccessible(true);
                        var snapshotHolder = field.get(null);
                        field = snapshotHolder.getClass().getDeclaredField("outputs");
                        field.setAccessible(true);
                        var outputs = (java.util.Map<String, byte[]>) field.get(snapshotHolder);
                        var worker = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(outputs.get("other/Worker")).accept(worker, 0);
                        var bridges = new java.util.HashSet<String>();
                        for (var method : worker.methods) {
                            for (var instruction : method.instructions) {
                                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                        && call.owner.equals("love/crysh/exp/Subject") && call.name.startsWith("mixinta$rule$")) {
                                    bridges.add(call.name);
                                }
                                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                        && call.owner.equals("java/lang/Long")) throw new AssertionError(call.name);
                            }
                        }
                        if (bridges.size() != 3) throw new AssertionError("Missing rule bridges " + bridges);
                        var subject = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(outputs.get("love/crysh/exp/Subject")).accept(subject, 0);
                        for (var method : subject.methods) {
                            if (bridges.contains(method.name) && (method.access & 0x1009) != 0x1009) {
                                throw new AssertionError("Inaccessible bridge " + method.name);
                            }
                        }
                        System.out.println("CALL_GRAPH_BYTECODE_VERIFIED");
                        System.out.println("RESULT=12");
                    }
                }
                """);
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, REFERENCE_MIXIN)), List.of());
        result.success();
        result.contains("RESULT=12", "CALL_GRAPH_BYTECODE_VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "values", "entries"})
    void migratesContainerViewsThroughRuntime(String scenario) throws Exception {
        String operation = switch (scenario) {
            case "list" -> """
                    java.util.List<Long> list = new java.util.ArrayList<>();
                    list.add(new BlockPos(12).asLong()); list.add(new BlockPos(3).asLong());
                    java.util.List<Long> view = list.subList(0, 2).reversed();
                    java.util.ListIterator<Long> it = view.listIterator();
                    it.next(); it.set(new BlockPos(7).asLong());
                    if (!view.contains(7L) || !view.remove(7L)) throw new AssertionError();
                    return read(view.iterator(), 3);
                    """;
            case "values" -> """
                    java.util.Map<Long, Long> map = new java.util.LinkedHashMap<>();
                    map.put(1L, new BlockPos(12).asLong()); map.put(2L, new BlockPos(3).asLong());
                    java.util.Collection<Long> values = map.values();
                    if (!values.contains(3L) || !values.remove(3L)) throw new AssertionError();
                    return read(values.iterator(), 3);
                    """;
            default -> """
                    java.util.Map<Long, Long> map = new java.util.LinkedHashMap<>();
                    map.put(1L, new BlockPos(3).asLong());
                    java.util.Map.Entry<Long, Long> entry = first(map.entrySet().iterator());
                    entry.setValue(new BlockPos(12).asLong());
                    if (entry.getKey() != 1L) throw new AssertionError();
                    return Subject.lookup(entry.getValue());
                    """;
        };
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put(SUBJECT, "package love.crysh.exp; public final class Subject { public static int lookup(long value){return (int)value;} }");
        String helpers = scenario.equals("entries") ? """
                public static java.util.Map.Entry<Long, Long> first(java.util.Iterator<java.util.Map.Entry<Long, Long>> it) { return it.next(); }
                """ : """
                public static int read(java.util.Iterator<Long> it, int depth) {
                    return depth == 0 ? Subject.lookup(it.next()) : read(it, depth - 1);
                }
                """;
        app.put("other.Worker", "package other; import love.crysh.exp.BlockPos; import love.crysh.exp.Subject; public final class Worker { public static int run(){"
                + operation + "}" + helpers + "}");
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        if (other.Worker.run() != 12) throw new AssertionError();
                        Subject.class.getDeclaredMethod("lookup", BlockPos.class);
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                        field.setAccessible(true);
                        var snapshotHolder = field.get(null);
                        field = snapshotHolder.getClass().getDeclaredField("outputs");
                        field.setAccessible(true);
                        var outputs = (java.util.Map<String, byte[]>) field.get(snapshotHolder);
                        var worker = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(outputs.get("other/Worker")).accept(worker, 0);
                        int unboxing = 0;
                        int sourceCalls = 0;
                        boolean adapted = false;
                        for (var method : worker.methods) {
                            for (var instruction : method.instructions) {
                                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                                    if (call.owner.equals("java/lang/Long") && call.name.equals("longValue")) unboxing++;
                                    if (call.owner.equals("love/crysh/exp/BlockPos") && call.name.equals("asLong")) sourceCalls++;
                                    if (call.name.equals("lookup") && call.desc.equals("(Llove/crysh/exp/BlockPos;)I")) adapted = true;
                                }
                            }
                        }
                        if (unboxing != %d || sourceCalls != 0 || !adapted) throw new AssertionError("Unadapted view operations");
                        System.out.println("VIEW_BYTECODE_VERIFIED");
                        System.out.println("RESULT=12");
                    }
                }
                """.formatted(scenario.equals("entries") ? 1 : 0));
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, REFERENCE_MIXIN)), List.of());
        result.success();
        result.contains("RESULT=12", "VIEW_BYTECODE_VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"values", "entry"})
    void rejectsEscapingContainerViewsBeforeBusinessStarts(String scenario) throws Exception {
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put(SUBJECT, """
                package love.crysh.exp;
                public final class Subject {
                    public static Object saved;
                    public static int lookup(long value){return (int)value;}
                    public static int run(){
                        java.util.Map<Long, Long> map = new java.util.HashMap<>();
                        map.put(1L, new BlockPos(12).asLong());
                        saved = %s;
                        return lookup(map.get(1L));
                    }
                }
                """.formatted(scenario.equals("values") ? "map.values().iterator()" : "map.entrySet().iterator().next()"));
        app.put("love.crysh.exp.Main", "package love.crysh.exp; public final class Main { public static void main(String[] args){System.out.println(\"BUSINESS_EXECUTED\");System.out.println(Subject.run());} }");
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, REFERENCE_MIXIN)), List.of());
        result.failure("ESCAPED_CONTAINER", "channels=[1]", "lookup", "HashMap");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mixin", "external", "invalid", "escape"})
    void usesCustomContainerContractsThroughRuntime(String scenario) throws Exception {
        String declarations = """
                @love.crysh.mixinta.anno.ContainerRule(method="love.crysh.exp.Box.<init>()V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.ALLOCATE)
                @love.crysh.mixinta.anno.ContainerRule(method="love.crysh.exp.Box.put(Ljava/lang/Object;)V", operation=love.crysh.mixinta.anno.ContainerRule.Operation.WRITE, parameter=%d)
                @love.crysh.mixinta.anno.ContainerRule(method="love.crysh.exp.Box.get(I)Ljava/lang/Object;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.READ)
                @love.crysh.mixinta.anno.ContainerRule(method="love.crysh.exp.Box.view()Ljava/util/List;", operation=love.crysh.mixinta.anno.ContainerRule.Operation.VIEW, view=love.crysh.mixinta.anno.ContainerRule.View.LIST)
                """.formatted(scenario.equals("invalid") ? 1 : 0);
        Map<String, String> app = new LinkedHashMap<>();
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value) { public long asLong(){return value;} }");
        app.put("love.crysh.exp.Box", """
                package love.crysh.exp;
                public final class Box<T> {
                    private final java.util.List<T> items = new java.util.ArrayList<>();
                    public Box() {}
                    public void put(T value) { items.add(value); }
                    public T get(int index) { return items.get(index); }
                    public java.util.List<T> view() { return items.subList(0, items.size()); }
                }
                """);
        app.put(SUBJECT, "package love.crysh.exp; public final class Subject { public static Object saved; public static int lookup(long value){return (int)value;} }");
        app.put("other.Worker", """
                package other;
                import love.crysh.exp.BlockPos;
                import love.crysh.exp.Box;
                import love.crysh.exp.Subject;
                public final class Worker {
                    public static Box<Long> create(int count) { return count == 0 ? new Box<>() : create(count - 1); }
                    public static int run() {
                        Box<Long> box = create(3);
                        box.put(new BlockPos(12).asLong());
                        java.util.List<Long> view = box.view();
                        %s
                        return Subject.lookup(view.iterator().next());
                    }
                }
                """.formatted(scenario.equals("escape") ? "Subject.saved = view;" : ""));
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        if (other.Worker.run() != 12) throw new AssertionError();
                        Subject.class.getDeclaredMethod("lookup", BlockPos.class);
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                        field.setAccessible(true);
                        var snapshotHolder = field.get(null);
                        field = snapshotHolder.getClass().getDeclaredField("outputs");
                        field.setAccessible(true);
                        var outputs = (java.util.Map<String, byte[]>) field.get(snapshotHolder);
                        var worker = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(outputs.get("other/Worker")).accept(worker, 0);
                        for (var method : worker.methods) {
                            for (var instruction : method.instructions) {
                                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                        && (call.owner.equals("java/lang/Long") || call.name.equals("asLong"))) {
                                    throw new AssertionError("Original element representation " + call.name);
                                }
                            }
                        }
                        System.out.println("CUSTOM_CONTAINER_BYTECODE_VERIFIED");
                        System.out.println("RESULT=12");
                    }
                }
                """);
        String source = mixin("SubjectMixin", 1000, REFERENCE_MIXIN);
        Map<String, String> helpers = Map.of();
        if (scenario.equals("external")) {
            source = source.replace("rules=SubjectMixin.class", "rules=fixture.Rules.class");
            String rules = "package fixture; " + declarations + " public class Rules {"
                    + REFERENCE_MIXIN.substring(REFERENCE_MIXIN.indexOf("@love.crysh.mixinta.anno.TypeRule"))
                    .replace("private static", "public static") + "}";
            helpers = Map.of("fixture.Rules", rules);
        } else {
            source = source.replace("public class SubjectMixin", declarations + " public class SubjectMixin");
        }
        Result result = launch(app, Map.of("SubjectMixin", source), List.of(), helpers);
        if (scenario.equals("invalid")) {
            result.failure("INVALID_CONTAINER_RULE", "Invalid erased element parameter");
        } else if (scenario.equals("escape")) {
            result.failure("ESCAPED_CONTAINER", "Box", "channels=[0]");
        } else {
            result.success();
            result.contains("RESULT=12", "CUSTOM_CONTAINER_BYTECODE_VERIFIED");
        }
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
                    public static native void expose(Object first, Object second);
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
            result.contains("Callee summary love/crysh/exp/Subject.expose", "body=false", "effects=0", "composition=unresolved");
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
    void ordinaryEntrypointsCanUseTargetsBeforeAnExplicitInitializeCall() throws Exception {
        String bootstrap = """
                package fixture;
                public class Bootstrap implements net.fabricmc.api.ModInitializer {
                    public void onInitialize() {
                        try {
                            Class.forName("love.crysh.exp.Subject", false, getClass().getClassLoader());
                            System.out.println("AUTOMATIC_INITIALIZATION_READY");
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
        result.success();
        result.contains("AUTOMATIC_INITIALIZATION_READY");
        assertFalse(result.log().contains("EARLY_DEFINITION_REJECTED"), result.log());
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

    @ParameterizedTest
    @ValueSource(strings = {"compatible", "independent", "conflict", "rules", "boxed", "primitive", "transfer", "missing", "null", "external", "unknown-merge"})
    void coordinatesRepresentationRequirementsThroughRealMixin(String scenario) throws Exception {
        boolean independent = scenario.equals("independent");
        boolean cross = List.of("boxed", "primitive", "transfer").contains(scenario);
        boolean compatible = scenario.equals("compatible") || cross || scenario.equals("missing")
                || scenario.equals("external") || scenario.equals("unknown-merge");
        String type = compatible || scenario.equals("rules") ? "BlockPos" : "OtherPos";
        String rules = """
                package fixture;
                import love.crysh.mixinta.anno.TypeRule;
                import love.crysh.exp.BlockPos;
                import love.crysh.exp.OtherPos;
                public class %s {
                    public static int sources;
                    @TypeRule(value=TypeRule.Kind.SOURCE, method="love.crysh.exp.BlockPos.asLong()J")
                    public static %s keep(BlockPos value) { sources++; return %s; }
                    @TypeRule(TypeRule.Kind.READ)
                    public static %s read(%s value) { return java.util.Objects.requireNonNull(value); }
                    @TypeRule(TypeRule.Kind.ENCODE)
                    public static long encode(%s value) { return value.value(); }
                }
                """;
        Map<String, String> helpers = Map.of(
                "fixture.RulesA", rules.formatted("RulesA", "BlockPos", "value", "BlockPos", "BlockPos", "BlockPos"),
                "fixture.RulesB", rules.formatted("RulesB", type, type.equals("BlockPos") ? "value" : "new OtherPos(value.value())",
                        type, type, type));
        String methods = """
                public static int first(long value) { return (int)value; }
                public static int second(long value) { return (int)value; }
                public static long legacy(long a, double b, long c) { return a + (long)b + c; }
                public static long run() {
                    java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                    java.util.Queue<Long> b = %s;
                    a.add(new BlockPos(12).asLong());
                    %s
                    long av=a.peek(); long bv=b.peek();
                    return first(av) + second(bv) + legacy(av, 4.0, bv);
                }
                """.formatted(independent ? "new java.util.ArrayDeque<>()" : "a",
                        independent ? "b.add(new BlockPos(3).asLong());" : "");
        String body = switch (scenario) {
            case "boxed", "primitive" -> """
                    %s value = new BlockPos(12).asLong();
                    java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                    java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                    a.add(value); b.add(value);
                    return first(a.peek()) + second(b.peek());
                    """.formatted(scenario.equals("boxed") ? "Long" : "long");
            case "transfer" -> """
                    java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                    java.util.Queue<Long> b = new java.util.ArrayDeque<>();
                    a.add(new BlockPos(12).asLong()); b.add(a.peek());
                    return first(a.peek()) + second(b.peek());
                    """;
            case "missing", "external" -> """
                    java.util.Queue<Long> a = new java.util.ArrayDeque<>();
                    a.add(%s); %s
                    return first(a.peek()) + second(a.peek());
                    """.formatted(scenario.equals("missing") ? "12L" : "new BlockPos(12).asLong()",
                            scenario.equals("external") ? "a.toArray();" : "");
            case "unknown-merge" -> """
                    java.util.Queue<Long> local = new java.util.ArrayDeque<>();
                    local.add(new BlockPos(12).asLong());
                    java.util.Queue<Long> merged = flag ? local : external;
                    return first(merged.peek()) + second(local.peek());
                    """;
            case "null" -> """
                    Long empty = null;
                    java.util.Queue<Long> a = new java.util.LinkedList<>();
                    java.util.Queue<Long> b = new java.util.LinkedList<>();
                    a.add(new BlockPos(12).asLong()); a.add(empty);
                    b.add(new BlockPos(3).asLong()); b.add(empty);
                    return first(a.peek()) + second(b.peek());
                    """;
            default -> null;
        };
        if (body != null) {
            String fields = scenario.equals("unknown-merge")
                    ? "public static boolean flag; public static java.util.Queue<Long> external;" : "";
            methods = methods.substring(0, methods.indexOf("public static long run()")) + fields
                    + "public static long run(){" + body + "}";
        }
        Map<String, String> app = new LinkedHashMap<>();
        app.put(SUBJECT, "package love.crysh.exp; public final class Subject {" + methods + "}");
        app.put("love.crysh.exp.BlockPos", "package love.crysh.exp; public record BlockPos(long value){public long asLong(){return value;}}");
        app.put("love.crysh.exp.OtherPos", "package love.crysh.exp; public record OtherPos(long value){}");
        app.put("love.crysh.exp.Main", """
                package love.crysh.exp;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BUSINESS_EXECUTED");
                        System.out.println("RESULT=" + Subject.run());
                        int sources = Class.forName("fixture.RulesA").getField("sources").getInt(null)
                                + Class.forName("fixture.RulesB").getField("sources").getInt(null);
                        System.out.println("SOURCES=" + sources);
                        Subject.class.getDeclaredMethod("first", BlockPos.class);
                        Subject.class.getDeclaredMethod("second", %s.class);
                        var field = love.crysh.mixinta.runtime.TypedOverwriteRuntime.class.getDeclaredField("result");
                        field.setAccessible(true);
                        var snapshotHolder = field.get(null);
                        field = snapshotHolder.getClass().getDeclaredField("outputs");
                        field.setAccessible(true);
                        var snapshots = (java.util.Map<String, byte[]>) field.get(snapshotHolder);
                        var node = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(snapshots.get("love/crysh/exp/Subject")).accept(node, 0);
                        var run = node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
                        for (var instruction : run.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.owner.equals("java/lang/Long")) throw new AssertionError("Boxing survived: " + call.name);
                        }
                        System.out.println("BATCH_BYTECODE_VERIFIED");
                    }
                }
                """.formatted(type));
        String declarations = """
                @love.crysh.mixinta.anno.TypedOverwrite(method="first(J)I",
                    parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=fixture.RulesA.class))
                public static int first(love.crysh.exp.BlockPos value){return (int)value.value();}
                @love.crysh.mixinta.anno.TypedOverwrite(method="second(J)I",
                    parameters=@love.crysh.mixinta.anno.ParameterMigration(parameter=0, rules=fixture.%s.class))
                public static int second(love.crysh.exp.%s value){return (int)value.value();}
                """.formatted(compatible ? "RulesA" : "RulesB", type);
        Result result = launch(app, Map.of("SubjectMixin", mixin("SubjectMixin", 1000, declarations)), List.of(), helpers);
        if (cross || scenario.equals("compatible") || independent || scenario.equals("null")) {
            result.success();
            int expected = cross ? 24 : scenario.equals("null") ? 15 : compatible ? 52 : 34;
            result.contains("RESULT=" + expected, "SOURCES=" + (compatible ? 1 : 2), "BATCH_BYTECODE_VERIFIED");
        } else {
            String code = switch (scenario) {
                case "conflict" -> "CONTAINER_FACT_CONFLICT";
                case "missing" -> "MISSING_PRODUCER_RULE";
                case "external" -> "UNKNOWN_CONTAINER_USE";
                case "unknown-merge" -> "UNRESOLVED_CONTAINER";
                default -> "REPRESENTATION_RULE_CONFLICT";
            };
            result.failure(code,
                    "Storage:", "Channel:", "Operation:", "Analysis failed. No transformations published.");
            if (scenario.equals("external")) result.contains("Operation: UNKNOWN_OPERATION");
            if (scenario.equals("unknown-merge")) result.contains("State: UNKNOWN", "Operation: READ");
            assertFalse(result.log().contains("OUTPUT_VERIFICATION_FAILED"), result.log());
        }
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
