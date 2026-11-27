package love.crysh.mixinta.runtime;

import org.spongepowered.asm.mixin.MixinEnvironment;
import java.util.Map;
import java.util.function.BiFunction;

/** 配置和来源冻结后，各个缓存与定义入口核对同一份批次前提。 */
record BatchPremises(MixinEnvironment environment, Object transformer, KnotClassSources.State sources, Map<?, ?> bindings) {
    BatchPremises { bindings = Map.copyOf(bindings); }

    void verify(MixinEnvironment currentEnvironment, Object currentTransformer, KnotClassSources.State currentSources,
            Map<?, ?> currentBindings, BiFunction<String, String, IllegalStateException> failure) {
        if (environment != currentEnvironment || transformer != currentTransformer) {
            throw failure.apply("MIXIN_CONFIGURATION_CHANGED", "batch environment or transformer changed");
        }
        if (!sources.equals(currentSources)) throw failure.apply("CLASS_SOURCES_CHANGED", "Knot sources or restrictions changed");
        if (!bindings.equals(currentBindings)) throw failure.apply("MIXIN_CONFIGURATION_CHANGED", "effective target bindings changed");
    }
}
