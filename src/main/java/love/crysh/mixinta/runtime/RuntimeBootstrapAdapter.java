package love.crysh.mixinta.runtime;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/** Loader 主动构造 adapter 时建立早期转换设施。 */
public final class RuntimeBootstrapAdapter implements LanguageAdapter {
    public RuntimeBootstrapAdapter() {
        TypedOverwriteRuntime.bootstrap();
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        return LanguageAdapter.getDefault().create(mod, value, type);
    }
}
