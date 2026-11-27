package love.crysh.mixinta.analysis.plan;

import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepresentationPlanTest {
    private static final MethodKey METHOD = new MethodKey("sample/Subject", "run", "()V");
    private static final Type BLOCK_POS = Type.getObjectType("sample/BlockPos");

    @Test
    void reportsEveryDeclarationContributingToConflictingEdits() {
        var location = new InstructionLocation(METHOD, 7);
        var first = edit(location, RepresentationPlan.Action.LOAD_REFERENCE, "first", BLOCK_POS);
        var second = edit(location, RepresentationPlan.Action.LOAD_REFERENCE, "second", BLOCK_POS);
        var third = edit(location, RepresentationPlan.Action.CAST_REFERENCE, "third", Type.getObjectType("sample/OtherPos"));
        var fourth = edit(location, RepresentationPlan.Action.CAST_REFERENCE, "fourth", Type.getObjectType("sample/OtherPos"));
        var unrelated = edit(new InstructionLocation(METHOD, 12), RepresentationPlan.Action.LOAD_REFERENCE, "unrelated", BLOCK_POS);
        var result = RepresentationPlanMerger.merge(List.of(plan(first), plan(second), plan(third), plan(fourth), plan(unrelated)), Map.of());

        assertTrue(result.plan().isEmpty());
        assertEquals(1, result.conflicts().size());
        assertContributors(result.conflicts().getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void reportsEveryDeclarationContributingToConflictingArguments() {
        var location = new InstructionLocation(METHOD, 9);
        var original = new MethodKey("sample/Subject", "lookup", "(J)I");
        var first = argument(location, original, original, "first");
        var second = argument(location, original, original, "second");
        var third = argument(location, original, new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;)I"), "third");
        var fourth = argument(location, original, new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;)I"), "fourth");
        var result = RepresentationPlanMerger.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of());

        assertTrue(result.plan().isEmpty());
        assertEquals(1, result.conflicts().size());
        assertContributors(result.conflicts().getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void reportsEveryDeclarationContributingToConflictingLocalMetadata() {
        var first = local("Ljava/util/Queue<Lsample/BlockPos;>;", "first");
        var second = local("Ljava/util/Queue<Lsample/BlockPos;>;", "second");
        var third = local("Ljava/util/Queue<Lsample/OtherPos;>;", "third");
        var fourth = local("Ljava/util/Queue<Lsample/OtherPos;>;", "fourth");
        var result = RepresentationPlanMerger.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of("sample/Subject", localOwner()));

        assertTrue(result.plan().isEmpty());
        assertEquals(1, result.conflicts().size());
        assertContributors(result.conflicts().getFirst(), Set.of("first", "second", "third", "fourth"));
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
        var result = RepresentationPlanMerger.merge(List.of(plan(first), plan(second), plan(third), plan(fourth)), Map.of());

        assertTrue(result.plan().isEmpty());
        assertEquals(1, result.conflicts().size());
        assertContributors(result.conflicts().getFirst(), Set.of("first", "second", "third", "fourth"));
    }

    @Test
    void preservesUseIdentityWhenPropagationPointsAreSerialized() {
        var edge = new PropagationEdge("sample/Subject.run()V instruction=7 operand=1",
                "sample/BlockPos.asLong()J instruction=3", "SOURCE_RESULT");

        assertEquals(7, edge.fromPoint().instruction());
        assertEquals("operand=1", edge.fromPoint().detail());
        assertEquals(3, edge.toPoint().instruction());
    }

    @Test
    void gathersConflictsAtIndependentLocationsBeforeReturningFailure() {
        var firstLocation = new InstructionLocation(METHOD, 3);
        var secondLocation = new InstructionLocation(METHOD, 11);
        var first = new RepresentationPlan(List.of(
                edit(firstLocation, RepresentationPlan.Action.LOAD_REFERENCE, "first", BLOCK_POS),
                edit(secondLocation, RepresentationPlan.Action.STORE_REFERENCE, "first", BLOCK_POS)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var second = new RepresentationPlan(List.of(
                edit(firstLocation, RepresentationPlan.Action.CAST_REFERENCE, "second", BLOCK_POS),
                edit(secondLocation, RepresentationPlan.Action.REMOVE_BOXING, "second", BLOCK_POS)),
                List.of(), List.of(), List.of(), List.of(), List.of());

        var result = RepresentationPlanMerger.merge(List.of(first, second), Map.of());

        assertTrue(result.plan().isEmpty());
        assertEquals(Set.of(firstLocation, secondLocation), result.conflicts().stream().map(PlanConflict::location).collect(Collectors.toSet()));
        assertThrows(UnsupportedOperationException.class, () -> result.conflicts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.conflicts().getFirst().contributors().clear());
    }

    @Test
    void successfulMergeRetainsDeduplicatedEditsAndAllContributors() {
        var location = new InstructionLocation(METHOD, 3);
        var first = edit(location, RepresentationPlan.Action.LOAD_REFERENCE, "first", BLOCK_POS);
        var second = edit(location, RepresentationPlan.Action.LOAD_REFERENCE, "second", BLOCK_POS);

        var result = RepresentationPlanMerger.merge(List.of(plan(first), plan(second)), Map.of());

        assertTrue(result.conflicts().isEmpty());
        var merged = result.plan().orElseThrow();
        assertEquals(1, merged.edits().size());
        assertEquals(Set.of("first", "second"), merged.edits().getFirst().contributors().stream()
                .map(MigrationEvidence::id).collect(Collectors.toSet()));
    }

    @Test
    void localMetadataUsesTableEntryIndexIndependentlyOfJvmSlot() {
        var owner = localOwner();
        var variable = owner.methods.getFirst().localVariables.getFirst();
        variable.index = 7;
        var change = new RepresentationPlan.Local(METHOD, 0, BLOCK_POS.getDescriptor(), null, requirement("local", BLOCK_POS));
        var result = RepresentationPlanMerger.merge(List.of(plan(change)), Map.of(owner.name, owner));

        RepresentationPlanApplier.apply(result.plan().orElseThrow(), Map.of(owner.name, owner), Map.of());

        assertEquals(0, result.plan().orElseThrow().locals().getFirst().entryIndex());
        assertEquals(7, variable.index);
        assertEquals(BLOCK_POS.getDescriptor(), variable.desc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "argument", "local", "bridge"})
    void retainsLateCompatibleContributorsInEveryOrder(String kind) {
        var first = candidate(kind, "first", false);
        var incompatible = candidate(kind, "incompatible", true);
        var late = candidate(kind, "late", false);
        List<PlanConflict> expected = null;
        for (var order : permutations(first, incompatible, late)) {
            var result = RepresentationPlanMerger.merge(order, Map.of("sample/Subject", localOwner()));
            assertTrue(result.plan().isEmpty());
            assertEquals(1, result.conflicts().size());
            assertContributors(result.conflicts().getFirst(), Set.of("first", "incompatible", "late"));
            if (expected != null) assertEquals(expected, result.conflicts());
            expected = result.conflicts();
        }
    }

    @Test
    void combinesIndependentGenericChangesAgainstTheOriginalLocalEntry() {
        var owner = localOwner();
        var variable = owner.methods.getFirst().localVariables.getFirst();
        variable.desc = "Ljava/util/Map;";
        variable.signature = "Ljava/util/Map<Ljava/lang/Long;Ljava/lang/Long;>;";
        var left = plan(new RepresentationPlan.Local(METHOD, 0, variable.desc,
                "Ljava/util/Map<Lsample/BlockPos;Ljava/lang/Long;>;", requirement("left", BLOCK_POS)));
        var right = plan(new RepresentationPlan.Local(METHOD, 0, variable.desc,
                "Ljava/util/Map<Ljava/lang/Long;Lsample/OtherPos;>;", requirement("right", BLOCK_POS)));
        var original = plan(new RepresentationPlan.Local(METHOD, 0, variable.desc, variable.signature, requirement("original", BLOCK_POS)));
        for (var order : permutations(left, right, original)) {
            var result = RepresentationPlanMerger.merge(order, Map.of(owner.name, owner));
            assertTrue(result.conflicts().isEmpty());
            var local = result.plan().orElseThrow().locals().getFirst();
            assertEquals("Ljava/util/Map<Lsample/BlockPos;Lsample/OtherPos;>;", local.signature());
            assertEquals(Set.of("left", "right", "original"), local.contributors().stream().map(MigrationEvidence::id).collect(Collectors.toSet()));
        }
    }

    @Test
    void checksInvocationDescriptorsUsingAllParameterCandidates() {
        var location = new InstructionLocation(METHOD, 9);
        var original = new MethodKey("sample/Subject", "lookup", "(JJ)I");
        var implementation = new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;Lsample/BlockPos;)I");
        var alternate = new MethodKey("sample/Subject", "lookup", "(Lsample/BlockPos;J)I");
        var first = new RepresentationPlan.Argument(location, original, implementation, 0, BLOCK_POS, null, "first", requirement("first", BLOCK_POS));
        var second = new RepresentationPlan.Argument(location, original, alternate, 0, BLOCK_POS, null, "second", requirement("second", BLOCK_POS));
        var third = new RepresentationPlan.Argument(location, original, implementation, 1, BLOCK_POS, null, "third", requirement("third", BLOCK_POS));
        for (var order : permutations(plan(first), plan(second), plan(third))) {
            var result = RepresentationPlanMerger.merge(order, Map.of());
            assertTrue(result.plan().isEmpty());
            var conflict = result.conflicts().stream().filter(value -> value.message().equals("Invocation requires incompatible final descriptors"))
                    .findFirst().orElseThrow();
            assertContributors(conflict, Set.of("first", "second", "third"));
        }
    }

    @Test
    void overlappingEditsRetainAllCandidatesAndTheirEvidence() {
        var location = new InstructionLocation(METHOD, 7);
        var original = new MethodKey("sample/Subject", "lookup", "(J)I");
        var first = plan(edit(location, RepresentationPlan.Action.REPLACE_PRODUCER, "first", BLOCK_POS));
        var second = plan(edit(location, RepresentationPlan.Action.CAST_REFERENCE, "second", BLOCK_POS));
        var argument = plan(argument(location, original, original, "argument"));
        for (var order : permutations(first, second, argument)) {
            var result = RepresentationPlanMerger.merge(order, Map.of());
            var conflict = result.conflicts().stream().filter(value -> value.message().equals("Invocation replacement overlaps argument conversion"))
                    .findFirst().orElseThrow();
            assertContributors(conflict, Set.of("first", "second", "argument"));
        }
    }

    @Test
    void observationFusionMergesEveryContributor() {
        var location = new InstructionLocation(METHOD, 7);
        var original = new MethodKey("java/util/Collection", "contains", "(Ljava/lang/Object;)Z");
        var helper = new MethodKey("sample/Rules", "encode", "(Lsample/BlockPos;)J");
        var observation = plan(edit(location, RepresentationPlan.Action.OBSERVE_VIEW_ELEMENTS, "observation", BLOCK_POS));
        var first = plan(new RepresentationPlan.Argument(location, original, original, 0, BLOCK_POS, helper, "first", requirement("first", BLOCK_POS)));
        var late = plan(new RepresentationPlan.Argument(location, original, original, 0, BLOCK_POS, helper, "late", requirement("late", BLOCK_POS)));
        for (var order : permutations(observation, first, late)) {
            var result = RepresentationPlanMerger.merge(order, Map.of());
            var merged = result.plan().orElseThrow();
            assertTrue(merged.arguments().isEmpty());
            assertEquals(new RepresentationPlan.Encoding(BLOCK_POS, helper), merged.edits().getFirst().query());
            assertEquals(Set.of("observation", "first", "late"), merged.edits().getFirst().contributors().stream()
                    .map(MigrationEvidence::id).collect(Collectors.toSet()));
        }
    }

    private static RepresentationPlan candidate(String kind, String id, boolean incompatible) {
        var location = new InstructionLocation(METHOD, 7);
        var helper = new MethodKey("sample/Rules", incompatible ? "other" : "encode", "(Lsample/BlockPos;)J");
        var original = new MethodKey("sample/Subject", "lookup", "(J)I");
        return switch (kind) {
            case "edit" -> plan(edit(location, incompatible ? RepresentationPlan.Action.CAST_REFERENCE : RepresentationPlan.Action.LOAD_REFERENCE, id, BLOCK_POS));
            case "argument" -> plan(new RepresentationPlan.Argument(location, original, original, 0, BLOCK_POS, helper, id, requirement(id, BLOCK_POS)));
            case "local" -> plan(local("Ljava/util/Queue<Lsample/" + (incompatible ? "OtherPos" : "BlockPos") + ";>;", id));
            case "bridge" -> plan(new RepresentationPlan.Bridge(new MethodKey("sample/Rules", "bridge", "(Lsample/BlockPos;)J"), helper, requirement(id, BLOCK_POS)));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @Test
    void conflictingGroupUnionsLateEvidenceForTheSameDemand() {
        var location = new InstructionLocation(METHOD, 7);
        var shared = requirement("shared", BLOCK_POS);
        var latePoint = new ProgramPoint(METHOD, 12, METHOD.toString(), "operand=1");
        var late = new MigrationEvidence(shared.demand(),
                List.of(new PropagationEdge(shared.anchors().getFirst(), latePoint, DependencyKind.SOURCE_RESULT)), List.of(latePoint));
        var first = plan(new RepresentationPlan.Edit(location, RepresentationPlan.Action.LOAD_REFERENCE, null, BLOCK_POS,
                TypeRule.Binding.RECEIVER, 0, shared));
        var conflict = plan(edit(location, RepresentationPlan.Action.CAST_REFERENCE, "conflict", BLOCK_POS));
        var last = plan(new RepresentationPlan.Edit(location, RepresentationPlan.Action.LOAD_REFERENCE, null, BLOCK_POS,
                TypeRule.Binding.RECEIVER, 0, late));
        List<PlanConflict> expected = null;
        for (var order : permutations(first, conflict, last)) {
            var result = RepresentationPlanMerger.merge(order, Map.of());
            var evidence = result.conflicts().getFirst().contributors().stream().filter(value -> value.id().equals("shared")).findFirst().orElseThrow();
            assertEquals(2, evidence.dependencies().size());
            assertEquals(Set.of(shared.anchors().getFirst(), latePoint), Set.copyOf(evidence.anchors()));
            if (expected != null) assertEquals(expected, result.conflicts());
            expected = result.conflicts();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksExistingObservationEncodingBeforeFusion(boolean compatible) {
        var location = new InstructionLocation(METHOD, 7);
        var original = new MethodKey("java/util/Collection", "contains", "(Ljava/lang/Object;)Z");
        var helper = new MethodKey("sample/Rules", "encode", "(Lsample/BlockPos;)J");
        var previous = compatible ? helper : new MethodKey("sample/Rules", "other", helper.descriptor());
        var observation = plan(new RepresentationPlan.Edit(location, RepresentationPlan.Action.OBSERVE_VIEW_ELEMENTS, null, BLOCK_POS,
                TypeRule.Binding.RECEIVER, 0, new RepresentationPlan.Encoding(BLOCK_POS, previous), requirement("observation", BLOCK_POS)));
        var argument = plan(new RepresentationPlan.Argument(location, original, original, 0, BLOCK_POS, helper, "argument", requirement("argument", BLOCK_POS)));
        for (var order : List.of(List.of(observation, argument), List.of(argument, observation))) {
            var result = RepresentationPlanMerger.merge(order, Map.of());
            assertEquals(compatible, result.plan().isPresent());
            if (compatible) {
                assertEquals(new RepresentationPlan.Encoding(BLOCK_POS, helper), result.plan().orElseThrow().edits().getFirst().query());
            } else {
                assertEquals("Invocation replacement overlaps argument conversion", result.conflicts().getFirst().message());
                assertContributors(result.conflicts().getFirst(), Set.of("observation", "argument"));
            }
        }
    }

    private static <T> List<List<T>> permutations(T first, T second, T third) {
        return List.of(List.of(first, second, third), List.of(first, third, second), List.of(second, first, third),
                List.of(second, third, first), List.of(third, first, second), List.of(third, second, first));
    }

    private static RepresentationPlan.Edit edit(InstructionLocation location, RepresentationPlan.Action action,
            String id, Type representation) {
        return new RepresentationPlan.Edit(location, action, null, representation, TypeRule.Binding.RECEIVER, 0,
                requirement(id, representation));
    }

    private static RepresentationPlan.Argument argument(InstructionLocation location, MethodKey original,
            MethodKey implementation, String id) {
        return new RepresentationPlan.Argument(location, original, implementation, 0, BLOCK_POS, null, id,
                requirement(id, BLOCK_POS));
    }

    private static RepresentationPlan.Local local(String signature, String id) {
        return new RepresentationPlan.Local(METHOD, 0, "Ljava/util/Queue;", signature, requirement(id, BLOCK_POS));
    }

    private static MigrationEvidence requirement(String id, Type representation) {
        MethodKey target = new MethodKey("sample/Subject", id, "(J)I");
        var anchor = new ProgramPoint(METHOD, 7, "sample/Subject.run()V", "operand=0");
        var targetPoint = new ProgramPoint(target, -1, target.toString(), "parameter=0");
        return new MigrationEvidence(id, target, 0, representation.getDescriptor(),
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

    private static void assertContributors(PlanConflict conflict, Set<String> expected) {
        assertEquals(expected, conflict.contributors().stream().map(requirement -> requirement.target().name()).collect(Collectors.toSet()));
        assertTrue(conflict.contributors().stream().allMatch(requirement -> !requirement.dependencies().isEmpty()));
    }
}
