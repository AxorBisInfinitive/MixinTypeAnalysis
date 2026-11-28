package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 元素约束按值、具名使用位置与完整通道保存关联，引用调用图提供传递依据。 */
final class ConstraintGraph {
    enum Kind { VALUE, USE, STORAGE, UNKNOWN }

    enum UseKind { ARGUMENT, RECEIVER, OPERAND, CAPTURE, OTHER }

    record UseIdentity(UseKind kind, int index) {
        String detail() {
            return switch (kind) {
                case ARGUMENT -> "parameter=" + index;
                case RECEIVER -> "receiver";
                case OPERAND -> "operand=" + index;
                case CAPTURE -> "capture=" + index;
                case OTHER -> "use";
            };
        }
    }

    record Subject(ProgramPoint location, Kind kind, UseIdentity use, StorageSlot storage) {
        ProgramPoint point() {
            String detail = switch (kind) {
                case VALUE -> "";
                case USE -> use.detail();
                case STORAGE -> "channel=" + storage.channel();
                case UNKNOWN -> "";
            };
            return new ProgramPoint(location.method(), location.instruction(), location.symbol(), detail);
        }
    }

    private final Map<Subject, Set<Subject>> dependencies = new LinkedHashMap<>();
    private final Map<Subject, Set<Subject>> dependents = new LinkedHashMap<>();
    private final Set<PropagationEdge> edges = new LinkedHashSet<>();
    private Map<InstructionLocation, List<Subject>> locations;
    private final Map<List<Subject>, Set<Subject>> closures = new LinkedHashMap<>();
    private final Map<List<Subject>, Set<StorageSlot>> storageQueries = new LinkedHashMap<>();
    private final Map<List<Subject>, List<PropagationEdge>> evidenceQueries = new LinkedHashMap<>();
    private final Work work;

    private static final class Work {
        long links, adjacencies, edges, atVisits, closureVisits, queryHits;
        final RepresentationWork ordering = new RepresentationWork();
    }

    ConstraintGraph() { this(new Work()); }
    private ConstraintGraph(Work work) { this.work = work; }

    private boolean frozen;

    ConstraintGraph snapshot() {
        if (frozen) return this;
        ConstraintGraph copy = mutableCopy();
        copy.frozen = true;
        return copy;
    }

    ConstraintGraph mutableCopy() {
        ConstraintGraph copy = new ConstraintGraph(work);
        dependencies.forEach((key, values) -> copy.dependencies.put(key, new LinkedHashSet<>(values)));
        dependents.forEach((key, values) -> copy.dependents.put(key, new LinkedHashSet<>(values)));
        copy.edges.addAll(edges);
        return copy;
    }

    static Subject value(ProgramPoint point) {
        return new Subject(new ProgramPoint(point.method(), point.instruction(), point.symbol()), Kind.VALUE, null, null);
    }

    static Subject use(ProgramPoint point, int parameter) {
        UseIdentity identity = parameter < 0 ? new UseIdentity(UseKind.OTHER, -1)
                : new UseIdentity(UseKind.ARGUMENT, parameter);
        return use(point, identity);
    }

    static Subject receiver(ProgramPoint point) {
        return use(point, new UseIdentity(UseKind.RECEIVER, -1));
    }

    static Subject operand(ProgramPoint point, int index) {
        return use(point, new UseIdentity(UseKind.OPERAND, index));
    }

    static Subject capture(ProgramPoint point, int index) {
        return use(point, new UseIdentity(UseKind.CAPTURE, index));
    }

    private static Subject use(ProgramPoint point, UseIdentity identity) {
        return new Subject(new ProgramPoint(point.method(), point.instruction(), point.symbol()), Kind.USE, identity, null);
    }

    static Subject storage(StorageSlot slot) {
        var allocation = slot.storage();
        String descriptor = allocation.descriptor();
        var point = new ProgramPoint(allocation.method(), allocation.instruction(),
                descriptor.substring(1, descriptor.length() - 1));
        return new Subject(point, Kind.STORAGE, null, slot);
    }

    static Subject unknown(ProgramPoint point) {
        return new Subject(new ProgramPoint(point.method(), point.instruction(), point.symbol()), Kind.UNKNOWN, null, null);
    }

    void link(Subject from, Subject to, DependencyKind kind) {
        if (frozen) throw new IllegalStateException("Constraint graph is frozen");
        work.links++;
        locations = null;
        if (dependencies.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to)) work.adjacencies++;
        dependencies.computeIfAbsent(to, ignored -> new LinkedHashSet<>());
        dependents.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from);
        if (edges.add(new PropagationEdge(from.point(), to.point(), kind))) work.edges++;
    }

    Set<Subject> at(ProgramPoint point, int parameter) {
        if (locations == null) {
            locations = new LinkedHashMap<>();
            for (Subject subject : dependencies.keySet()) {
                if (subject.kind() != Kind.STORAGE) locations.computeIfAbsent(new InstructionLocation(
                        subject.location().method(), subject.location().instruction()), ignored -> new ArrayList<>()).add(subject);
            }
        }
        Set<Subject> result = new LinkedHashSet<>();
        for (Subject subject : locations.getOrDefault(new InstructionLocation(point.method(), point.instruction()), List.of())) {
            work.atVisits++;
            if (parameter < 0 || subject.kind() == Kind.USE && subject.use().kind() == UseKind.ARGUMENT
                    && subject.use().index() == parameter) result.add(subject);
        }
        return result;
    }

    Set<Subject> ancestors(Collection<Subject> subjects) {
        List<Subject> key = frozen ? List.copyOf(subjects) : null;
        if (frozen && closures.containsKey(key)) {
            work.queryHits++;
            return closures.get(key);
        }
        Set<Subject> result = new LinkedHashSet<>(subjects);
        ArrayDeque<Subject> pending = new ArrayDeque<>(subjects);
        while (!pending.isEmpty()) {
            work.closureVisits++;
            for (Subject parent : dependents.getOrDefault(pending.removeFirst(), Set.of())) {
                if (result.add(parent)) pending.addLast(parent);
            }
        }
        if (!frozen) return result;
        Set<Subject> snapshot = Collections.unmodifiableSet(result);
        closures.put(key, snapshot);
        return snapshot;
    }

    Set<StorageSlot> storages(Collection<Subject> subjects) {
        List<Subject> key = frozen ? List.copyOf(subjects) : null;
        if (frozen && storageQueries.containsKey(key)) {
            work.queryHits++;
            return storageQueries.get(key);
        }
        Set<StorageSlot> result = new LinkedHashSet<>();
        ancestors(subjects).stream().filter(subject -> subject.kind() == Kind.STORAGE)
                .forEach(subject -> result.add(subject.storage()));
        ArrayDeque<Subject> pending = new ArrayDeque<>(subjects);
        Set<Subject> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Subject subject = pending.removeFirst();
            if (!visited.add(subject)) continue;
            if (subject.kind() == Kind.STORAGE) result.add(subject.storage());
            else pending.addAll(dependencies.getOrDefault(subject, Set.of()));
        }
        Set<StorageSlot> snapshot = Set.copyOf(result);
        if (frozen) storageQueries.put(key, snapshot);
        return snapshot;
    }

    List<PropagationEdge> evidence(Collection<Subject> subjects) {
        List<Subject> key = frozen ? List.copyOf(subjects) : null;
        if (frozen && evidenceQueries.containsKey(key)) {
            work.queryHits++;
            return evidenceQueries.get(key);
        }
        Set<ProgramPoint> selected = new LinkedHashSet<>();
        ancestors(subjects).forEach(subject -> selected.add(subject.point()));
        List<PropagationEdge> result = work.ordering.orderedEdges(edges.stream()
                .filter(edge -> selected.contains(edge.fromPoint()) && selected.contains(edge.toPoint())).toList());
        if (frozen) evidenceQueries.put(key, result);
        return result;
    }

    void recordMetrics(AnalysisMetrics metrics) {
        metrics.count(AnalysisMetrics.Count.GRAPH_LINKS, work.links);
        metrics.count(AnalysisMetrics.Count.GRAPH_ADJACENCIES, work.adjacencies);
        metrics.count(AnalysisMetrics.Count.GRAPH_EDGES, work.edges);
        metrics.count(AnalysisMetrics.Count.GRAPH_AT_VISITS, work.atVisits);
        metrics.count(AnalysisMetrics.Count.GRAPH_CLOSURE_VISITS, work.closureVisits);
        metrics.count(AnalysisMetrics.Count.GRAPH_QUERY_HITS, work.queryHits);
        work.ordering.recordMetrics(metrics);
        work.links = work.adjacencies = work.edges = work.atVisits = work.closureVisits = work.queryHits = 0;
    }
}
