package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * 一个 Knot ClassLoader 对应一个显式登记的封闭类集合。
 * initialize 必须先于集合中任意类的定义，结果整批发布，之后按加载顺序提供快照。
 */
public final class TypedOverwriteRuntime {
    private static final ClassLoader LOADER = TypedOverwriteRuntime.class.getClassLoader();
    private static final Map<String, byte[]> ORIGINALS = new LinkedHashMap<>();
    private static volatile Map<String, byte[]> outputs;
    private static volatile Map<String, byte[]> afterMixins;
    private static PreparedOverwrites prepared;
    private static volatile Throwable failure;
    private static boolean attempted;

    private TypedOverwriteRuntime() {
    }

    public static synchronized void prepare() {
        if (attempted) {
            checkFailure();
            return;
        }
        attempted = true;
        try {
            Map<String, byte[]> mixins = new LinkedHashMap<>();
            for (var mod : FabricLoader.getInstance().getAllMods()) {
                CustomValue metadata = mod.getMetadata().getCustomValue("mixinta");
                if (metadata != null) {
                    readClasses(metadata.getAsObject().get("classes"), ORIGINALS);
                    readClasses(metadata.getAsObject().get("mixins"), mixins);
                }
            }
            prepared = new TypedOverwriteAnalyzer(LOADER).prepare(ORIGINALS, mixins);
            Map<String, byte[]> preparedMixins = prepared.mixins();
            Class<?> agent = attachAgent();
            UnaryOperator<Object> preparation = value -> {
                ClassNode original = (ClassNode) value;
                byte[] replacement = preparedMixins.get(original.name);
                if (replacement == null) {
                    return value;
                }
                ClassNode node = new ClassNode();
                new ClassReader(replacement).accept(node, 0);
                return node;
            };
            BiFunction<ClassLoader, Object[], byte[]> publication = TypedOverwriteRuntime::publishedClass;
            BiFunction<String, byte[], byte[]> cache = TypedOverwriteRuntime::cachedMixinResult;
            agent.getMethod("connect", UnaryOperator.class, BiFunction.class, BiFunction.class)
                    .invoke(null, preparation, publication, cache);
        } catch (Throwable cause) {
            failure = cause;
            checkFailure();
        }
    }

    public static synchronized void initialize() {
        if (!attempted) {
            boolean registered = FabricLoader.getInstance().getAllMods().stream()
                    .anyMatch(mod -> mod.getMetadata().getCustomValue("mixinta") != null);
            if (registered) {
                throw new IllegalStateException("mixinta metadata requires TypedOverwritePlugin during Mixin config preparation");
            }
            return;
        }
        checkFailure();
        if (outputs != null) {
            return;
        }
        try {
            IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            afterMixins = new LinkedHashMap<>();
            for (var entry : ORIGINALS.entrySet()) {
                String name = entry.getKey().replace('/', '.');
                afterMixins.put(entry.getKey(), transformer.transformClassBytes(name, name, entry.getValue().clone()));
            }
            Map<String, byte[]> transformed = prepared.analyze(afterMixins).apply();
            checkFailure();
            outputs = transformed;
        } catch (Throwable cause) {
            failure = cause;
            checkFailure();
        }
    }

    private static byte[] publishedClass(ClassLoader loader, Object[] arguments) {
        String name = (String) arguments[0];
        if (loader != LOADER || !ORIGINALS.containsKey(name)) {
            return null;
        }
        checkFailure();
        if (outputs == null) {
            failure = new IllegalStateException("MixinTA class loaded before batch initialization: " + name);
            checkFailure();
        }
        if (!Arrays.equals(afterMixins.get(name), (byte[]) arguments[1])) {
            failure = new IllegalStateException("MixinTA post-Mixin snapshot changed before definition: " + name);
            checkFailure();
        }
        return outputs.get(name).clone();
    }

    private static byte[] cachedMixinResult(String binaryName, byte[] input) {
        if (binaryName == null || outputs == null) {
            return null;
        }
        String name = binaryName.replace('.', '/');
        if (!ORIGINALS.containsKey(name)) {
            return null;
        }
        checkFailure();
        if (!Arrays.equals(ORIGINALS.get(name), input)) {
            failure = new IllegalStateException("MixinTA input changed after batch preparation: " + name);
            checkFailure();
        }
        // 复用真实 Mixin 的结果，使 config plugin 与 extension 对每个登记类只执行一次。
        return afterMixins.get(name).clone();
    }

    private static void readClasses(CustomValue value, Map<String, byte[]> destination) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("mixinta metadata requires classes and mixins arrays");
        }
        for (CustomValue item : value.getAsArray()) {
            String name = item.getAsString().replace('.', '/');
            try (var input = LOADER.getResourceAsStream(name + ".class")) {
                if (input == null) {
                    throw new IOException("Missing registered class " + name);
                }
                destination.put(name, input.readAllBytes());
            }
        }
    }

    private static Class<?> attachAgent() throws Exception {
        Path jar = Path.of(TypedOverwriteRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("MixinTA must be loaded from a packaged JAR: " + jar);
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--add-modules=jdk.attach", "-cp", jar.toString(),
                "love.crysh.mixinta.runtime.agent.AgentAttacher", Long.toString(ProcessHandle.current().pid()), jar.toString())
                .inheritIO().start();
        try {
            if (!helper.waitFor(30, TimeUnit.SECONDS)) {
                helper.destroyForcibly();
                throw new IllegalStateException("MixinTA agent attachment timed out");
            }
            if (helper.exitValue() != 0) {
                throw new IllegalStateException("MixinTA agent attachment failed: " + helper.exitValue());
            }
        } catch (InterruptedException interrupted) {
            helper.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        return Class.forName("love.crysh.mixinta.runtime.agent.RuntimeAgent", true, ClassLoader.getSystemClassLoader());
    }

    private static void checkFailure() {
        if (failure != null) {
            throw new IllegalStateException("MixinTA batch initialization failed", failure);
        }
    }
}
