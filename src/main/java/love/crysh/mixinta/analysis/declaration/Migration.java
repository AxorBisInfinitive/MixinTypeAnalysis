package love.crysh.mixinta.analysis.declaration;

import love.crysh.mixinta.model.MethodKey;

import java.util.List;

public record Migration(MethodKey original, MethodKey implementation, int access, List<RepresentationMigration> representations) {
    public Migration {
        representations = List.copyOf(representations);
    }
}
