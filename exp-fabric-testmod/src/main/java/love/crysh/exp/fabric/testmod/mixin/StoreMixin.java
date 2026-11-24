package love.crysh.exp.fabric.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import love.crysh.exp.persistence.Store;

@Mixin(value = Store.class, remap = false)
public abstract class StoreMixin {
    @ModifyArg(method = "insertProduct(Ljava/sql/Connection;Ljava/lang/String;Ljava/lang/String;Ljava/math/BigDecimal;Z)J", at = @At(value = "INVOKE", target = "Ljava/sql/PreparedStatement;setString(ILjava/lang/String;)V", ordinal = 1), index = 1, require = 1)
    private static String test$reverseCreatedProductName(String name) {
        return new StringBuilder(name).reverse().toString();
    }
}
