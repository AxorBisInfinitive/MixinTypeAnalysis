package love.crysh.mixinta.runtime;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class RuntimePreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        TypedOverwriteRuntime.initialize();
    }
}
