package love.crysh.mixinta.analysis;

import org.objectweb.asm.Type;
import love.crysh.mixinta.TypeRule;

import java.util.List;

record RepresentationMigration(String id, MethodKey target, int parameter, Type representation,
        String rulesOwner, List<Rule> rules, boolean arrayDeque) {
    RepresentationMigration {
        rules = List.copyOf(rules);
    }

    record Rule(String id, int version, TypeRule.Kind kind, MethodKey source, MethodKey helper,
            TypeRule.Binding binding, int parameter, String mergedOwner) {
    }
}
