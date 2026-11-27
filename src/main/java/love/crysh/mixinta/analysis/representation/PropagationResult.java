package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.declaration.RepresentationRule;

import java.util.List;
import java.util.Map;

/** 传播结束后的事实、SOURCE 决策与累计问题。 */
record PropagationResult(RepresentationFacts facts, Map<RuleUse, RepresentationRule> rules, List<RepresentationIssue> issues) {
    PropagationResult {
        rules = Map.copyOf(rules);
        issues = List.copyOf(issues);
    }
}
