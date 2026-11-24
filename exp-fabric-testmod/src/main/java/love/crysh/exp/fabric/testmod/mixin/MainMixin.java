package love.crysh.exp.fabric.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import love.crysh.exp.Main;

@Mixin(value = Main.class, remap = false)
public class MainMixin {
    @Overwrite
    private static int add(int a, int b) {
        return a * b;
    }
}
