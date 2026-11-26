package love.crysh.mixinta.analysis;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Type;

import java.util.List;

record RepresentationMigration(String id, MethodKey target, int parameter, Type representation,
        String rulesOwner, List<Rule> rules, List<ContainerRules.Definition> containers) {
    RepresentationMigration {
        rules = List.copyOf(rules);
        containers = List.copyOf(containers);
    }

    record Rule(String id, int version, TypeRule.Kind kind, MethodKey source, MethodKey helper,
            TypeRule.Binding binding, int parameter, String mergedOwner) {
    }
}
