package love.crysh.mixinta.analysis;

import java.util.List;

record Migration(MethodKey original, MethodKey implementation, int access, List<RepresentationMigration> representations) {
    Migration {
        representations = List.copyOf(representations);
    }
}
