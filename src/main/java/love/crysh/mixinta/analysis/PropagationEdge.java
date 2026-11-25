package love.crysh.mixinta.analysis;

import java.util.Objects;

/** 从需求位置指向其依赖位置，同时保留兼容旧调用方的文本访问器。 */
public final class PropagationEdge {
    private final String from;
    private final String to;
    private final String reason;
    private final ProgramPoint fromPoint;
    private final ProgramPoint toPoint;
    private final DependencyKind kind;

    public PropagationEdge(String from, String to, String reason) {
        this.from = Objects.requireNonNullElse(from, "");
        this.to = Objects.requireNonNullElse(to, "");
        this.reason = Objects.requireNonNullElse(reason, "");
        this.fromPoint = ProgramPoint.parse(this.from);
        this.toPoint = ProgramPoint.parse(this.to);
        this.kind = DependencyKind.fromReason(this.reason);
    }

    public PropagationEdge(ProgramPoint from, ProgramPoint to, DependencyKind kind) {
        this.fromPoint = Objects.requireNonNull(from);
        this.toPoint = Objects.requireNonNull(to);
        this.kind = Objects.requireNonNull(kind);
        this.from = encode(from);
        this.to = encode(to);
        this.reason = kind.name();
    }

    public String from() { return from; }
    public String to() { return to; }
    public String reason() { return reason; }
    public ProgramPoint fromPoint() { return fromPoint; }
    public ProgramPoint toPoint() { return toPoint; }
    public DependencyKind kind() { return kind; }

    private static String encode(ProgramPoint point) {
        String value = point.symbol();
        if (point.instruction() >= 0) value += " instruction=" + point.instruction();
        if (!point.detail().isEmpty()) value += " " + point.detail();
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PropagationEdge edge && fromPoint.equals(edge.fromPoint)
                && toPoint.equals(edge.toPoint) && kind == edge.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromPoint, toPoint, kind);
    }
}
