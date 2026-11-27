package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.StorageSlot;

import java.util.List;
import java.util.Set;

record RepresentationIssue(String code, AnalysisSite site, int parameter, String message, List<String> candidates,
        Set<StorageSlot> storages, Set<ConstraintGraph.Subject> anchors, String identity) {
    RepresentationIssue {
        candidates = List.copyOf(candidates);
        storages = Set.copyOf(storages);
        anchors = Set.copyOf(anchors);
    }
}
