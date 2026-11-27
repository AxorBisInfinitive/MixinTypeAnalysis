package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.model.MethodKey;

public record CallSite(MethodKey caller, int instruction, Migration migration) {
}
