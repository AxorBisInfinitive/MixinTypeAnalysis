package love.crysh.mixinta.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 同一迁移要求的证据按传播位置合并，声明身份包含目标和参数位置。 */
public record MigrationEvidence(MigrationDemand demand, List<PropagationEdge> dependencies, List<ProgramPoint> anchors) {
    public MigrationEvidence {
        Objects.requireNonNull(demand);
        dependencies = List.copyOf(dependencies);
        anchors = List.copyOf(anchors);
    }

    public MigrationEvidence(String id, MethodKey target, int parameter, String representation,
            List<PropagationEdge> dependencies, List<ProgramPoint> anchors) {
        this(new MigrationDemand(id, target, parameter, representation), dependencies, anchors);
    }

    public MigrationEvidence(String id, MethodKey target, int parameter, String representation, List<PropagationEdge> dependencies) {
        this(id, target, parameter, representation, dependencies, List.of());
    }

    public String id() { return demand.id(); }
    public MethodKey target() { return demand.target(); }
    public int parameter() { return demand.parameter(); }
    public String representation() { return demand.representation(); }

    public static List<MigrationEvidence> merge(List<List<MigrationEvidence>> groups) {
        Map<MigrationDemand, MigrationEvidence> merged = new LinkedHashMap<>();
        groups.stream().flatMap(List::stream).filter(Objects::nonNull).forEach(value ->
                merged.merge(value.demand(), value, (left, right) -> new MigrationEvidence(left.demand(),
                        java.util.stream.Stream.concat(left.dependencies().stream(), right.dependencies().stream()).distinct()
                                .sorted(Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                                        .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList(),
                        java.util.stream.Stream.concat(left.anchors().stream(), right.anchors().stream()).distinct()
                                .sorted(Comparator.comparing(ProgramPoint::key)).toList())));
        return merged.values().stream().sorted(Comparator.comparing((MigrationEvidence value) -> value.target().toString())
                .thenComparingInt(MigrationEvidence::parameter).thenComparing(MigrationEvidence::id)
                .thenComparing(MigrationEvidence::representation)).toList();
    }
}
