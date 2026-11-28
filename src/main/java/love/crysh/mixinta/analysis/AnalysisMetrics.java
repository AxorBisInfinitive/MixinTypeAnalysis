package love.crysh.mixinta.analysis;

import java.util.EnumMap;
import java.util.Map;

/** 同一分析批次共享计数，阶段计时只累计其实际执行区间。 */
public final class AnalysisMetrics {
    public enum Stage {
        SOURCE, RAW_INPUT, PREPROCESS, MIXIN, DATAFLOW, CONTAINER,
        CONTAINER_SUMMARIES, CONTAINER_CALLS, CONTAINER_FIRST_CONVERGENCE, CONTAINER_UNKNOWN,
        CONTAINER_SECOND_CONVERGENCE, CONTAINER_RELATIONSHIPS, CONTAINER_ALIASES, CONTAINER_EFFECTS,
        CONTAINER_FINAL_DISPATCH, CONTAINER_DEPENDENCIES,
        REPRESENTATION, REPRESENTATION_INPUT, REPRESENTATION_PROPAGATION, REPRESENTATION_INITIAL_FACTS,
        REPRESENTATION_VALIDATION, REPRESENTATION_FINAL_FACTS, REPRESENTATION_CONFLICTS, REPRESENTATION_DIAGNOSTICS,
        PLAN, OUTPUT, PREMISES
    }
    public enum Count {
        CANDIDATE_CLASSES, UNAVAILABLE_CLASSES, FAILED_INPUTS, SNAPSHOT_CLASSES, AVAILABLE_METHODS,
        ANALYZED_METHODS, FLOW_CACHE_HITS, SUMMARY_CONTEXTS, MODIFIED_CLASSES,
        INDEX_ENTRIES, INDEX_FINALIZATIONS, ALIAS_POSITIONS, ALIAS_VALUES,
        FIRST_ROUNDS, FIRST_CALL_VISITS, FIRST_DISPATCHES, FIRST_FACT_UPDATES,
        SECOND_ROUNDS, SECOND_CALL_VISITS, SECOND_DISPATCHES, SECOND_FACT_UPDATES,
        REFERENCE_JOINS, REFERENCE_REUSES, REFERENCE_GROWTHS, REFERENCE_COPIED_ELEMENTS, UNKNOWN_VALUES,
        DEFAULT_SYMBOLS, POINT_CREATIONS, POINT_REUSES, SORT_CALLS, SORT_EDGES, SORT_COMPARISONS, SORT_KEYS,
        TOUCHES_CALLS, TOUCHES_ORIGINS, LOCAL_CANDIDATES, LOCAL_REQUESTS,
        GRAPH_LINKS, GRAPH_ADJACENCIES, GRAPH_EDGES, GRAPH_AT_VISITS, GRAPH_CLOSURE_VISITS, GRAPH_QUERY_HITS,
        FINAL_DISPATCH_BUILDS, FINAL_DISPATCH_HITS, DEPENDENCY_BUILDS, VALUE_RESOLUTIONS, VALUE_ORIGINS,
        REFERENCE_UNIONS, REFERENCE_UNION_REUSES, REFERENCE_UNION_GROWTHS, REFERENCE_MEMBERSHIP_CHECKS,
        REFERENCE_SEALED_ELEMENTS, ALIAS_CAPACITY_HINTS,
        HIERARCHY_QUERIES, HIERARCHY_HITS, HIERARCHY_FAILURES, HIERARCHY_NODES
    }
    private final Map<Stage, Long> nanos = new EnumMap<>(Stage.class);
    private final Map<Count, Long> counts = new EnumMap<>(Count.class);

    public synchronized void add(Stage stage, long elapsed) { nanos.merge(stage, elapsed, Long::sum); }
    public synchronized void count(Count count, long value) { counts.merge(count, value, Long::sum); }
    public synchronized long count(Count count) { return counts.getOrDefault(count, 0L); }
    public synchronized Map<Stage, Long> nanos() { return Map.copyOf(nanos); }

    public synchronized String format() {
        StringBuilder result = new StringBuilder();
        for (Stage stage : Stage.values()) result.append(' ').append(stage.name()).append("_MS=").append(nanos.getOrDefault(stage, 0L) / 1_000_000);
        for (Count count : Count.values()) result.append(' ').append(count.name()).append('=').append(counts.getOrDefault(count, 0L));
        return result.toString();
    }
}
