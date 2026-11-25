package love.crysh.exp.fabric.testmod.mixin;

import love.crysh.mixinta.TypedOverwrite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import love.crysh.exp.Main;

@Mixin(value = Main.class, remap = false)
public class MainMixin {
    @TypedOverwrite(method = "div(II)I")
    private static int div(double a, double b) {
        return (int) (a / b * 10);
    }
    @Overwrite
    private static int add(int a, int b) {
        return a * b;
    }
}
