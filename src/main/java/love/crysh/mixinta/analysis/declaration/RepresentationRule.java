package love.crysh.mixinta.analysis.declaration;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.MethodKey;

public record RepresentationRule(String id, int version, TypeRule.Kind kind, MethodKey source, MethodKey helper,
        TypeRule.Binding binding, int parameter, String mergedOwner) {
}
