package love.crysh.mixinta.diagnostic;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import java.util.List;

/** 每个声明独立保留其要求及推导路径，相同 id 不会覆盖另一项声明。 */
public record MigrationRequirement(String id, MethodKey target, int parameter, String representation,
        List<PropagationEdge> dependencies, List<ProgramPoint> anchors) {
    public MigrationRequirement {
        dependencies = List.copyOf(dependencies);
        anchors = List.copyOf(anchors);
    }

    public MigrationRequirement(String id, MethodKey target, int parameter, String representation,
            List<PropagationEdge> dependencies) {
        this(id, target, parameter, representation, dependencies, List.of());
    }
}
