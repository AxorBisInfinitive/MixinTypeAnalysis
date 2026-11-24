package love.crysh.exp.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Knot.launch(args, EnvType.SERVER);
    }
}
