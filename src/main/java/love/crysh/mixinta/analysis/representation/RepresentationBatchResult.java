package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.plan.RepresentationPlan;
import love.crysh.mixinta.diagnostic.Diagnostic;

import java.util.List;
import java.util.Optional;

public record RepresentationBatchResult(Optional<RepresentationPlan> plan, List<Diagnostic> diagnostics) {
    public RepresentationBatchResult {
        plan = java.util.Objects.requireNonNull(plan);
        diagnostics = List.copyOf(diagnostics);
        if (plan.isPresent() != diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid batch result");
    }
}
