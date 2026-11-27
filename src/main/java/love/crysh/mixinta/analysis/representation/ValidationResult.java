package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.declaration.RepresentationRule;

import java.util.List;
import java.util.Map;

/** 验证后的完整事实及 SOURCE、READ、ENCODE 决策。 */
record ValidationResult(RepresentationFacts facts, Map<RuleUse, RepresentationRule> rules, List<RepresentationIssue> issues) {
    ValidationResult {
        rules = Map.copyOf(rules);
        issues = List.copyOf(issues);
    }
}
