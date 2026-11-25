package love.crysh.exp.fabric.testmod;

import love.crysh.mixinta.MixinTypeAnalysis;
import net.fabricmc.api.ModInitializer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestMod implements ModInitializer {
    private static final String AGENT_ATTACHER = "love.crysh.exp.fabric.testmod.AgentAttacher";

    @Override
    public void onInitialize() {
        System.out.println("exp-fabric-testmod entrypoint initialized");
        System.out.println("mixinta dependency loaded: " + MixinTypeAnalysis.class.getName());
        installAgent();
    }

    private void installAgent() {
        try {
            Path modJar = Path.of(TestMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(modJar)) {
                throw new IllegalStateException("Test mod must be loaded from a JAR to attach its agent: " + modJar);
            }

            String executableName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
            Process helper = new ProcessBuilder(
                    javaExecutable.toString(),
                    "--add-modules=jdk.attach",
                    "-cp",
                    modJar.toString(),
                    AGENT_ATTACHER,
                    Long.toString(ProcessHandle.current().pid()),
                    modJar.toString())
                    .inheritIO()
                    .start();

            int exitCode = helper.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Agent attach helper exited with code " + exitCode);
            }
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Unable to start the ASM agent", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while attaching the ASM agent", failure);
        }
    }
}
