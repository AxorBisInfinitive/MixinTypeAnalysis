package love.crysh.mixinta.model;

public record MigrationDemand(String id, MethodKey target, int parameter, String representation) {
}
