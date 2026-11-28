package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 高频查询只更新批次局部计数，阶段结束后再写入共享指标。 */
final class RepresentationWork {
    private long touchesCalls;
    private long touchesOrigins;
    private long sortCalls;
    private long sortEdges;
    private long sortComparisons;
    long pointReuses;
    long localCandidates;
    long localRequests;

    boolean touches(OriginValue value, Set<AbstractInsnNode> selected) {
        touchesCalls++;
        if (value == null) return false;
        for (AbstractInsnNode origin : value.origins()) {
            touchesOrigins++;
            if (selected.contains(origin)) return true;
        }
        return false;
    }

    private record OrderedEdge(PropagationEdge edge, String from, String to, String kind) { }

    List<PropagationEdge> orderedEdges(Collection<PropagationEdge> edges) {
        sortCalls++;
        sortEdges += edges.size();
        List<OrderedEdge> ordered = new ArrayList<>(edges.size());
        for (PropagationEdge edge : edges) {
            ordered.add(new OrderedEdge(edge, edge.fromPoint().key(), edge.toPoint().key(), edge.kind().name()));
        }
        ordered.sort((first, second) -> {
            sortComparisons++;
            int order = first.from().compareTo(second.from());
            if (order == 0) order = first.to().compareTo(second.to());
            if (order == 0) order = first.kind().compareTo(second.kind());
            return order;
        });
        return ordered.stream().map(OrderedEdge::edge).toList();
    }

    void recordMetrics(AnalysisMetrics metrics) {
        metrics.count(AnalysisMetrics.Count.TOUCHES_CALLS, touchesCalls);
        metrics.count(AnalysisMetrics.Count.TOUCHES_ORIGINS, touchesOrigins);
        metrics.count(AnalysisMetrics.Count.SORT_CALLS, sortCalls);
        metrics.count(AnalysisMetrics.Count.SORT_EDGES, sortEdges);
        metrics.count(AnalysisMetrics.Count.SORT_KEYS, sortEdges * 2);
        metrics.count(AnalysisMetrics.Count.SORT_COMPARISONS, sortComparisons);
        metrics.count(AnalysisMetrics.Count.POINT_REUSES, pointReuses);
        metrics.count(AnalysisMetrics.Count.LOCAL_CANDIDATES, localCandidates);
        metrics.count(AnalysisMetrics.Count.LOCAL_REQUESTS, localRequests);
        touchesCalls = touchesOrigins = sortCalls = sortEdges = sortComparisons = 0;
        pointReuses = localCandidates = localRequests = 0;
    }
}
