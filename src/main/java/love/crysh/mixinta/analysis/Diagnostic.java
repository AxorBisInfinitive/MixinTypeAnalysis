package love.crysh.mixinta.analysis;

import java.util.List;

/** instruction 和 parameter 从零计数，-1 表示该位置不适用。 */
public record Diagnostic(String code, MethodKey method, int instruction, int parameter, String message,
        String migration, List<PropagationEdge> propagation, List<String> candidates) {
    public Diagnostic {
        propagation = List.copyOf(propagation);
        candidates = List.copyOf(candidates);
    }

    public Diagnostic(String code, MethodKey method, int instruction, int parameter, String message) {
        this(code, method, instruction, parameter, message, "", List.of(), List.of());
    }

    @Override
    public String toString() {
        String location = method == null ? "input" : method.toString();
        if (instruction >= 0) {
            location += " instruction=" + instruction;
        }
        if (parameter >= 0) {
            location += " parameter=" + parameter;
        }
        StringBuilder text = new StringBuilder("[" + code + "] " + location + " " + message);
        if (!migration.isEmpty()) {
            text.append(" migration=").append(migration);
        }
        for (PropagationEdge edge : propagation) {
            text.append(System.lineSeparator()).append("  ").append(edge.from()).append(" -> ")
                    .append(edge.to()).append(" ").append(edge.reason());
        }
        for (String candidate : candidates) {
            text.append(System.lineSeparator()).append("  candidate ").append(candidate);
        }
        return text.toString();
    }
}
