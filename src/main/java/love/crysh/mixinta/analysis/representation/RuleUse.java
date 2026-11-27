package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.InstructionLocation;

record RuleUse(InstructionLocation location, TypeRule.Kind kind, int parameter) {
}
