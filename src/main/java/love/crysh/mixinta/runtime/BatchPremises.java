package love.crysh.mixinta.runtime;

import org.spongepowered.asm.mixin.MixinEnvironment;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/** 配置和来源冻结后，各个缓存与定义入口核对同一份批次前提。 */
record BatchPremises(MixinEnvironment environment, Object transformer, KnotClassSources.State sources,
        MixinDiscovery.ConfigurationSnapshot configurations,
        Map<String, ClassSourceIndex.Source> inputSources) {
    BatchPremises {
        inputSources = Map.copyOf(inputSources);
    }

    void verify(MixinEnvironment currentEnvironment, Object currentTransformer, KnotClassSources.State currentSources,
            MixinDiscovery.ConfigurationSnapshot currentConfigurations, BiFunction<String, String, IllegalStateException> failure) {
        if (environment != currentEnvironment || transformer != currentTransformer) {
            throw failure.apply("MIXIN_CONFIGURATION_CHANGED", "batch environment or transformer changed");
        }
        if (!sources.equals(currentSources)) throw failure.apply("CLASS_SOURCES_CHANGED", "Knot sources or restrictions changed");
        if (!configurations.sameProcessor(currentConfigurations)) {
            throw failure.apply("MIXIN_CONFIGURATION_CHANGED", "active or pending processor configurations changed");
        }
        if (!configurations.bindings().equals(currentConfigurations.bindings())) {
            throw failure.apply("MIXIN_CONFIGURATION_CHANGED", "effective target bindings changed");
        }
    }

    void verifySource(String name, ClassSourceIndex.Source current,
            BiFunction<String, String, IllegalStateException> failure) {
        ClassSourceIndex.Source expected = inputSources.get(name);
        if (expected != null && !expected.sameOrigin(current)) {
            throw failure.apply("CLASS_SOURCE_CHANGED", name + " expected=" + expected + " actual=" + current);
        }
    }

    void verifyDefinition(String name, ClassLoader loader, ProtectionDomain domain,
            UnaryOperator<IllegalStateException> failure) {
        ClassSourceIndex.Source expected = inputSources.get(name);
        if (expected == null) return;
        Path root = null;
        boolean matches;
        try {
            if (domain != null && domain.getCodeSource() != null) {
                root = Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            }
            matches = loader == expected.definingLoader() && sameRoot(root, expected.root());
        } catch (java.net.URISyntaxException | java.io.IOException | RuntimeException invalid) {
            throw failure.apply(new IllegalStateException("CLASS_SOURCE_CHANGED " + name + " invalid definition source", invalid));
        }
        if (!matches) {
            throw failure.apply(new IllegalStateException("CLASS_SOURCE_CHANGED " + name + " expected=" + expected
                    + " definitionLoader=" + loader + " definitionRoot=" + root));
        }
    }

    private static boolean sameRoot(Path actual, Path expected) throws java.io.IOException {
        if (java.util.Objects.equals(actual, expected)) return true;
        if (actual == null || expected == null) return false;
        return java.nio.file.Files.isSameFile(actual, expected);
    }
}
