package love.crysh.exp.fabric.testmod.initialization;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/** 在 adapter 阶段安装测试观察器，并按场景提前定义业务类。 */
public final class InitializationProbeAdapter implements LanguageAdapter {
    public InitializationProbeAdapter() throws Exception {
        if (Boolean.getBoolean("probe.existingRoot") || Boolean.getBoolean("probe.missingPreparedInput")) {
            net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher()
                    .addToClassPath(java.nio.file.Path.of(System.getProperty("probe.extraRoot")));
        }
        String early = System.getProperty("probe.earlyClass");
        if (early != null) {
            Class.forName(early, true, getClass().getClassLoader());
            System.out.println("EARLY_DEFINED=" + early);
        }
        InitializationProbeRuntime.bootstrap();
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        return LanguageAdapter.getDefault().create(mod, value, type);
    }
}
