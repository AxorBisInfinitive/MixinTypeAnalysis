package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.MethodKey;

record CallSite(MethodKey caller, int instruction, Migration migration) {
}
