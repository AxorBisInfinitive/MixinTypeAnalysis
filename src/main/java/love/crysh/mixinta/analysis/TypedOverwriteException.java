package love.crysh.mixinta.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class TypedOverwriteException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public TypedOverwriteException(List<Diagnostic> diagnostics) {
        this(sorted(diagnostics), "TypedOverwrite failed with " + diagnostics.size() + " diagnostic(s)");
    }

    private TypedOverwriteException(List<Diagnostic> diagnostics, String summary) {
        super(diagnostics.stream().map(Diagnostic::toString)
                .collect(Collectors.joining(System.lineSeparator(),
                        summary + System.lineSeparator(), "")));
        this.diagnostics = diagnostics;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    private static List<Diagnostic> sorted(List<Diagnostic> diagnostics) {
        return diagnostics.stream().sorted(Comparator
                .comparing((Diagnostic diagnostic) -> String.valueOf(diagnostic.method()))
                .thenComparingInt(Diagnostic::instruction)
                .thenComparingInt(Diagnostic::parameter)
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message)).toList();
    }
}
