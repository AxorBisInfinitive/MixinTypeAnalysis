package love.crysh.mixinta.diagnostic;

import love.crysh.mixinta.model.ElementState;
import love.crysh.mixinta.model.ProgramPoint;

/** access 保存产生该约束的真实调用位置，storage 保存根分配位置。 */
public record ContainerContext(ProgramPoint storage, String descriptor, int channel,
        ProgramPoint access, String operation, ElementState state) {
}
