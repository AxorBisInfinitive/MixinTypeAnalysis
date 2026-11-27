package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerCallGraph;
import love.crysh.mixinta.analysis.container.ContainerEffects;
import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.analysis.container.ContainerRules;
import love.crysh.mixinta.analysis.container.MethodContainerSummary;
import love.crysh.mixinta.analysis.container.StorageId;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static love.crysh.mixinta.analysis.representation.RepresentationQueries.*;

/** 校验已传播值的使用、容器副作用和规则可访问性。 */
final class RepresentationValidator {
    private final PropagationResult propagation;
    private final RepresentationInput input;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final ContainerRules containers;
    private final List<ContainerFlow> flows;
    private final Map<AbstractInsnNode, AnalysisSite> sites;
    private final Set<AbstractInsnNode> migrated;
    private final Set<StorageSlot> storages;
    private final List<ContainerUse> containerUses;
    private final Map<MethodKey, MethodContainerSummary> methodSummaries;
    private final ContainerCallGraph callGraph;
    private final ConstraintGraph graph;
    private final Set<PropagationEdge> dependencies;
    private final Map<AbstractInsnNode, RepresentationRule> selected;
    private final Map<RuleUse, RepresentationRule> rules;
    private final ContainerEffects containerEffects;
    private final RepresentationProblems problems;
    private final Type representation;
    private final ElementFacts batch;

    RepresentationValidator(RepresentationMigration migration, PropagationResult propagation,
            RepresentationInput input, Map<MethodKey, Migration> targets, Map<String, ClassNode> classes,
            ClassHierarchy hierarchy, ElementFacts batch) {
        this.propagation = propagation;
        this.input = input;
        var facts = propagation.facts();
        this.migration = migration;
        this.targets = targets;
        this.classes = classes;
        this.hierarchy = hierarchy;
        this.batch = batch;
        representation = migration.representation();
        containers = new ContainerRules(migration.containers());
        flows = input.flows();
        sites = input.sites();
        migrated = input.nodes(facts.migrated());
        storages = facts.storages();
        selected = input.sourceRules(propagation.rules());
        containerUses = input.containerUses();
        callGraph = input.containers().callGraph();
        containerEffects = input.containers().effects();
        methodSummaries = input.containers().summaries();
        graph = facts.graph().mutableCopy();
        dependencies = new LinkedHashSet<>(facts.dependencies());
        rules = new LinkedHashMap<>(propagation.rules());
        problems = new RepresentationProblems(sites, migrated, graph, propagation.issues());
    }

    ValidationResult validate() {
        if (callGraph != null) {
            selected.forEach((instruction, rule) -> checkHelperAccess(rule.helper(), sites.get(instruction)));
            for (ContainerUse use : containerUses) {
                if (use.summary() != null && use.summary().observedParameter() >= 0
                        && use.receivers().slots(use.summary().channel()).stream().anyMatch(storages::contains)) {
                    helper(TypeRule.Kind.ENCODE, use.site());
                }
            }
            validateUses();
            validateContainerEffects();
        }
        return new ValidationResult(RepresentationFacts.capture(input, input.nodes(propagation.facts().requested()), migrated,
                storages, dependencies, graph, problems.issues()), rules, problems.issues());
    }

    private void validateContainerEffects() {
        for (ContainerEffects.Access access : containerEffects.accesses()) {
            List<StorageId> affected = access.storages().stream().filter(this::isSelectedStorage)
                    .sorted(java.util.Comparator.comparing(storage -> storagePoint(storage).key())).toList();
            if (affected.isEmpty()) {
                continue;
            }
            DependencyKind kind = access.kind().escape() == ContainerEffects.EscapeState.ESCAPED
                    ? DependencyKind.CONTAINER_ESCAPE : DependencyKind.CONTAINER_EXTERNAL_ACCESS;
            List<String> details = new ArrayList<>();
            for (StorageSlot slot : storages) {
                if (affected.contains(slot.storage())) graph.link(ConstraintGraph.storage(slot),
                        ConstraintGraph.use(access.point(), access.parameter()), kind);
            }
            for (StorageId storage : affected) {
                dependencies.add(new PropagationEdge(storagePoint(storage), access.point(), kind));
                List<Integer> channels = storages.stream().filter(slot -> slot.storage().equals(storage))
                        .map(StorageSlot::channel).sorted().toList();
                details.add(site(storagePoint(storage)).describe() + " allocation=" + storagePoint(storage).symbol()
                        + " channels=" + channels + " escape=" + containerEffects.state(storage));
            }
            String binding = access.parameter() >= 0 ? "argument=" + access.parameter()
                    : access.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION ? "receiver" : "value";
            problems.candidateProblem(access.kind().code(), site(access.point()), access.parameter(),
                    access.kind() + " " + binding + " requires modeled container effects, storage=" + String.join(", ", details),
                    summaryCandidates(site(access.point())), storages.stream().filter(slot -> affected.contains(slot.storage()))
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private List<String> summaryCandidates(AnalysisSite site) {
        if (site.index() < 0 || !(site.flow().source().instruction(site.index()) instanceof MethodInsnNode call)) {
            return List.of();
        }
        MethodContainerSummary summary = methodSummaries.get(symbol(call));
        if (summary == null) {
            return List.of();
        }
        long operations = summary.calls().stream().filter(value -> value.intrinsic() != null).count();
        return List.of("Callee summary " + summary.method() + " body=" + summary.hasBody()
                + " allocations=" + summary.allocations().size() + " returns=" + summary.returns().size()
                + " operations=" + operations + " calls=" + summary.calls().size() + " effects=" + summary.effects().size()
                + " composition=" + (callGraph.covered(site.point()) ? "resolved" : "unresolved")
                + " coverage=" + callGraph.coverage(site.point()));
    }

    private void validateUses() {
        for (ContainerFlow flow : flows) {
            for (int index = 0; index < flow.source().instructionCount(); index++) {
                if (!flow.source().reachable(index)) {
                    continue;
                }
                AbstractInsnNode instruction = flow.source().instruction(index);
                AnalysisSite site = new AnalysisSite(flow, index);
                if (instruction instanceof MethodInsnNode call) {
                    validateCall(site, call);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    checkReference(dynamic.bsm, site);
                    for (Object argument : dynamic.bsmArgs) {
                        checkReference(argument, site);
                    }
                    int arguments = Type.getArgumentTypes(dynamic.desc).length;
                    for (int parameter = 0; parameter < arguments; parameter++) {
                        OriginValue value = flow.source().stack(index, flow.source().stackSize(index) - arguments + parameter);
                        if (touches(value, migrated) && !nullOnly(value)) {
                            var use = ConstraintGraph.capture(site.point(), parameter);
                            linkUse(use, value);
                            problems.candidateProblem("UNSUPPORTED_VALUE_USE", site, parameter,
                                    pathFor(value) + " <- invokedynamic argument at " + site.describe(), List.of(), Set.of(),
                                    Set.of(use), "capture=" + parameter);
                        }
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    checkReference(constant.cst, site);
                } else if (instruction.getOpcode() == Opcodes.ARETURN || instruction.getOpcode() == Opcodes.PUTSTATIC
                        || instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.AASTORE) {
                    if (touches(flow.source().top(index), migrated) && !nullOnly(flow.source().top(index))) {
                        problems.problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.source().top(index)) + " <- reference sink at " + site.describe());
                    }
                } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.source().top(index), migrated)) {
                    requireEncoder(site);
                } else if (!(instruction instanceof VarInsnNode)
                        && instruction.getOpcode() != Opcodes.POP
                        && !(instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals("java/lang/Long"))
                        && flow.source().consumed(instruction).stream()
                                .anyMatch(origin -> origin.getOpcode() != Opcodes.ACONST_NULL && migrated.contains(origin))) {
                    for (MethodFlow.ConsumedInput input : flow.source().inputs(instruction)) {
                        if (input.origins().stream().anyMatch(origin -> origin.getOpcode() != Opcodes.ACONST_NULL
                                && migrated.contains(origin))) {
                            problemInput(site, instruction.getOpcode(), input);
                        }
                    }
                }
            }
        }
    }

    private void checkReference(Object constant, AnalysisSite site) {
        if (constant instanceof Handle handle && targets.containsKey(new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc()))) {
            problems.problem("UNSUPPORTED_METHOD_REFERENCE", site, "Target reference needs migration at " + site.describe());
        } else if (constant instanceof ConstantDynamic dynamic) {
            checkReference(dynamic.getBootstrapMethod(), site);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkReference(dynamic.getBootstrapMethodArgument(index), site);
            }
        }
    }

    private void validateCall(AnalysisSite site, MethodInsnNode call) {
        ContainerFlow flow = site.flow();
        int index = site.index();
        boolean receiverPresent = call.getOpcode() != Opcodes.INVOKESTATIC;
        if (receiverPresent) linkUse(ConstraintGraph.receiver(site.point()), flow.source().receiver(index, call));
        if (isUnbox(call) && migrated.contains(call)) {
            helper(TypeRule.Kind.READ, site);
        }
        if (receiverPresent && touches(flow.source().receiver(index, call), migrated)
                && !nullOnly(flow.source().receiver(index, call))
                && !(isUnbox(call) && migrated.contains(call))) {
            problemUse("UNSUPPORTED_REFERENCE_USE", site, ConstraintGraph.receiver(site.point()), flow.source().receiver(index, call),
                    "receiver", pathFor(flow.source().receiver(index, call)) + " <- receiver of " + symbol(call));
        }
        if (selected.containsKey(call) && ((receiverPresent && touches(flow.source().receiver(index, call), migrated))
                || java.util.stream.IntStream.range(0, Type.getArgumentTypes(call.desc).length)
                .anyMatch(parameter -> touches(flow.source().argument(index, call, parameter), migrated)))) {
            problems.problem("MIGRATED_SOURCE_INPUT_NOT_IMPLEMENTED", site, "Source replacement consumes another migrated value");
        }
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int parameter = 0; parameter < arguments.length; parameter++) {
            int parameterIndex = parameter;
            OriginValue value = flow.source().argument(index, call, parameter);
            linkUse(site, parameter, value);
            if (!touches(value, migrated)) {
                continue;
            }
            if (nullOnly(value) && (arguments[parameter].getSort() == Type.OBJECT || arguments[parameter].getSort() == Type.ARRAY)) continue;
            Migration target = target(call, targets, hierarchy);
            boolean matchingTarget = target != null && target.representations().stream().anyMatch(declaration ->
                    declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
            if (matchingTarget || isBox(call) && effectiveValue(call)
                    || compatibleWrite(site, call, parameter)) {
                continue;
            }
            if (arguments[parameter].equals(Type.LONG_TYPE) || isMapComparison(site, call, parameter, containers)) {
                helper(TypeRule.Kind.ENCODE, site, parameter);
            } else {
                problems.candidateProblem("UNSUPPORTED_REFERENCE_USE", site, parameter, pathFor(value) + " <- argument of " + symbol(call), List.of());
            }
        }
    }

    private boolean effectiveValue(AbstractInsnNode instruction) {
        return migrated.contains(instruction) || batch != null && batch.value(location(sites.get(instruction)), representation);
    }

    private boolean compatibleWrite(AnalysisSite site, MethodInsnNode call, int parameter) {
        if (!isContainerWrite(site, call, parameter, containers)) return false;
        OriginValue value = site.flow().source().argument(site.index(), call, parameter);
        if (nullOnly(value)) return true;
        var summary = summary(site, call, containers);
        var references = site.flow().receiverReferences(site.index(), call);
        var slots = references.slots(summary.channel());
        return !slots.isEmpty() && references.unknownOrigins().isEmpty() && slots.stream()
                .allMatch(slot -> storages.contains(slot) || batch != null && batch.storage(slot, representation));
    }

    private static boolean nullOnly(OriginValue value) {
        return value != null && !value.origins().isEmpty() && value.origins().stream().allMatch(origin -> origin.getOpcode() == Opcodes.ACONST_NULL);
    }

    private void linkUse(AnalysisSite site, int parameter, OriginValue value) {
        linkUse(ConstraintGraph.use(site.point(), parameter), value);
    }

    private void problemInput(AnalysisSite site, int opcode, MethodFlow.ConsumedInput input) {
        problemUse("UNSUPPORTED_VALUE_USE", site, ConstraintGraph.operand(site.point(), input.index()), input.origins(),
                "operand=" + input.index(), "Migrated value reaches opcode=" + opcode + " operand=" + input.index());
    }

    private void problemUse(String code, AnalysisSite site, ConstraintGraph.Subject use, OriginValue value,
            String identity, String message) {
        problemUse(code, site, use, value == null ? Set.of() : value.origins(), identity, message);
    }

    private void problemUse(String code, AnalysisSite site, ConstraintGraph.Subject use, Set<AbstractInsnNode> origins,
            String identity, String message) {
        Set<ConstraintGraph.Subject> anchors = new LinkedHashSet<>();
        anchors.add(use);
        for (AbstractInsnNode origin : ordered(origins)) {
            if (origin.getOpcode() == Opcodes.ACONST_NULL || !migrated.contains(origin)) continue;
            var value = ConstraintGraph.value(sites.get(origin).point());
            graph.link(use, value, DependencyKind.SOURCE_RESULT);
            graph.link(value, use, DependencyKind.SOURCE_RESULT);
            anchors.add(value);
        }
        problems.candidateProblem(code, site, -1, message, List.of(), Set.of(), anchors, identity);
    }

    private void linkUse(ConstraintGraph.Subject use, OriginValue value) {
        if (value == null) return;
        for (AbstractInsnNode origin : ordered(value.origins())) {
            var producer = ConstraintGraph.value(sites.get(origin).point());
            graph.link(use, producer, DependencyKind.SOURCE_RESULT);
            if (migrated.contains(origin)) graph.link(producer, use, DependencyKind.SOURCE_RESULT);
        }
    }

    private RepresentationRule helper(TypeRule.Kind kind, AnalysisSite site) {
        return helper(kind, site, -1);
    }

    private RepresentationRule helper(TypeRule.Kind kind, AnalysisSite site, int parameter) {
        if (kind == TypeRule.Kind.READ) graph.link(ConstraintGraph.use(site.point(), parameter), ConstraintGraph.value(site.point()), DependencyKind.UNBOXING);
        else if (site.index() >= 0 && site.flow().source().instruction(site.index()) instanceof MethodInsnNode call && parameter >= 0) {
            linkUse(site, parameter, site.flow().source().argument(site.index(), call, parameter));
        } else if (site.index() >= 0) linkUse(site, parameter, site.flow().source().top(site.index()));
        List<RepresentationRule> candidates = migration.rules().stream().filter(rule -> rule.kind() == kind).toList();
        if (candidates.size() != 1) {
            problems.candidateProblem(candidates.isEmpty() ? "MISSING_" + kind + "_RULE" : "AMBIGUOUS_" + kind + "_RULE", site, parameter,
                    "Expected one " + kind + " helper", candidates.stream().map(rule -> rule.id() + " applicable").toList());
            return null;
        }
        checkHelperAccess(candidates.getFirst().helper(), site, parameter);
        rules.put(new RuleUse(location(site), kind, parameter), candidates.getFirst());
        return candidates.getFirst();
    }

    private void checkHelperAccess(MethodKey helper, AnalysisSite site) {
        checkHelperAccess(helper, site, -1);
    }

    private void checkHelperAccess(MethodKey helper, AnalysisSite site, int parameter) {
        ClassNode owner = classes.get(helper.owner());
        if ((owner.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(owner.name).equals(packageName(site.method().owner()))) {
            problems.candidateProblem("RULE_HELPER_NOT_ACCESSIBLE", site, parameter, "Rule helper owner is inaccessible from " + site.method() + ": " + helper, List.of());
        }
        ClassNode type = hierarchy.get(representation.getInternalName());
        if ((type.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(type.name).equals(packageName(site.method().owner()))) {
            problems.candidateProblem("REPRESENTATION_TYPE_NOT_ACCESSIBLE", site, parameter, "Representation type is inaccessible from " + site.method() + ": " + type.name, List.of());
        }
    }

    private void requireEncoder(AnalysisSite site) {
        helper(TypeRule.Kind.ENCODE, site);
    }

    private boolean isSelectedStorage(StorageId storage) {
        return storages.stream().anyMatch(slot -> slot.storage().equals(storage));
    }

    private List<AbstractInsnNode> ordered(Set<AbstractInsnNode> origins) {
        return origins.stream().sorted((left, right) -> {
            AnalysisSite first = sites.get(left);
            AnalysisSite second = sites.get(right);
            int method = first.method().toString().compareTo(second.method().toString());
            return method == 0 ? Integer.compare(first.index(), second.index()) : method;
        }).toList();
    }

    private String pathFor(OriginValue value) {
        return ordered(value.origins()).stream().filter(migrated::contains).map(origin -> sites.get(origin).describe())
                .findFirst().orElse("value migration " + migration.id());
    }
    private AnalysisSite site(ProgramPoint point) {
        ContainerFlow flow = flows.stream().filter(candidate -> candidate.source().key().equals(point.method())).findFirst().orElseThrow();
        return new AnalysisSite(flow, point.instruction());
    }
}
