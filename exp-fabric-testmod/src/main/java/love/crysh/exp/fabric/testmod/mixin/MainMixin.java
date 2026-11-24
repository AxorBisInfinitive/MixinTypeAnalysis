package love.crysh.exp.fabric.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import love.crysh.exp.Main;

@Mixin(value = Main.class, remap = false)
public abstract class MainMixin {

    @Overwrite
    public static void main(String[] args) throws Exception {
        return;
    }
}
