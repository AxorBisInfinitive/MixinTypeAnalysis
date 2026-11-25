package love.crysh.mixinta.analysis;

public enum DiagnosticVerbosity {
    NORMAL,
    VERBOSE;

    public static DiagnosticVerbosity fromProperty() {
        return "verbose".equalsIgnoreCase(System.getProperty("mixinta.diagnostics", "normal"))
                ? VERBOSE : NORMAL;
    }
}
