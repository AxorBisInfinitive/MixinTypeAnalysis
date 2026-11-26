package love.crysh.mixinta.analysis;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.MigrationRequirement;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;
import love.crysh.mixinta.model.DependencyKind;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepresentationPlanTest {
    private static final MethodKey METHOD = new MethodKey("sample/Subject", "run", "()V");
    private static final Type BLOCK_POS = Type.getObjectType("sample/BlockPos");

    @Test
    void reportsEveryDeclarationContributingToConflictingEdits() {
        var location = new RepresentationPlan.Location(METHOD, 7);
        var first = edit(location, "load-reference", "first", BLOCK_POS);
        var second = edit(location, "load-reference", "second", BLOCK_POS);
        var third = edit(location, "cast-reference", "third", Type.getObjectType("sample/OtherPos"));
        var fourth = edit(location, "cast-reference", "fourth", Type.getObjectType("sample/OtherPos"));
        var unrelated = edit(new RepresentationPlan.Location(METHOD, 12), "load-reference", "unrelated", BLOCK_POS);
        List<Diagnostic> diagnostics = new ArrayList<>();

        RepresentationPlan.merge(List.of(plan(first), plan(second), plan(third), plan(fourth), plan(unrelated)), Map.of(), diagnostics);

        assertEquals(1, diagnostics.size());
        assertContributors(diagnostics.getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void reportsEveryDeclarationContributingToConflictingArguments() {
        var location = new RepresentationPlan.Location(METHOD, 9);
        var original = new MethodKey("sample/Subject", "lookup", "(J)I");
        var first = argument(location, original, original, "first");
        var second = argument(location, original, original, "second");
        var third = argument(location, original, new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;)I"), "third");
        var fourth = argument(location, original, new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;)I"), "fourth");
        List<Diagnostic> diagnostics = new ArrayList<>();

        RepresentationPlan.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of(), diagnostics);

        assertEquals(1, diagnostics.size());
        assertContributors(diagnostics.getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void reportsEveryDeclarationContributingToConflictingLocalMetadata() {
        var first = local("Ljava/util/Queue<Lsample/BlockPos;>;", "first");
        var second = local("Ljava/util/Queue<Lsample/BlockPos;>;", "second");
        var third = local("Ljava/util/Queue<Lsample/OtherPos;>;", "third");
        var fourth = local("Ljava/util/Queue<Lsample/OtherPos;>;", "fourth");
        List<Diagnostic> diagnostics = new ArrayList<>();

        RepresentationPlan.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of("sample/Subject", localOwner()), diagnostics);

        assertEquals(1, diagnostics.size());
        assertContributors(diagnostics.getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void reportsEveryDeclarationContributingToConflictingBridges() {
        var bridge = new MethodKey("sample/Rules", "bridge", "(J)Ljava/lang/Object;");
        var first = new RepresentationPlan.Bridge(bridge, new MethodKey("sample/Rules", "encode", "(J)Ljava/lang/Object;"),
                requirement("first", BLOCK_POS));
        var second = new RepresentationPlan.Bridge(bridge, new MethodKey("sample/Rules", "encode", "(J)Ljava/lang/Object;"),
                requirement("second", BLOCK_POS));
        var third = new RepresentationPlan.Bridge(bridge, new MethodKey("sample/Rules", "encodeOther", "(J)Ljava/lang/Object;"),
                requirement("third", Type.getObjectType("sample/OtherPos")));
        var fourth = new RepresentationPlan.Bridge(bridge, new MethodKey("sample/Rules", "encodeOther", "(J)Ljava/lang/Object;"),
                requirement("fourth", Type.getObjectType("sample/OtherPos")));
        List<Diagnostic> diagnostics = new ArrayList<>();

        RepresentationPlan.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of(), diagnostics);

        assertEquals(1, diagnostics.size());
        assertContributors(diagnostics.getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void preservesUseIdentityWhenPropagationPointsAreSerialized() {
        var edge = new PropagationEdge("sample/Subject.run()V instruction=7 operand=1",
                "sample/BlockPos.asLong()J instruction=3", "SOURCE_RESULT");

        assertEquals(7, edge.fromPoint().instruction());
        assertEquals("operand=1", edge.fromPoint().detail());
        assertEquals(3, edge.toPoint().instruction());
    }

    private static RepresentationPlan.Edit edit(RepresentationPlan.Location location, String action,
            String id, Type representation) {
        return new RepresentationPlan.Edit(location, action, null, representation, TypeRule.Binding.RECEIVER, 0,
                requirement(id, representation));
    }

    private static RepresentationPlan.Argument argument(RepresentationPlan.Location location, MethodKey original,
            MethodKey implementation, String id) {
        return new RepresentationPlan.Argument(location, original, implementation, 0, BLOCK_POS, null, id,
                requirement(id, BLOCK_POS));
    }

    private static RepresentationPlan.Local local(String signature, String id) {
        return new RepresentationPlan.Local(METHOD, 0, "Ljava/util/Queue;", signature, requirement(id, BLOCK_POS));
    }

    private static MigrationRequirement requirement(String id, Type representation) {
        MethodKey target = new MethodKey("sample/Subject", id, "(J)I");
        var anchor = new ProgramPoint(METHOD, 7, "sample/Subject.run()V", "operand=0");
        var targetPoint = new ProgramPoint(target, -1, target.toString(), "parameter=0");
        return new MigrationRequirement(id, target, 0, representation.getDescriptor(),
                List.of(new PropagationEdge(targetPoint, anchor, DependencyKind.TARGET_ARGUMENT)), List.of(anchor));
    }

    private static ClassNode localOwner() {
        ClassNode owner = new ClassNode();
        owner.name = "sample/Subject";
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.localVariables = new ArrayList<>(List.of(new LocalVariableNode("queue", "Ljava/util/Queue;",
                "Ljava/util/Queue<Ljava/lang/Long;>;", start, end, 0)));
        owner.methods.add(method);
        return owner;
    }

    private static RepresentationPlan plan(RepresentationPlan.Edit edit) {
        return new RepresentationPlan(List.of(edit), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static RepresentationPlan plan(RepresentationPlan.Argument argument) {
        return new RepresentationPlan(List.of(), List.of(argument), List.of(), List.of(), List.of(), List.of());
    }

    private static RepresentationPlan plan(RepresentationPlan.Local local) {
        return new RepresentationPlan(List.of(), List.of(), List.of(local), List.of(), List.of(), List.of());
    }

    private static RepresentationPlan plan(RepresentationPlan.Bridge bridge) {
        return new RepresentationPlan(List.of(), List.of(), List.of(), List.of(bridge), List.of(), List.of());
    }

    private static void assertContributors(Diagnostic diagnostic, Set<String> expected) {
        assertEquals("REPRESENTATION_PLAN_CONFLICT", diagnostic.code());
        assertEquals(expected, diagnostic.migrations().stream().map(requirement -> requirement.target().name()).collect(Collectors.toSet()));
        assertTrue(diagnostic.migrations().stream().allMatch(requirement -> !requirement.dependencies().isEmpty()));
    }
}
