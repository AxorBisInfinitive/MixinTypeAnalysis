package love.crysh.mixinta.diagnostic;

public enum DiagnosticVerbosity {
    NORMAL,
    VERBOSE;

    public static DiagnosticVerbosity fromProperty() {
        return "verbose".equalsIgnoreCase(System.getProperty("mixinta.diagnostics", "normal"))
                ? VERBOSE : NORMAL;
    }
}
