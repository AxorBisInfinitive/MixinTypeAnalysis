package love.crysh.mixinta;

import net.fabricmc.api.ModInitializer;

public final class MixinTypeAnalysis implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("mixinta entrypoint initialized");
    }
}
