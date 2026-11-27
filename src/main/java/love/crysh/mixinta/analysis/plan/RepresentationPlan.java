package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.PlanAction;
import love.crysh.mixinta.model.PlanChange;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Type;

import java.util.List;

/** 保存固定输入的位置和生成参数，每次执行都使用独立节点。 */
public record RepresentationPlan(List<Edit> edits, List<Argument> arguments, List<Local> locals,
        List<Bridge> bridges, List<PlanChange> changes, List<PropagationEdge> dependencies) {
    public RepresentationPlan {
        edits = List.copyOf(edits);
        arguments = List.copyOf(arguments);
        locals = List.copyOf(locals);
        bridges = List.copyOf(bridges);
        changes = List.copyOf(changes);
        dependencies = List.copyOf(dependencies);
    }

    public enum Action {
        LOAD_REFERENCE(PlanAction.LOAD_REFERENCE),
        STORE_REFERENCE(PlanAction.STORE_REFERENCE),
        CAST_REFERENCE(PlanAction.CAST_REFERENCE),
        REMOVE_BOXING(PlanAction.REMOVE_BOXING),
        REPLACE_UNBOXING(PlanAction.REPLACE_UNBOXING),
        ENCODE_RETURN(PlanAction.ENCODE_RETURN),
        MIGRATE_INVOCATION(PlanAction.MIGRATE_INVOCATION),
        REPLACE_PRODUCER(PlanAction.REPLACE_PRODUCER),
        OBSERVE_MAP_VALUES(PlanAction.OBSERVE_MAP_VALUES),
        OBSERVE_VIEW_ELEMENTS(PlanAction.OBSERVE_VIEW_ELEMENTS);

        private final PlanAction action;

        Action(PlanAction action) { this.action = action; }

        public PlanAction planAction() { return action; }
    }

    public record Encoding(Type representation, MethodKey helper) { }

    public record Edit(InstructionLocation location, Action action, MethodKey helper, Type representation,
            TypeRule.Binding binding, int parameter, Encoding query,
            List<MigrationEvidence> contributors) {
        public Edit {
            contributors = List.copyOf(contributors);
        }

        public Edit(InstructionLocation location, Action action, MethodKey helper, Type representation, TypeRule.Binding binding, int parameter) {
            this(location, action, helper, representation, binding, parameter, null, List.of());
        }

        public Edit(InstructionLocation location, Action action, MethodKey helper, Type representation, TypeRule.Binding binding, int parameter,
                MigrationEvidence source) {
            this(location, action, helper, representation, binding, parameter, null, source == null ? List.of() : List.of(source));
        }

        public Edit(InstructionLocation location, Action action, MethodKey helper, Type representation, TypeRule.Binding binding,
                int parameter, Encoding query, MigrationEvidence source) {
            this(location, action, helper, representation, binding, parameter, query,
                    source == null ? List.of() : List.of(source));
        }

        Edit withContributors(List<MigrationEvidence> contributors) {
            return new Edit(location, action, helper, representation, binding, parameter, query, contributors);
        }

        public boolean observation() {
            return action.equals(RepresentationPlan.Action.OBSERVE_MAP_VALUES) || action.equals(RepresentationPlan.Action.OBSERVE_VIEW_ELEMENTS);
        }

    }

    public record Argument(InstructionLocation location, MethodKey original, MethodKey implementation, int parameter,
            Type representation, MethodKey encoder, String migration,
            List<MigrationEvidence> contributors) {
        public Argument {
            contributors = List.copyOf(contributors);
        }

        public Argument(InstructionLocation location, MethodKey original, MethodKey implementation, int parameter,
                Type representation, MethodKey encoder, String migration,
                MigrationEvidence source) {
            this(location, original, implementation, parameter, representation, encoder, migration,
                    source == null ? List.of() : List.of(source));
        }

        Argument withContributors(List<MigrationEvidence> contributors) {
            return new Argument(location, original, implementation, parameter, representation, encoder, migration, contributors);
        }
    }

    public record Local(MethodKey method, int entryIndex, String descriptor, String signature,
            List<MigrationEvidence> contributors) {
        public Local {
            contributors = List.copyOf(contributors);
        }

        public Local(MethodKey method, int entryIndex, String descriptor, String signature,
                MigrationEvidence source) {
            this(method, entryIndex, descriptor, signature, source == null ? List.of() : List.of(source));
        }

    }

    public record Bridge(MethodKey method, MethodKey helper,
            List<MigrationEvidence> contributors) {
        public Bridge {
            contributors = List.copyOf(contributors);
        }

        public Bridge(MethodKey method, MethodKey helper, MigrationEvidence source) {
            this(method, helper, source == null ? List.of() : List.of(source));
        }

        Bridge withContributors(List<MigrationEvidence> contributors) {
            return new Bridge(method, helper, contributors);
        }

    }

}
