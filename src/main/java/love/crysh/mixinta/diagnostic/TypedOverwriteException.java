package love.crysh.mixinta.diagnostic;

import java.util.Comparator;
import java.util.List;

public final class TypedOverwriteException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public TypedOverwriteException(List<Diagnostic> diagnostics) {
        this(diagnostics, DiagnosticVerbosity.fromProperty());
    }

    public TypedOverwriteException(List<Diagnostic> diagnostics, DiagnosticVerbosity verbosity) {
        this(sorted(diagnostics), stripHeader(DiagnosticFormatter.format(sorted(diagnostics), verbosity)), true);
    }

    private TypedOverwriteException(List<Diagnostic> diagnostics, String message, boolean ignored) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public String format(DiagnosticVerbosity verbosity) {
        return DiagnosticFormatter.format(diagnostics, verbosity);
    }

    private static String stripHeader(String message) {
        String header = "TypedOverwriteException: ";
        return message.startsWith(header) ? message.substring(header.length()) : message;
    }

    private static List<Diagnostic> sorted(List<Diagnostic> diagnostics) {
        return diagnostics.stream().sorted(Comparator
                .comparing((Diagnostic diagnostic) -> String.valueOf(diagnostic.target()))
                .thenComparing((Diagnostic diagnostic) -> String.valueOf(diagnostic.method()))
                .thenComparingInt(Diagnostic::instruction)
                .thenComparingInt(Diagnostic::parameter)
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message)).toList();
    }

    public static void throwIfErrors(List<Diagnostic> diagnostics) {
        if (!diagnostics.isEmpty()) {
            throw new TypedOverwriteException(diagnostics);
        }
    }
}
