package love.crysh.exp.fabric.testmod.mixin;

import love.crysh.mixinta.TypedOverwrite;
import love.crysh.mixinta.ParameterMigration;
import love.crysh.mixinta.TypeRule;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import love.crysh.exp.Main;
import love.crysh.exp.Main.BlockPos;

@Mixin(value = Main.class, remap = false)
public class MainMixin {
    @TypedOverwrite(method = "div(II)I")
    private static int div(double a, double b) {
        return (int) (a / b * 10);
    }

    @TypedOverwrite(method = "lookup(J)I", parameters = @ParameterMigration(parameter = 0, rules = MainMixin.class, arrayDeque = true))
    private static int lookup(BlockPos value) {
        return (int) (value.packed() ^ (value.packed() >>> 32));
    }

    @TypeRule(value = TypeRule.Kind.SOURCE, method = "love/crysh/exp/Main$BlockPos.asLong()J")
    private static BlockPos keep(BlockPos value) {
        return value;
    }

    @TypeRule(TypeRule.Kind.READ)
    private static BlockPos read(BlockPos value) {
        return java.util.Objects.requireNonNull(value);
    }

    @Overwrite
    private static int add(int a, int b) {
        return a * b;
    }
}
