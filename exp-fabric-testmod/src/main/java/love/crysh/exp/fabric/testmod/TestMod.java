package love.crysh.exp.fabric.testmod;

import net.fabricmc.api.ModInitializer;

public final class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("exp-fabric-testmod entrypoint initialized");
    }
}
