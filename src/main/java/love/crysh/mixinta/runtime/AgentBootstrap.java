package love.crysh.mixinta.runtime;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;

/** 按附加、共享 API 核对、回调连接、支持类预加载的顺序完成引导。 */
final class AgentBootstrap {
    static final class Connection {
        private final Method loadedClasses;

        private Connection(Class<?> agent) throws NoSuchMethodException {
            loadedClasses = agent.getMethod("loadedClasses", ClassLoader.class);
        }

        Class<?>[] loadedClasses(ClassLoader loader) throws ReflectiveOperationException {
            return (Class<?>[]) loadedClasses.invoke(null, loader);
        }
    }

    static Connection connect(ClassLoader loader, UnaryOperator<Object> preparation,
            BiFunction<ClassLoader, Object[], byte[]> publication, BiFunction<Object[], byte[], byte[]> cache,
            BiFunction<String, String, IllegalStateException> failure) throws Exception {
        Path jar = Path.of(AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Class<?> agent = attachAgent(jar);
        if (Class.forName(ClassNode.class.getName(), false, agent.getClassLoader()) != ClassNode.class
                || Class.forName(MixinEnvironment.class.getName(), false, agent.getClassLoader()) != MixinEnvironment.class) {
            throw failure.apply("UNSHARED_TRANSFORMER_API", "ASM and Mixin must resolve to the same API classes");
        }
        agent.getMethod("connect", UnaryOperator.class, BiFunction.class, BiFunction.class)
                .invoke(null, preparation, publication, cache);
        preloadSupport(jar, loader);
        return new Connection(agent);
    }

    private static void preloadSupport(Path jar, ClassLoader loader) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            for (var entry : archive.stream().filter(value -> value.getName().endsWith(".class")
                    && value.getName().startsWith("love/crysh/mixinta/")
                    && !value.getName().startsWith("love/crysh/mixinta/runtime/agent/")).toList()) {
                Class.forName(entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.'), false, loader);
            }
        }
    }

    private static Class<?> attachAgent(Path jar) throws Exception {
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("MixinTA must be loaded from a packaged JAR: " + jar);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--add-modules=jdk.attach", "-cp", jar.toString(), "love.crysh.mixinta.runtime.agent.AgentAttacher",
                Long.toString(ProcessHandle.current().pid()), jar.toString()).inheritIO().start();
        try {
            if (!helper.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("MixinTA agent attachment timed out");
            if (helper.exitValue() != 0) throw new IllegalStateException("MixinTA agent attachment failed: " + helper.exitValue());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
        }
        return Class.forName("love.crysh.mixinta.runtime.agent.RuntimeAgent", true, ClassLoader.getSystemClassLoader());
    }
}
