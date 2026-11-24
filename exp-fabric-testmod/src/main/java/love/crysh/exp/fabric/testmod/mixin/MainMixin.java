package love.crysh.exp.fabric.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import love.crysh.exp.Main;

@Mixin(value = Main.class, remap = false)
public abstract class MainMixin {

    @ModifyConstant(method = "main([Ljava/lang/String;)V", constant = @Constant(stringValue = "8080"), require = 1)
    private static String modifyDefaultPort(String original) {
        return "1119";
    }
}
