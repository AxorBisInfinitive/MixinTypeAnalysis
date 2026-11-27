package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.model.MethodKey;

public record ContainerContract(String id, int version, MethodKey method, ContainerSummary summary) {
}
