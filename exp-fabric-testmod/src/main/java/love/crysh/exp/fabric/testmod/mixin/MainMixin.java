package love.crysh.exp.fabric.testmod.mixin;

import love.crysh.mixinta.anno.ParameterMigration;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.anno.TypedOverwrite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import love.crysh.exp.Main;
import love.crysh.exp.Main.BlockPos;

import java.util.Objects;

@Mixin(value = Main.class, remap = false)
public class MainMixin {

    @TypedOverwrite(method = "lookup(J)I", parameters = @ParameterMigration(parameter = 0, rules = MainMixin.class))
    private static int lookup(BlockPos value) {
        return (int) (value.packed() ^ (value.packed() >>> 32));
    }

    @TypeRule(value = TypeRule.Kind.SOURCE, method = "love/crysh/exp/Main$BlockPos.asLong()J")
    private static BlockPos keep(BlockPos value) {
        return value;
    }

    @TypeRule(TypeRule.Kind.READ)
    private static BlockPos read(BlockPos value) {
        return Objects.requireNonNull(value);
    }
}
