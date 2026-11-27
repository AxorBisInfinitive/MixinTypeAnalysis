package love.crysh.mixinta.analysis.declaration;

import love.crysh.mixinta.analysis.container.ContainerContract;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Type;

import java.util.List;

public record RepresentationMigration(String id, MethodKey target, int parameter, Type representation,
        String rulesOwner, List<RepresentationRule> rules, List<ContainerContract> containers) {
    public RepresentationMigration {
        rules = List.copyOf(rules);
        containers = List.copyOf(containers);
    }

}
