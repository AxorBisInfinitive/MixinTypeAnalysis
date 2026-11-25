package love.crysh.mixinta.analysis;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DiagnosticFormatter {
    private DiagnosticFormatter() {
    }

    static String format(List<Diagnostic> diagnostics, DiagnosticVerbosity verbosity) {
        List<Diagnostic> ordered = diagnostics.stream().sorted(Comparator
                .comparing((Diagnostic value) -> String.valueOf(value.target()))
                .thenComparing(value -> String.valueOf(value.method()))
                .thenComparingInt(Diagnostic::instruction)
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message)).toList();
        StringBuilder output = new StringBuilder("TypedOverwriteException: ")
                .append(ordered.size()).append(" unresolved constraints").append(System.lineSeparator());
        List<Diagnostic> contextual = ordered.stream().filter(DiagnosticFormatter::hasContext).toList();
        if (!contextual.isEmpty()) {
            appendTargets(output, contextual, verbosity);
            appendLocations(output, contextual, verbosity);
        }
        for (Diagnostic diagnostic : ordered) {
            appendDiagnostic(output, diagnostic, verbosity);
        }
        output.append(System.lineSeparator()).append("Analysis failed. No transformations published.");
        return output.toString();
    }

    private static boolean hasContext(Diagnostic diagnostic) {
        return diagnostic.target() != null || diagnostic.requirement() != null || !diagnostic.dependencyPath().isEmpty();
    }

    private static void appendTargets(StringBuilder output, List<Diagnostic> diagnostics, DiagnosticVerbosity verbosity) {
        output.append(System.lineSeparator()).append("Target:").append(System.lineSeparator());
        Set<String> targets = new LinkedHashSet<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.target() == null) {
                continue;
            }
            String target = displayTargetMethod(diagnostic.target(), verbosity);
            if (diagnostic.targetParameter() >= 0) {
                target += System.lineSeparator() + "parameter " + diagnostic.targetParameter() + ": "
                        + displayType(diagnostic.sourceType(), verbosity) + " -> "
                        + displayType(diagnostic.targetType(), verbosity);
            }
            targets.add(target);
        }
        for (String target : targets) {
            for (String line : target.split(System.lineSeparator())) {
                output.append("    ").append(line).append(System.lineSeparator());
            }
        }
    }

    private static void appendLocations(StringBuilder output, List<Diagnostic> diagnostics, DiagnosticVerbosity verbosity) {
        Set<String> locations = diagnostics.stream().filter(value -> value.method() != null)
                .map(value -> displayMethod(value.method(), verbosity)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (locations.isEmpty()) {
            return;
        }
        output.append(System.lineSeparator()).append("Location:").append(System.lineSeparator());
        for (String location : locations) {
            output.append("    ").append(location).append(System.lineSeparator());
        }
    }

    private static void appendDiagnostic(StringBuilder output, Diagnostic diagnostic, DiagnosticVerbosity verbosity) {
        output.append(System.lineSeparator()).append("[").append(diagnostic.code()).append("]").append(System.lineSeparator());
        if (diagnostic.method() != null) {
            output.append("    instruction ").append(diagnostic.instruction()).append(": ")
                    .append(diagnostic.failureSymbol().isEmpty()
                            ? displayMethod(diagnostic.method(), verbosity)
                            : displaySymbol(diagnostic.failureSymbol(), verbosity))
                    .append(System.lineSeparator());
        } else {
            output.append("    ").append(diagnostic.message()).append(System.lineSeparator());
        }
        if (diagnostic.requirement() != null) {
            RuleRequirement requirement = diagnostic.requirement();
            output.append(System.lineSeparator())
                    .append("    Required: ").append(requirement.kind()).append(System.lineSeparator())
                    .append("    Binding:  ").append(requirement.binding()).append(System.lineSeparator())
                    .append("    Type:     ").append(requirement.type()).append(System.lineSeparator());
        } else if (!diagnostic.message().isEmpty() && diagnostic.method() != null) {
            output.append("    Message:  ").append(diagnostic.message()).append(System.lineSeparator());
        }
        List<DependencyStep> path = diagnostic.dependencyPath();
        if (path.isEmpty() && !diagnostic.propagation().isEmpty()) {
            path = fallbackPath(diagnostic.propagation());
        }
        if (!path.isEmpty()) {
            output.append(System.lineSeparator()).append("    Dependency:").append(System.lineSeparator());
            appendPath(output, path, verbosity);
        }
        if (verbosity == DiagnosticVerbosity.VERBOSE) {
            if (!diagnostic.migration().isEmpty()) {
                output.append("    Migration: ").append(diagnostic.migration()).append(System.lineSeparator());
            }
            for (String candidate : diagnostic.candidates()) {
                output.append("    Candidate: ").append(candidate).append(System.lineSeparator());
            }
            for (PropagationEdge edge : diagnostic.propagation()) {
                output.append("    Edge: ").append(displayPoint(edge.fromPoint(), verbosity)).append(" -> ")
                        .append(displayPoint(edge.toPoint(), verbosity)).append(" ").append(edge.kind())
                        .append(System.lineSeparator());
            }
        }
    }

    private static void appendPath(StringBuilder output, List<DependencyStep> path, DiagnosticVerbosity verbosity) {
        for (int index = 0; index < path.size(); index++) {
            DependencyStep step = path.get(index);
            String prefix = index == 0 ? "        " : "          -> ";
            output.append(prefix).append(displaySymbol(step.symbol(), verbosity));
            if (step.instruction() >= 0) {
                output.append(" [").append(step.instruction()).append("]");
            }
            output.append(System.lineSeparator());
        }
    }

    private static List<DependencyStep> fallbackPath(List<PropagationEdge> edges) {
        List<DependencyStep> path = new ArrayList<>();
        for (PropagationEdge edge : edges) {
            path.add(step(edge.fromPoint()));
            path.add(step(edge.toPoint()));
        }
        return path.stream().distinct().toList();
    }

    private static DependencyStep step(ProgramPoint point) {
        String symbol = point.symbol();
        return new DependencyStep(symbol, point.instruction(), descriptor(symbol));
    }

    private static String displayPoint(ProgramPoint point, DiagnosticVerbosity verbosity) {
        String symbol = point.symbol();
        if (point.instruction() >= 0) {
            symbol += " instruction=" + point.instruction();
        }
        if (!point.detail().isEmpty()) {
            symbol += " " + point.detail();
        }
        return displaySymbol(symbol, verbosity);
    }

    private static String displayMethod(MethodKey method, DiagnosticVerbosity verbosity) {
        String owner = method.owner().replace('/', '.');
        if (verbosity == DiagnosticVerbosity.NORMAL) {
            int separator = owner.lastIndexOf('.');
            owner = separator >= 0 ? owner.substring(separator + 1) : owner;
        }
        return owner + "." + method.name() + displayMethodDescriptor(method.descriptor(), verbosity);
    }

    private static String displayTargetMethod(MethodKey method, DiagnosticVerbosity verbosity) {
        String owner = method.owner().replace('/', '.');
        if (verbosity == DiagnosticVerbosity.NORMAL) {
            int separator = owner.lastIndexOf('.');
            owner = separator >= 0 ? owner.substring(separator + 1) : owner;
        }
        return owner + "." + method.name() + (verbosity == DiagnosticVerbosity.VERBOSE
                ? method.descriptor() : method.descriptor());
    }

    private static String displaySymbol(String symbol, DiagnosticVerbosity verbosity) {
        int marker = symbol.indexOf(" instruction=");
        String method = (marker < 0 ? symbol : symbol.substring(0, marker)).replace('/', '.');
        if (verbosity == DiagnosticVerbosity.VERBOSE) {
            return method;
        }
        int descriptor = method.indexOf('(');
        int separator = descriptor < 0 ? method.lastIndexOf('.') : method.lastIndexOf('.', descriptor);
        if (separator < 0) {
            return method;
        }
        int ownerSeparator = method.lastIndexOf('.', separator - 1);
        String owner = method.substring(ownerSeparator + 1, separator);
        return owner + method.substring(separator);
    }

    private static String displayMethodDescriptor(String descriptor, DiagnosticVerbosity verbosity) {
        if (verbosity == DiagnosticVerbosity.VERBOSE) {
            return descriptor;
        }
        Type[] parameters = Type.getArgumentTypes(descriptor);
        return "(" + java.util.Arrays.stream(parameters).map(DiagnosticFormatter::displayType).collect(Collectors.joining(",")) + ")";
    }

    private static String displayType(String descriptor, DiagnosticVerbosity verbosity) {
        if (descriptor == null || descriptor.isEmpty()) {
            return "?";
        }
        if (verbosity == DiagnosticVerbosity.VERBOSE) {
            return descriptor;
        }
        try {
            return displayType(Type.getType(descriptor));
        } catch (IllegalArgumentException ignored) {
            return descriptor;
        }
    }

    private static String displayType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "boolean";
            case Type.BYTE -> "byte";
            case Type.CHAR -> "char";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            case Type.ARRAY -> {
                StringBuilder value = new StringBuilder(displayType(type.getElementType()));
                for (int index = 0; index < type.getDimensions(); index++) {
                    value.append("[]");
                }
                yield value.toString();
            }
            case Type.OBJECT -> {
                String name = type.getClassName();
                int separator = name.lastIndexOf('.');
                name = separator >= 0 ? name.substring(separator + 1) : name;
                int nested = name.lastIndexOf('$');
                yield nested >= 0 ? name.substring(nested + 1) : name;
            }
            default -> type.getDescriptor();
        };
    }

    private static String descriptor(String symbol) {
        int separator = symbol.indexOf('(');
        return separator >= 0 ? symbol.substring(separator) : "";
    }
}
