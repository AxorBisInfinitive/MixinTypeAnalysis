package love.crysh.mixinta.runtime;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.util.LinkedHashSet;
import java.util.Set;

/** 从模组元数据读取显式参与类与 Mixin，保持声明顺序。 */
record RuntimeDeclarations(Set<String> classes, Set<String> mixins) {
    RuntimeDeclarations {
        classes = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(classes));
        mixins = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(mixins));
    }

    static RuntimeDeclarations read() {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> mixins = new LinkedHashSet<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue metadata = mod.getMetadata().getCustomValue("mixinta");
            if (metadata == null) continue;
            register(metadata.getAsObject().get("classes"), classes, mod.getMetadata().getId());
            register(metadata.getAsObject().get("mixins"), mixins, mod.getMetadata().getId());
        }
        return new RuntimeDeclarations(classes, mixins);
    }

    private static void register(CustomValue values, Set<String> destination, String mod) {
        if (values == null) throw new IllegalArgumentException("mixinta metadata requires classes and mixins arrays in " + mod);
        for (CustomValue value : values.getAsArray()) destination.add(value.getAsString().replace('.', '/'));
    }
}
