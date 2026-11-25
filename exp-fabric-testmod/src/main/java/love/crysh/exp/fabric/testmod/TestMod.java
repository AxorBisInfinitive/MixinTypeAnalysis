package love.crysh.exp.fabric.testmod;

import love.crysh.mixinta.runtime.TypedOverwriteRuntime;
import net.fabricmc.api.ModInitializer;

public final class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        TypedOverwriteRuntime.initialize();
        System.out.println("exp-fabric-testmod entrypoint initialized");
    }
}
