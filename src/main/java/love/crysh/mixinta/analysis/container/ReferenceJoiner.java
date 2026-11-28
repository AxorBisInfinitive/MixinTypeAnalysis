package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.AnalysisMetrics;

import java.util.LinkedHashSet;
import java.util.Set;

/** 合流复用不可变事实，计数在容器阶段结束时集中汇总。 */
final class ReferenceJoiner {
    private long calls;
    private long reuses;
    private long growths;
    private long copiedElements;

    StorageReferences join(StorageReferences first, StorageReferences second) {
        calls++;
        if (first == second || empty(second)) return reused(first);
        if (empty(first)) return reused(second);
        var references = union(first.references(), second.references());
        var unknown = union(first.unknownOrigins(), second.unknownOrigins());
        boolean nullable = first.nullable() || second.nullable();
        // 并集包含两个输入，分量大小相同即可证明该输入已经覆盖全部事实。
        if (references.size() == first.references().size() && unknown.size() == first.unknownOrigins().size()
                && nullable == first.nullable()) return reused(first);
        if (references.size() == second.references().size() && unknown.size() == second.unknownOrigins().size()
                && nullable == second.nullable()) return reused(second);
        growths++;
        return new StorageReferences(references, unknown, nullable);
    }

    private StorageReferences reused(StorageReferences facts) {
        reuses++;
        return facts;
    }

    private <T> Set<T> union(Set<T> first, Set<T> second) {
        if (first == second || second.isEmpty()) return first;
        if (first.isEmpty()) return second;
        Set<T> base = first.size() >= second.size() ? first : second;
        Set<T> additions = base == first ? second : first;
        Set<T> merged = null;
        for (T value : additions) {
            if (base.contains(value)) continue;
            if (merged == null) {
                merged = new LinkedHashSet<>(base);
                copiedElements += base.size();
            }
            merged.add(value);
            copiedElements++;
        }
        return merged == null ? base : merged;
    }

    static boolean empty(StorageReferences facts) {
        return facts.references().isEmpty() && facts.unknownOrigins().isEmpty() && !facts.nullable();
    }

    void recordMetrics(AnalysisMetrics metrics) {
        metrics.count(AnalysisMetrics.Count.REFERENCE_JOINS, calls);
        metrics.count(AnalysisMetrics.Count.REFERENCE_REUSES, reuses);
        metrics.count(AnalysisMetrics.Count.REFERENCE_GROWTHS, growths);
        metrics.count(AnalysisMetrics.Count.REFERENCE_COPIED_ELEMENTS, copiedElements);
        calls = reuses = growths = copiedElements = 0;
    }
}
