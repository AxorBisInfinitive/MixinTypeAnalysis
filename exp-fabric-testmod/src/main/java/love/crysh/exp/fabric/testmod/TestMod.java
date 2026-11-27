package love.crysh.exp.fabric.testmod;

import net.fabricmc.api.ModInitializer;

import java.util.logging.Logger;

public final class TestMod implements ModInitializer {
    private static final Logger LOG = Logger.getLogger("love.crysh.exp.fabric.testmod");

    @Override
    public void onInitialize() {
        LOG.info("exp-fabric-testmod entrypoint initialized");
    }
}
