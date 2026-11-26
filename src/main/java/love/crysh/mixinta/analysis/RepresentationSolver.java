package love.crysh.mixinta.analysis;

import love.crysh.mixinta.TypeRule;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从固定 Mixin 后快照求解一组具有相同迁移身份的值与存储要求。 */
final class RepresentationSolver {
    record Analysis(RepresentationPlan plan, Set<RepresentationPlan.Location> values,
            Set<RepresentationPlan.Location> storages) {
    }

    private record Site(MethodFlow flow, int index) {
        MethodKey method() {
            return flow.key;
        }

        String describe() {
            return method() + " instruction=" + index;
        }

        String symbol() {
            return flow.point(index).symbol();
        }

        ProgramPoint point() {
            return new ProgramPoint(method(), index, symbol());
        }

        ProgramPoint point(String detail) {
            return new ProgramPoint(method(), index, symbol(), detail);
        }
    }

    private record Demand(AbstractInsnNode origin, ProgramPoint parent) {
    }

    private record ContainerUse(Site site, MethodInsnNode call, ContainerSummary summary,
            StorageReferences receivers) {
    }

    private final List<RepresentationMigration> migrations;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final List<MethodFlow> flows = new ArrayList<>();
    private final Map<AbstractInsnNode, Site> sites = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> migrated = new LinkedHashSet<>();
    private final Set<StorageSlot> storages = new LinkedHashSet<>();
    private final List<ContainerUse> containerUses = new ArrayList<>();
    private final ContainerEffects containerEffects = new ContainerEffects();
    private final Map<MethodKey, MethodContainerSummary> methodSummaries = new LinkedHashMap<>();
    private ContainerCallGraph callGraph;
    private final ArrayDeque<Demand> pending = new ArrayDeque<>();
    private final Set<AbstractInsnNode> requested = new HashSet<>();
    private final List<Diagnostic> diagnostics;
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> diagnosticKeys = new HashSet<>();
    private final Set<PropagationEdge> dependencies = new LinkedHashSet<>();
    private final Map<AbstractInsnNode, RepresentationMigration.Rule> selected = new IdentityHashMap<>();
    private final List<RepresentationPlan.Edit> edits = new ArrayList<>();
    private final List<RepresentationPlan.Argument> arguments = new ArrayList<>();
    private final List<RepresentationPlan.Local> locals = new ArrayList<>();
    private final List<PlanChange> changes = new ArrayList<>();
    private final Map<MethodKey, RepresentationPlan.Bridge> bridges = new LinkedHashMap<>();
    private final Type representation;

    private record Issue(String code, Site site, int parameter, String message, List<String> candidates) {
    }

    RepresentationSolver(Map<String, ClassNode> classes, List<RepresentationMigration> migrations,
            Map<MethodKey, Migration> targets, ClassHierarchy hierarchy, List<Diagnostic> diagnostics) {
        this.classes = classes;
        this.migrations = List.copyOf(migrations);
        this.migration = migrations.getFirst();
        this.targets = targets;
        this.hierarchy = hierarchy;
        this.diagnostics = diagnostics;
        this.representation = migration.representation();
    }

    Analysis analyze() {
        analyzeMethods();
        if (flows.stream().anyMatch(flow -> flow.frames == null)) {
            finishDiagnostics();
            return result();
        }
        callGraph = new ContainerCallGraph(classes, flows, hierarchy);
        callGraph.solve();
        methodSummaries.putAll(callGraph.summaries());
        dependencies.addAll(callGraph.dependencies());
        indexContainerUses();
        indexContainerEffects();
        seedTargets();
        int previous;
        do {
            previous = requested.size() + storages.size();
            solveDemands();
            propagateStorage();
            unifyLocalMerges();
        } while (!pending.isEmpty() || previous != requested.size() + storages.size());
        validateUses();
        validateContainerEffects();
        finishDiagnostics();
        if (issues.isEmpty()) {
            for (MethodFlow flow : flows) {
                plan(flow);
            }
        }
        return result();
    }

    private Analysis result() {
        return new Analysis(new RepresentationPlan(edits, arguments, locals, List.copyOf(bridges.values()), changes, orderedEdges(dependencies)),
                migrated.stream().map(origin -> location(sites.get(origin))).collect(java.util.stream.Collectors.toSet()),
                storages.stream().map(slot -> new RepresentationPlan.Location(slot.storage().method(), slot.storage().instruction()))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private void analyzeMethods() {
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                if (targets.containsKey(key)) {
                    continue;
                }
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    methodSummaries.put(key, ContainerMethodAnalyzer.unavailable(key, method.access));
                    continue;
                }
                MethodFlow flow = new MethodFlow(owner.name, method);
                flows.add(flow);
                try {
                    flow.analyze();
                } catch (AnalyzerException failure) {
                    problem("INVALID_PROPAGATION_INPUT", new Site(flow, -1), failure.getMessage());
                }
                flow.indices.forEach((instruction, index) -> sites.put(instruction, new Site(flow, index)));
            }
        }
    }

    private void indexContainerUses() {
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && call.getOpcode() != Opcodes.INVOKESTATIC) {
                    StorageReferences receiver = flow.receiverReferences(index, call);
                    containerUses.add(new ContainerUse(new Site(flow, index), call, ContainerSummary.resolve(call, receiver), receiver));
                }
            }
        }
    }

    private void indexContainerEffects() {
        for (MethodContainerSummary summary : methodSummaries.values()) {
            for (MethodContainerSummary.Effect effect : summary.effects()) {
                boolean ordinaryCall = effect.kind() == ContainerEffects.Kind.CALL_ARGUMENT
                        || effect.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION;
                if (ordinaryCall && callGraph.covered(effect.point())
                        || effect.kind() == ContainerEffects.Kind.RETURN && callGraph.internalReturn(summary.method())) {
                    continue;
                }
                if (effect.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION) {
                    Site site = site(effect.point());
                    if (site.flow.instructions[site.index] instanceof MethodInsnNode call && summary(site, call) != null) continue;
                }
                containerEffects.record(callGraph.resolve(summary.method(), effect.subject()).storages(),
                        effect.point(), effect.kind(), effect.parameter());
            }
        }
        for (ContainerUse use : containerUses) {
            if (use.summary() == null && ContainerSummary.resolve(use.call()) != null) {
                containerEffects.record(use.receivers().storages(), use.site().point(), ContainerEffects.Kind.UNKNOWN_OPERATION, -1);
            }
        }
    }

    private void validateContainerEffects() {
        for (ContainerEffects.Access access : containerEffects.accesses()) {
            List<StorageId> affected = access.storages().stream().filter(this::isSelectedStorage)
                    .sorted(java.util.Comparator.comparing(storage -> storagePoint(storage).key())).toList();
            if (affected.isEmpty()) {
                continue;
            }
            DependencyKind kind = access.kind().escape == ContainerEffects.EscapeState.ESCAPED
                    ? DependencyKind.CONTAINER_ESCAPE : DependencyKind.CONTAINER_EXTERNAL_ACCESS;
            List<String> details = new ArrayList<>();
            for (StorageId storage : affected) {
                dependencies.add(new PropagationEdge(storagePoint(storage), access.point(), kind));
                List<Integer> channels = storages.stream().filter(slot -> slot.storage().equals(storage))
                        .map(StorageSlot::channel).sorted().toList();
                details.add(site(storagePoint(storage)).describe() + " allocation=" + storagePoint(storage).symbol()
                        + " channels=" + channels + " escape=" + containerEffects.state(storage));
            }
            String binding = access.parameter() >= 0 ? "argument=" + access.parameter()
                    : access.kind() == ContainerEffects.Kind.UNKNOWN_OPERATION ? "receiver" : "value";
            candidateProblem(access.kind().code(), site(access.point()), access.parameter(),
                    access.kind() + " " + binding + " requires modeled container effects, storage=" + String.join(", ", details),
                    summaryCandidates(site(access.point())));
        }
    }

    private List<String> summaryCandidates(Site site) {
        if (site.index < 0 || !(site.flow.instructions[site.index] instanceof MethodInsnNode call)) {
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

    private void seedTargets() {
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null || !(flow.instructions[index] instanceof MethodInsnNode call)) {
                    continue;
                }
                Migration target = target(call);
                if (target == null) {
                    continue;
                }
                for (RepresentationMigration declaration : migrations) {
                    if (target.original().equals(declaration.target())) {
                        request(flow.argument(index, call, declaration.parameter()),
                                new Site(flow, index).point("parameter=" + declaration.parameter()),
                                DependencyKind.TARGET_ARGUMENT);
                    }
                }
            }
        }
    }

    private void request(SourceValue value, ProgramPoint parent, DependencyKind kind) {
        for (AbstractInsnNode origin : ordered(value.insns)) {
            dependencies.add(new PropagationEdge(parent, sites.get(origin).point(), kind));
            if (requested.add(origin)) {
                pending.add(new Demand(origin, parent));
            }
        }
    }

    private void solveDemands() {
        while (!pending.isEmpty()) {
            Demand demand = pending.removeFirst();
            AbstractInsnNode origin = demand.origin;
            Site site = sites.get(origin);
            if (origin instanceof MethodInsnNode call) {
                List<RepresentationMigration.Rule> candidates = migration.rules().stream()
                        .filter(rule -> rule.kind() == TypeRule.Kind.SOURCE && is(call, rule.source())).toList();
                if (!candidates.isEmpty()) {
                    migrated.add(origin);
                    if (candidates.size() == 1) {
                        selected.put(origin, candidates.getFirst());
                        checkHelperAccess(candidates.getFirst().helper(), site);
                    } else {
                        candidateProblem("AMBIGUOUS_SOURCE_RULE", site, "Multiple applicable source rules " + symbol(call),
                                candidates.stream().map(rule -> rule.id() + " applicable").toList());
                    }
                } else if (isBox(call)) {
                    migrated.add(origin);
                    request(site.flow.argument(site.index, call, 0), site.point(), DependencyKind.BOXING);
                } else if (isUnbox(call)) {
                    migrated.add(origin);
                    request(site.flow.receiver(site.index, call), site.point(), DependencyKind.UNBOXING);
                } else if (summary(site, call) != null && summary(site, call).readsElement()) {
                    ContainerSummary summary = summary(site, call);
                    migrated.add(origin);
                    requireStorage(site.flow.receiverReferences(site.index, call), site.point(), summary.channel());
                } else {
                    candidateProblem("MISSING_PRODUCER_RULE", site, "Missing source relation " + symbol(call),
                            migration.rules().stream().filter(rule -> rule.kind() == TypeRule.Kind.SOURCE)
                                    .map(rule -> rule.id() + " symbol does not match " + symbol(call)).toList());
                }
            } else if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                migrated.add(origin);
            } else {
                problem("MISSING_PRODUCER_RULE", site, "Missing source relation at " + site.describe());
            }
        }
    }

    private void requireStorage(StorageReferences references, ProgramPoint path, int channel) {
        for (StorageSlot slot : references.slots(channel)) {
            if (slot.channel() == 0 && ContainerSummary.mapOwner(Type.getType(slot.storage().descriptor()).getInternalName())) {
                problem("MAP_KEY_MIGRATION_NOT_IMPLEMENTED", site(path), "Map key representation requires equality and hash rules");
                continue;
            }
            storages.add(slot);
            dependencies.add(new PropagationEdge(path, storagePoint(slot.storage()), DependencyKind.CONTAINER_ALLOCATION));
        }
        for (ProgramPoint origin : references.unknownOrigins()) {
            Site site = site(origin);
            dependencies.add(new PropagationEdge(path, origin, DependencyKind.CONTAINER_ALLOCATION));
            candidateProblem("UNRESOLVED_CONTAINER", site,
                    path.key() + " <- unknown storage at " + origin.symbol(), summaryCandidates(site));
        }
    }

    private void propagateStorage() {
        for (ContainerUse use : containerUses) {
            ContainerSummary summary = use.summary();
            if (summary == null || !(summary.view() != null ? touchesStorage(use.receivers())
                    : touchesChannel(use.receivers(), summary.channel()))) {
                continue;
            }
            Site site = use.site();
            StorageReferences receiver = use.receivers();
            if (summary.readsElement() || summary.writesElement() || summary.observedParameter() >= 0) {
                requireStorage(receiver, site.point(), summary.channel());
            }
            for (StorageId storage : receiver.storages()) {
                if (isSelectedStorage(storage)) {
                    dependencies.add(new PropagationEdge(storagePoint(storage), site.point(),
                            dependencyKind(summary.operation())));
                }
            }
            if (summary.writesElement()) {
                request(site.flow.argument(site.index, use.call(), summary.elementParameter()), site.point(),
                        DependencyKind.CONTAINER_WRITE);
            }
            if (summary.readsElement()) {
                request(new SourceValue(1, use.call()), site.point(), DependencyKind.CONTAINER_READ);
            }
            if (summary.observedParameter() >= 0) {
                helper(TypeRule.Kind.ENCODE, site);
            }
        }
        // 所有读取方都要跟随共享元素迁移，包含最终传入旧消费者的读取。
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] != null && flow.instructions[index] instanceof MethodInsnNode call
                        && isUnbox(call) && touches(flow.receiver(index, call), migrated)) {
                    request(new SourceValue(2, call), firstMigratedPoint(flow.receiver(index, call)), DependencyKind.UNBOXING);
                }
            }
        }
    }

    private void unifyLocalMerges() {
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null || !(flow.instructions[index] instanceof VarInsnNode variable)) {
                    continue;
                }
                SourceValue value = switch (variable.getOpcode()) {
                    case Opcodes.LLOAD, Opcodes.ALOAD -> flow.frames[index].getLocal(variable.var);
                    case Opcodes.LSTORE, Opcodes.ASTORE -> flow.top(index);
                    default -> null;
                };
                if (value != null && touches(value, migrated)) {
                    for (AbstractInsnNode source : ordered(value.insns)) {
                        if (migrated.contains(source)) {
                            request(value, new Site(flow, index).point(), DependencyKind.LOCAL_MERGE);
                        }
                    }
                }
            }
        }
    }

    private void validateUses() {
        for (MethodFlow flow : flows) {
            for (int index = 0; index < flow.instructions.length; index++) {
                if (flow.frames[index] == null) {
                    continue;
                }
                AbstractInsnNode instruction = flow.instructions[index];
                Site site = new Site(flow, index);
                if (instruction instanceof MethodInsnNode call) {
                    validateCall(site, call);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    checkReference(dynamic.bsm, site);
                    for (Object argument : dynamic.bsmArgs) {
                        checkReference(argument, site);
                    }
                    int arguments = Type.getArgumentTypes(dynamic.desc).length;
                    Frame<SourceValue> frame = flow.frames[index];
                    for (int parameter = 0; parameter < arguments; parameter++) {
                        SourceValue value = frame.getStack(frame.getStackSize() - arguments + parameter);
                        if (touches(value, migrated)) {
                            problem("UNSUPPORTED_VALUE_USE", site, pathFor(value) + " <- invokedynamic argument at " + site.describe());
                        }
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    checkReference(constant.cst, site);
                } else if (instruction.getOpcode() == Opcodes.ARETURN || instruction.getOpcode() == Opcodes.PUTSTATIC
                        || instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.AASTORE) {
                    if (touches(flow.top(index), migrated)) {
                        problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.top(index)) + " <- reference sink at " + site.describe());
                    }
                } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                    requireEncoder(site);
                } else if (!(instruction instanceof VarInsnNode)
                        && instruction.getOpcode() != Opcodes.POP
                        && !(instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals("java/lang/Long"))
                        && flow.consumed.getOrDefault(instruction, Set.of()).stream().anyMatch(migrated::contains)) {
                    problem("UNSUPPORTED_VALUE_USE", site, "Migrated value reaches opcode=" + instruction.getOpcode());
                }
            }
        }
    }

    private void checkReference(Object constant, Site site) {
        if (constant instanceof Handle handle && targets.containsKey(new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc()))) {
            problem("UNSUPPORTED_METHOD_REFERENCE", site, "Target reference needs migration at " + site.describe());
        } else if (constant instanceof ConstantDynamic dynamic) {
            checkReference(dynamic.getBootstrapMethod(), site);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkReference(dynamic.getBootstrapMethodArgument(index), site);
            }
        }
    }

    private void validateCall(Site site, MethodInsnNode call) {
        MethodFlow flow = site.flow;
        int index = site.index;
        boolean receiverPresent = call.getOpcode() != Opcodes.INVOKESTATIC;
        if (isUnbox(call) && migrated.contains(call)) {
            helper(TypeRule.Kind.READ, site);
        }
        if (receiverPresent && touches(flow.receiver(index, call), migrated)
                && !(isUnbox(call) && migrated.contains(call))) {
            problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(flow.receiver(index, call)) + " <- receiver of " + symbol(call));
        }
        if (selected.containsKey(call) && ((receiverPresent && touches(flow.receiver(index, call), migrated))
                || java.util.stream.IntStream.range(0, Type.getArgumentTypes(call.desc).length)
                .anyMatch(parameter -> touches(flow.argument(index, call, parameter), migrated)))) {
            problem("MIGRATED_SOURCE_INPUT_NOT_IMPLEMENTED", site, "Source replacement consumes another migrated value");
        }
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int parameter = 0; parameter < arguments.length; parameter++) {
            int parameterIndex = parameter;
            SourceValue value = flow.argument(index, call, parameter);
            if (!touches(value, migrated)) {
                continue;
            }
            Migration target = target(call);
            boolean matchingTarget = target != null && migrations.stream().anyMatch(declaration ->
                    declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
            if (matchingTarget || isBox(call) && migrated.contains(call)
                    || isContainerWrite(site, call, parameter) && touchesChannel(flow.receiverReferences(index, call), summary(site, call).channel())) {
                continue;
            }
            if (arguments[parameter].equals(Type.LONG_TYPE) || isMapComparison(site, call, parameter)) {
                requireEncoder(site);
            } else {
                problem("UNSUPPORTED_REFERENCE_USE", site, pathFor(value) + " <- argument of " + symbol(call));
            }
        }
    }

    private RepresentationMigration.Rule helper(TypeRule.Kind kind, Site site) {
        List<RepresentationMigration.Rule> candidates = migration.rules().stream().filter(rule -> rule.kind() == kind).toList();
        if (candidates.size() != 1) {
            candidateProblem(candidates.isEmpty() ? "MISSING_" + kind + "_RULE" : "AMBIGUOUS_" + kind + "_RULE", site,
                    "Expected one " + kind + " helper", candidates.stream().map(rule -> rule.id() + " applicable").toList());
            return null;
        }
        checkHelperAccess(candidates.getFirst().helper(), site);
        return candidates.getFirst();
    }

    private void checkHelperAccess(MethodKey helper, Site site) {
        ClassNode owner = classes.get(helper.owner());
        if ((owner.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(owner.name).equals(packageName(site.method().owner()))) {
            problem("RULE_HELPER_NOT_ACCESSIBLE", site, "Rule helper owner is inaccessible from " + site.method() + ": " + helper);
        }
        ClassNode type = hierarchy.get(representation.getInternalName());
        if ((type.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(type.name).equals(packageName(site.method().owner()))) {
            problem("REPRESENTATION_TYPE_NOT_ACCESSIBLE", site, "Representation type is inaccessible from " + site.method() + ": " + type.name);
        }
    }

    private static String packageName(String owner) {
        return owner.substring(0, Math.max(0, owner.lastIndexOf('/')));
    }

    private void requireEncoder(Site site) {
        helper(TypeRule.Kind.ENCODE, site);
    }

    private void plan(MethodFlow flow) {
        for (int index = 0; index < flow.instructions.length; index++) {
            if (flow.frames[index] == null) {
                continue;
            }
            AbstractInsnNode instruction = flow.instructions[index];
            Site site = new Site(flow, index);
            if (instruction instanceof VarInsnNode variable) {
                if (variable.getOpcode() == Opcodes.LLOAD && touches(flow.frames[index].getLocal(variable.var), migrated)) {
                    edit(site, "load-reference", null);
                } else if (variable.getOpcode() == Opcodes.LSTORE && touches(flow.top(index), migrated)) {
                    edit(site, "store-reference", null);
                }
            } else if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("java/lang/Long") && touches(flow.top(index), migrated)) {
                edit(site, "cast-reference", null);
            } else if (instruction instanceof MethodInsnNode call) {
                if (selected.containsKey(call)) {
                    edit(site, "replace-producer", selected.get(call));
                } else if (isBox(call) && migrated.contains(call)) {
                    edit(site, "remove-boxing", null);
                } else if (isUnbox(call) && migrated.contains(call)) {
                    edit(site, "replace-unboxing", helper(TypeRule.Kind.READ, site));
                } else if (isMigratedObservation(flow, index, call)) {
                    var encoder = helper(TypeRule.Kind.ENCODE, site);
                    String action = ContainerSummary.mapOwner(call.owner) ? "observe-map-values" : "observe-view-elements";
                    edits.add(new RepresentationPlan.Edit(location(site), action, accessibleHelper(encoder.helper(), site),
                            representation, TypeRule.Binding.RECEIVER,
                            touches(flow.argument(index, call, 0), migrated) ? 1 : 0));
                    changes.add(new PlanChange(flow.key, index, action, migration.id(), encoder.id()));
                } else {
                    Type[] parameters = Type.getArgumentTypes(call.desc);
                    Migration target = target(call);
                    for (int parameter = 0; parameter < parameters.length; parameter++) {
                        int parameterIndex = parameter;
                        boolean matchingTarget = target != null && migrations.stream().anyMatch(declaration ->
                                declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
                        if (!touches(flow.argument(index, call, parameter), migrated) && !matchingTarget) {
                            continue;
                        }
                        if (isContainerWrite(site, call, parameter) && touchesChannel(flow.receiverReferences(index, call), summary(site, call).channel())) {
                            continue;
                        }
                        var encoder = matchingTarget ? null : helper(TypeRule.Kind.ENCODE, site);
                        MethodKey original = symbol(call);
                        MethodKey replacement = matchingTarget
                                ? new MethodKey(call.owner, call.name, target.implementation().descriptor()) : original;
                        arguments.add(new RepresentationPlan.Argument(location(site), original, replacement, parameter,
                                representation, encoder == null ? null : accessibleHelper(encoder.helper(), site), migration.id()));
                        changes.add(new PlanChange(flow.key, index, matchingTarget ? "reference-argument" : "encode-argument",
                                migration.id(), encoder == null ? "" : encoder.id()));
                    }
                }
            } else if (instruction.getOpcode() == Opcodes.LRETURN && touches(flow.top(index), migrated)) {
                edit(site, "encode-return", helper(TypeRule.Kind.ENCODE, site));
            }
        }
        planLocalMetadata(flow);
    }

    private void edit(Site site, String action, RepresentationMigration.Rule rule) {
        edits.add(new RepresentationPlan.Edit(location(site), action, rule == null ? null : accessibleHelper(rule.helper(), site), representation,
                rule == null ? TypeRule.Binding.RECEIVER : rule.binding(), rule == null ? 0 : rule.parameter()));
        changes.add(new PlanChange(site.method(), site.index, action, migration.id(), rule == null ? "" : rule.id()));
    }

    private MethodKey accessibleHelper(MethodKey helper, Site site) {
        ClassNode owner = classes.get(helper.owner());
        MethodNode method = ClassFiles.findMethod(owner, helper.name(), helper.descriptor());
        if (helper.owner().equals(site.method().owner()) || (method.access & Opcodes.ACC_PUBLIC) != 0) {
            return helper;
        }
        return bridges.computeIfAbsent(helper, key -> {
            String name = "mixinta$rule$" + key.name() + "$" + owner.methods.indexOf(method);
            Set<String> names = new HashSet<>();
            owner.methods.forEach(existing -> names.add(existing.name));
            bridges.values().stream().filter(bridge -> bridge.method().owner().equals(owner.name))
                    .forEach(bridge -> names.add(bridge.method().name()));
            while (names.contains(name)) name += "$";
            MethodKey bridge = new MethodKey(owner.name, name, key.descriptor());
            changes.add(new PlanChange(bridge, -1, "generate-rule-bridge", migration.id(), key.toString()));
            return new RepresentationPlan.Bridge(bridge, key);
        }).method();
    }

    private void planLocalMetadata(MethodFlow flow) {
        if (flow.method.localVariables == null) {
            return;
        }
        for (int local = 0; local < flow.method.localVariables.size(); local++) {
            var variable = flow.method.localVariables.get(local);
            int start = Arrays.asList(flow.instructions).indexOf(variable.start);
            int end = Arrays.asList(flow.instructions).indexOf(variable.end);
            for (int index = start; index < end; index++) {
                Frame<SourceValue> frame = flow.frames[index];
                if (frame == null || variable.index >= frame.getLocals()) {
                    continue;
                }
                SourceValue value = frame.getLocal(variable.index);
                if ((variable.desc.equals("J") || variable.desc.equals("Ljava/lang/Long;")) && touches(value, migrated)) {
                    locals.add(new RepresentationPlan.Local(flow.key, local, representation.getDescriptor(), null));
                    break;
                }
                if (variable.signature != null && touchesStorage(flow.aliases.local(index, variable.index))) {
                    locals.add(new RepresentationPlan.Local(flow.key, local, variable.desc,
                            storageSignature(variable.signature)));
                    break;
                }
            }
        }
    }

    private String storageSignature(String signature) {
        int open = signature.indexOf('<');
        if (open < 0) return signature;
        String owner = signature.substring(1, open);
        if (!ContainerSummary.mapOwner(owner) && !owner.equals("java/util/Map$Entry")) {
            int entry = signature.indexOf("Ljava/util/Map$Entry<");
            if (entry >= 0) {
                int depth = 0;
                int end = entry;
                for (; end < signature.length(); end++) {
                    if (signature.charAt(end) == '<') depth++;
                    if (signature.charAt(end) == '>') depth--;
                    if (signature.charAt(end) == ';' && depth == 0) break;
                }
                return signature.substring(0, entry) + storageSignature(signature.substring(entry, end + 1))
                        + signature.substring(end + 1);
            }
            return signature.replace("Ljava/lang/Long;", representation.getDescriptor());
        }
        int start = open + 1;
        int argument = 0;
        int depth = 0;
        for (int index = start; index < signature.length(); index++) {
            char symbol = signature.charAt(index);
            if (symbol == '<') depth++;
            if (symbol == '>') depth--;
            if (depth == 0 && (symbol == ';' || symbol == '*')) {
                if (argument == 1) {
                    String value = signature.substring(start, index + 1);
                    if (value.equals("Ljava/lang/Long;") || value.equals("+Ljava/lang/Long;")
                            || value.equals("-Ljava/lang/Long;")) {
                        return signature.substring(0, start)
                                + value.replace("Ljava/lang/Long;", representation.getDescriptor())
                                + signature.substring(index + 1);
                    }
                    return signature;
                }
                argument++;
                start = index + 1;
            }
        }
        return signature;
    }

    private Migration target(MethodInsnNode call) {
        if (targets.keySet().stream().noneMatch(key -> key.name().equals(call.name) && key.descriptor().equals(call.desc))) {
            return null;
        }
        return targets.get(hierarchy.resolve(call.owner, call.name, call.desc));
    }

    private static RepresentationPlan.Location location(Site site) {
        return new RepresentationPlan.Location(site.method(), site.index);
    }

    private ContainerSummary summary(Site site, MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        return ContainerSummary.resolve(call, site.flow.receiverReferences(site.index, call));
    }

    private boolean isContainerWrite(Site site, MethodInsnNode call, int parameter) {
        ContainerSummary summary = summary(site, call);
        return summary != null && summary.writesElement() && summary.elementParameter() == parameter;
    }

    private boolean isMapComparison(Site site, MethodInsnNode call, int parameter) {
        ContainerSummary summary = summary(site, call);
        return summary != null && (summary.keyParameter() == parameter || summary.observedParameter() == parameter);
    }

    private boolean isMigratedObservation(MethodFlow flow, int index, MethodInsnNode call) {
        ContainerSummary summary = summary(new Site(flow, index), call);
        return summary != null && summary.observedParameter() >= 0 && touchesChannel(flow.receiverReferences(index, call), summary.channel());
    }

    private static DependencyKind dependencyKind(ContainerOperation operation) {
        return switch (operation) {
            case ALLOCATE -> DependencyKind.CONTAINER_ALLOCATION;
            case VIEW -> DependencyKind.CONTAINER_VIEW;
            case WRITE, READ_WRITE -> DependencyKind.CONTAINER_WRITE;
            case READ, READ_REMOVE -> DependencyKind.CONTAINER_READ;
            case OBSERVE -> DependencyKind.CONTAINER_OBSERVE;
            case REMOVE_MATCH -> DependencyKind.CONTAINER_WRITE;
            case CLEAR -> DependencyKind.CONTAINER_CLEAR;
        };
    }

    private static boolean isBox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("valueOf") && call.desc.equals("(J)Ljava/lang/Long;");
    }

    private static boolean isUnbox(MethodInsnNode call) {
        return call.owner.equals("java/lang/Long") && call.name.equals("longValue") && call.desc.equals("()J");
    }

    private static boolean is(MethodInsnNode call, MethodKey key) {
        return call.owner.equals(key.owner()) && call.name.equals(key.name()) && call.desc.equals(key.descriptor());
    }

    private static MethodKey symbol(MethodInsnNode call) {
        return new MethodKey(call.owner, call.name, call.desc);
    }

    private static MethodInsnNode invoke(MethodKey key) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, key.owner(), key.name(), key.descriptor(), false);
    }

    private static boolean touches(SourceValue value, Set<AbstractInsnNode> selected) {
        return value != null && value.insns.stream().anyMatch(selected::contains);
    }

    private boolean touchesStorage(StorageReferences references) {
        return references != null && references.references().stream().anyMatch(reference -> {
            int channel = ContainerSummary.mapOwner(Type.getType(reference.storage().descriptor()).getInternalName()) ? 1 : 0;
            return storages.contains(reference.slot(channel));
        });
    }

    private boolean touchesChannel(StorageReferences references, int channel) {
        return references.slots(channel).stream().anyMatch(storages::contains);
    }

    private boolean isSelectedStorage(StorageId storage) {
        return storages.stream().anyMatch(slot -> slot.storage().equals(storage));
    }

    private Site site(ProgramPoint point) {
        MethodFlow flow = flows.stream().filter(candidate -> candidate.key.equals(point.method())).findFirst().orElseThrow();
        return new Site(flow, point.instruction());
    }

    private ProgramPoint storagePoint(StorageId storage) {
        return new ProgramPoint(storage.method(), storage.instruction(), Type.getType(storage.descriptor()).getInternalName());
    }

    private List<AbstractInsnNode> ordered(Set<AbstractInsnNode> origins) {
        return origins.stream().sorted((left, right) -> {
            Site first = sites.get(left);
            Site second = sites.get(right);
            int method = first.method().toString().compareTo(second.method().toString());
            return method == 0 ? Integer.compare(first.index, second.index) : method;
        }).toList();
    }

    private String pathFor(SourceValue value) {
        return ordered(value.insns).stream().filter(migrated::contains).map(origin -> sites.get(origin).describe())
                .findFirst().orElse("value migration " + migration.id());
    }

    private ProgramPoint firstMigratedPoint(SourceValue value) {
        return ordered(value.insns).stream().filter(migrated::contains).map(origin -> sites.get(origin).point())
                .findFirst().orElse(new ProgramPoint(null, -1, "value migration " + migration.id()));
    }

    private void problem(String code, Site site, String message) {
        candidateProblem(code, site, message, List.of());
    }

    private void candidateProblem(String code, Site site, String message, List<String> candidates) {
        candidateProblem(code, site, -1, message, candidates);
    }

    private void candidateProblem(String code, Site site, int parameter, String message, List<String> candidates) {
        String key = code + " " + site.describe() + " parameter=" + parameter;
        if (diagnosticKeys.add(key)) {
            issues.add(new Issue(code, site, parameter, message, candidates));
        }
    }

    private void finishDiagnostics() {
        for (Issue issue : issues) {
            Set<ProgramPoint> affected = new HashSet<>();
            affected.add(issue.site.point());
            boolean changed;
            do {
                changed = false;
                for (PropagationEdge edge : dependencies) {
                    if (affected.contains(edge.toPoint())) {
                        changed |= affected.add(edge.fromPoint());
                    }
                }
            } while (changed);
            List<PropagationEdge> relevant = orderedEdges(dependencies.stream()
                    .filter(edge -> affected.contains(edge.toPoint()) && affected.contains(edge.fromPoint())).toList());
            Type originalType = Type.getArgumentTypes(migration.target().descriptor())[migration.parameter()];
            RuleRequirement requirement = requirement(issue.code);
            String sourceType = originalType.getDescriptor();
            String targetType = migration.representation().getDescriptor();
            diagnostics.add(new Diagnostic(issue.code, issue.site.method(), Math.max(-1, issue.site.index), issue.parameter,
                    issue.message, migration.id(), relevant, issue.candidates,
                    migration.target(), migration.parameter(), sourceType, targetType, requirement,
                    dependencyPath(issue.site.point(), relevant), issue.site.symbol()));
        }
    }

    private RuleRequirement requirement(String code) {
        return switch (code) {
            case "MISSING_PRODUCER_RULE" -> new RuleRequirement("SOURCE", "RECEIVER", representationType());
            case "MISSING_READ_RULE" -> new RuleRequirement("READ", "RECEIVER", representationType());
            case "MISSING_ENCODE_RULE", "MISSING_BOUNDARY_CONVERSION" -> new RuleRequirement("ENCODE", "RECEIVER", representationType());
            case "UNRESOLVED_CONTAINER" -> new RuleRequirement("CONTAINER_SUMMARY", "RECEIVER", "container");
            default -> null;
        };
    }

    private String representationType() {
        String name = representation.getClassName();
        int separator = name.lastIndexOf('.');
        name = separator < 0 ? name : name.substring(separator + 1);
        int nested = name.lastIndexOf('$');
        name = nested < 0 ? name : name.substring(nested + 1);
        return "(" + name + ")" + name;
    }

    private List<DependencyStep> dependencyPath(ProgramPoint start, List<PropagationEdge> edges) {
        List<DependencyStep> path = new ArrayList<>();
        Set<ProgramPoint> visited = new LinkedHashSet<>();
        ArrayDeque<ProgramPoint> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            ProgramPoint point = pending.removeFirst();
            if (!visited.add(point)) {
                continue;
            }
            path.add(step(point));
            edges.stream().filter(edge -> edge.toPoint().equals(point))
                    .sorted(java.util.Comparator.comparing(edge -> edge.fromPoint().key()))
                    .forEach(edge -> pending.addLast(edge.fromPoint()));
        }
        return List.copyOf(path);
    }

    private static DependencyStep step(ProgramPoint point) {
        String symbol = point.symbol();
        String descriptor = symbol == null ? "" : symbol.substring(Math.max(0, symbol.indexOf('(')));
        return new DependencyStep(symbol, point.instruction(), descriptor);
    }

    private static List<PropagationEdge> orderedEdges(java.util.Collection<PropagationEdge> edges) {
        return edges.stream().sorted(java.util.Comparator.comparing((PropagationEdge edge) -> edge.fromPoint().key())
                .thenComparing(edge -> edge.toPoint().key()).thenComparing(edge -> edge.kind().name())).toList();
    }
}
