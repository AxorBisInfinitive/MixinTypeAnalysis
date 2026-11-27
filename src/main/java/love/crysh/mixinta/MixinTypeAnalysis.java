package love.crysh.mixinta;

import love.crysh.mixinta.runtime.TypedOverwriteRuntime;
import net.fabricmc.api.ModInitializer;

import java.util.logging.Logger;

public final class MixinTypeAnalysis implements ModInitializer {
    private static final Logger LOG = Logger.getLogger("love.crysh.mixinta");

    @Override
    public void onInitialize() {
        TypedOverwriteRuntime.initialize();
        LOG.info("mixinta entrypoint initialized");
    }
}
