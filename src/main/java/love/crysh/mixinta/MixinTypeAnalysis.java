package love.crysh.mixinta;

import love.crysh.mixinta.runtime.TypedOverwriteRuntime;
import net.fabricmc.api.ModInitializer;

public final class MixinTypeAnalysis implements ModInitializer {
    @Override
    public void onInitialize() {
        TypedOverwriteRuntime.initialize();
        System.out.println("mixinta entrypoint initialized");
    }
}
