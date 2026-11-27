package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.model.ProgramPoint;

record ContainerObservation(StorageSlot storage, ProgramPoint access, String operation) { }
