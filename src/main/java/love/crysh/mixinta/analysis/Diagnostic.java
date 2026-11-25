package love.crysh.mixinta.analysis;

import java.util.List;

/** 分析诊断。instruction 和 parameter 从零计数，-1 表示该位置不适用。 */
public record Diagnostic(
        String code,
        MethodKey method,
        int instruction,
        int parameter,
        String message,
        String migration,
        List<PropagationEdge> propagation,
        List<String> candidates,
        MethodKey target,
        int targetParameter,
        String sourceType,
        String targetType,
        RuleRequirement requirement,
        List<DependencyStep> dependencyPath,
        String failureSymbol) {
    public Diagnostic {
        propagation = List.copyOf(propagation);
        candidates = List.copyOf(candidates);
        dependencyPath = List.copyOf(dependencyPath);
    }

    public Diagnostic(String code, MethodKey method, int instruction, int parameter, String message) {
        this(code, method, instruction, parameter, message, "", List.of(), List.of(),
                null, -1, "", "", null, List.of(), "");
    }

    public Diagnostic(String code, MethodKey method, int instruction, int parameter, String message,
            String migration, List<PropagationEdge> propagation, List<String> candidates) {
        this(code, method, instruction, parameter, message, migration, propagation, candidates,
                null, -1, "", "", null, List.of(), "");
    }

    public Diagnostic withContext(MethodKey target, int targetParameter, String sourceType, String targetType,
            RuleRequirement requirement, List<DependencyStep> dependencyPath, String failureSymbol) {
        return new Diagnostic(code, method, instruction, parameter, message, migration, propagation, candidates,
                target, targetParameter, sourceType, targetType, requirement, dependencyPath, failureSymbol);
    }

    @Override
    public String toString() {
        return DiagnosticFormatter.format(List.of(this), DiagnosticVerbosity.VERBOSE);
    }
}
