package love.crysh.mixinta.diagnostic;

public record DependencyStep(String symbol, int instruction, String descriptor, String detail) {
    public DependencyStep {
        detail = detail == null ? "" : detail;
    }

    public DependencyStep(String symbol, int instruction, String descriptor) {
        this(symbol, instruction, descriptor, "");
    }
}
