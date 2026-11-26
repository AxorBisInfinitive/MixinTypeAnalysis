package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
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
        dependencies.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
        dependencies.computeIfAbsent(to, ignored -> new LinkedHashSet<>());
        dependents.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from);
        edges.add(new PropagationEdge(from.point(), to.point(), kind));
    }

    boolean contains(Subject subject) {
        return dependencies.containsKey(subject);
    }

    Set<Subject> at(ProgramPoint point, int parameter) {
        Set<Subject> result = new LinkedHashSet<>();
        for (Subject subject : dependencies.keySet()) {
            if (subject.kind() != Kind.STORAGE && subject.location().method().equals(point.method())
                    && subject.location().instruction() == point.instruction()
                    && (parameter < 0 || subject.kind() == Kind.USE && subject.use().kind() == UseKind.ARGUMENT
                    && subject.use().index() == parameter)) result.add(subject);
        }
        return result;
    }

    Set<Subject> ancestors(Collection<Subject> subjects) {
        Set<Subject> result = new LinkedHashSet<>(subjects);
        ArrayDeque<Subject> pending = new ArrayDeque<>(subjects);
        while (!pending.isEmpty()) {
            for (Subject parent : dependents.getOrDefault(pending.removeFirst(), Set.of())) {
                if (result.add(parent)) pending.addLast(parent);
            }
        }
        return result;
    }

    Set<StorageSlot> storages(Collection<Subject> subjects) {
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
        return Set.copyOf(result);
    }

    List<PropagationEdge> evidence(Collection<Subject> subjects) {
        Set<ProgramPoint> selected = new LinkedHashSet<>();
        ancestors(subjects).forEach(subject -> selected.add(subject.point()));
        return edges.stream().filter(edge -> selected.contains(edge.fromPoint()) && selected.contains(edge.toPoint()))
                .sorted(Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                        .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList();
    }
}
