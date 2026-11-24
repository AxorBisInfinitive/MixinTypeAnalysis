package love.crysh.exp.fabric.testmod.mixin;

import love.crysh.exp.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Main.class, remap = false)
public abstract class MainMixin {
    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), require = 1)
    private static void test$main(CallbackInfo callbackInfo) {
    }
}
