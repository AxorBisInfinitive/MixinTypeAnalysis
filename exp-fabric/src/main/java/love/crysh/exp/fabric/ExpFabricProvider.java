package love.crysh.exp.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.Arguments;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ExpFabricProvider implements GameProvider {
    private static final String APP_MAIN = "love.crysh.exp.Main";
    private static final String DEFAULT_GAME_VERSION = "0.1.0-Snapshot";

    private final Arguments arguments = new Arguments();
    private final GameTransformer transformer = new GameTransformer();
    private Path gameJar;
    private Path runDirectory;
    private String gameVersion = DEFAULT_GAME_VERSION;

    @Override
    public String getGameId() {
        return "exp";
    }

    @Override
    public String getGameName() {
        return "Exp";
    }

    @Override
    public String getRawGameVersion() {
        return gameVersion;
    }

    @Override
    public String getNormalizedGameVersion() {
        return gameVersion;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        return Collections.emptyList();
    }

    @Override
    public String getEntrypoint() {
        return APP_MAIN;
    }

    @Override
    public Path getLaunchDirectory() {
        return runDirectory;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        return Set.of();
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        Path defaultRunDirectory = Path.of("exp-fabric-run").toAbsolutePath().normalize();
        String jarArgument = System.getProperty("exp.fabric.jar", "../exp/target/exp-0.1.0-Snapshot.jar");
        String runArgument = System.getProperty("exp.fabric.runDir", defaultRunDirectory.toString());
        gameJar = Path.of(jarArgument).toAbsolutePath().normalize();
        runDirectory = Path.of(runArgument).toAbsolutePath().normalize();
        arguments.parse(args);
        if (!Files.isRegularFile(gameJar)) {
            return false;
        }
        try {
            Files.createDirectories(runDirectory.resolve("mods"));
            return true;
        } catch (java.io.IOException failure) {
            throw new RuntimeException("无法创建 Exp 运行目录", failure);
        }
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        gameVersion = System.getProperty("exp.fabric.version", DEFAULT_GAME_VERSION);
        transformer.locateEntrypoints(launcher, List.of(gameJar));
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        FabricLauncherBase.getLauncher().addToClassPath(gameJar);
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return transformer;
    }

    @Override
    public void launch(ClassLoader loader) {
        try {
            System.getProperties().putIfAbsent("exp.data", runDirectory.resolve("data/exp").toString());
            FabricLoader.getInstance().invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);
            Class<?> mainClass = loader.loadClass(APP_MAIN);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) arguments.toArray());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new RuntimeException("Exp 启动失败", cause);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("无法调用 Exp main 方法", failure);
        } catch (Exception failure) {
            throw new RuntimeException("Exp 启动失败", failure);
        }
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return arguments.toArray();
    }
}
