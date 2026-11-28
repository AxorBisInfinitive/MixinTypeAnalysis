package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.analysis.container.ContainerReference;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.container.JdkContainerSummaries;
import love.crysh.mixinta.analysis.container.StorageReferences;
import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.analysis.declaration.Migration;
import love.crysh.mixinta.analysis.declaration.RepresentationMigration;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.analysis.plan.RepresentationPlan;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.MigrationEvidence;
import love.crysh.mixinta.model.PlanAction;
import love.crysh.mixinta.model.PlanChange;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static love.crysh.mixinta.analysis.representation.RepresentationQueries.*;

/** 从已校验规则与最终批次事实生成编辑，保持输入指令图只读。 */
final class RepresentationPlanner {
    private final List<RepresentationMigration> migrations;
    private final RepresentationMigration migration;
    private final Map<MethodKey, Migration> targets;
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final RepresentationInput input;
    private final List<ContainerFlow> flows;
    private final Map<AbstractInsnNode, AnalysisSite> sites;
    private final Set<AbstractInsnNode> migrated;
    private final Set<StorageSlot> storages;
    private final Map<AbstractInsnNode, RepresentationRule> selected;
    private final Map<RuleUse, RepresentationRule> rules;
    private final ConstraintGraph graph;
    private final List<PropagationEdge> dependencies;
    private final ElementFacts batch;
    private final Type representation;
    private final List<RepresentationPlan.Edit> edits = new ArrayList<>();
    private final List<RepresentationPlan.Argument> arguments = new ArrayList<>();
    private final List<RepresentationPlan.Local> locals = new ArrayList<>();
    private final List<PlanChange> changes = new ArrayList<>();
    private final Map<MethodKey, RepresentationPlan.Bridge> bridges = new LinkedHashMap<>();

    RepresentationPlanner(List<RepresentationMigration> migrations, RepresentationInput input, ValidationResult validated,
            Map<MethodKey, Migration> targets, Map<String, ClassNode> classes, ClassHierarchy hierarchy, ElementFacts batch) {
        this.input = input;
        var result = validated.facts();
        this.migrations = List.copyOf(migrations);
        migration = migrations.getFirst();
        this.targets = targets;
        this.classes = classes;
        this.hierarchy = hierarchy;
        this.batch = batch;
        representation = migration.representation();
        flows = input.flows();
        sites = input.sites();
        migrated = input.nodes(result.migrated());
        storages = result.storages();
        selected = input.sourceRules(validated.rules());
        rules = validated.rules();
        graph = result.graph();
        dependencies = result.dependencies();
    }

    RepresentationPlan plan() {
        flows.forEach(this::plan);
        return new RepresentationPlan(edits, arguments, locals, List.copyOf(bridges.values()), changes, dependencies);
    }

    private RepresentationRule helper(TypeRule.Kind kind, AnalysisSite site) {
        return helper(kind, site, -1);
    }

    private RepresentationRule helper(TypeRule.Kind kind, AnalysisSite site, int parameter) {
        var rule = rules.get(new RuleUse(location(site), kind, parameter));
        if (rule == null) throw new IllegalStateException("Missing validated rule " + kind + " at " + site.describe());
        return rule;
    }

    private void plan(ContainerFlow flow) {
        for (int index = 0; index < flow.source().instructionCount(); index++) {
            if (!flow.source().reachable(index)) {
                continue;
            }
            AbstractInsnNode instruction = flow.source().instruction(index);
            AnalysisSite site = new AnalysisSite(flow, index);
            if (instruction instanceof VarInsnNode variable) {
                if (variable.getOpcode() == Opcodes.LLOAD && input.work.touches(flow.source().local(index, variable.var), migrated)) {
                    edit(site, RepresentationPlan.Action.LOAD_REFERENCE, null);
                } else if (variable.getOpcode() == Opcodes.LSTORE && input.work.touches(flow.source().top(index), migrated)) {
                    edit(site, RepresentationPlan.Action.STORE_REFERENCE, null);
                }
            } else if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("java/lang/Long") && input.work.touches(flow.source().top(index), migrated)) {
                if (!nullOnly(flow.source().top(index))) edit(site, RepresentationPlan.Action.CAST_REFERENCE, null);
            } else if (instruction instanceof MethodInsnNode call) {
                if (selected.containsKey(call)) {
                    edit(site, RepresentationPlan.Action.REPLACE_PRODUCER, selected.get(call));
                } else if (isBox(call) && effectiveValue(call)) {
                    if (migrated.contains(call)) edit(site, RepresentationPlan.Action.REMOVE_BOXING, null);
                } else if (isUnbox(call) && migrated.contains(call)) {
                    edit(site, RepresentationPlan.Action.REPLACE_UNBOXING, helper(TypeRule.Kind.READ, site));
                } else if (isMigratedObservation(flow, index, call)) {
                    var encoder = helper(TypeRule.Kind.ENCODE, site);
                    RepresentationPlan.Action action = JdkContainerSummaries.mapOwner(call.owner) ? RepresentationPlan.Action.OBSERVE_MAP_VALUES : RepresentationPlan.Action.OBSERVE_VIEW_ELEMENTS;
                    var source = sourceRequirement(graph.at(site.point(), -1));
                    edits.add(new RepresentationPlan.Edit(location(site), action, accessibleHelper(encoder.helper(), site, source),
                            representation, TypeRule.Binding.RECEIVER, 0, source));
                    changes.add(PlanChange.of(flow.source().key(), index, action.planAction(), migration.id(), encoder.id()));
                    int parameter = input.summary(site, call).observedParameter();
                    var value = flow.source().argument(index, call, parameter);
                    if (input.work.touches(value, migrated) && !nullOnly(value)) {
                        var queryEncoder = helper(TypeRule.Kind.ENCODE, site, parameter);
                        var querySource = sourceRequirement(List.of(ConstraintGraph.use(site.point(), parameter)));
                        arguments.add(new RepresentationPlan.Argument(location(site), symbol(call), symbol(call), parameter,
                                representation, accessibleHelper(queryEncoder.helper(), site, querySource), migration.id(), querySource));
                        changes.add(PlanChange.of(flow.source().key(), index, PlanAction.ENCODE_ARGUMENT, migration.id(), queryEncoder.id()));
                    }
                } else {
                    Type[] parameters = Type.getArgumentTypes(call.desc);
                    Migration target = target(call, targets, hierarchy);
                    for (int parameter = 0; parameter < parameters.length; parameter++) {
                        int parameterIndex = parameter;
                        boolean ownTarget = target != null && migrations.stream().anyMatch(declaration ->
                                declaration.target().equals(target.original()) && declaration.parameter() == parameterIndex);
                        boolean matchingTarget = target != null && target.representations().stream().anyMatch(declaration ->
                                declaration.parameter() == parameterIndex);
                        if (!input.work.touches(flow.source().argument(index, call, parameter), migrated) && !ownTarget) {
                            continue;
                        }
                        if (nullOnly(flow.source().argument(index, call, parameter))
                                && (parameters[parameter].getSort() == Type.OBJECT || parameters[parameter].getSort() == Type.ARRAY)) continue;
                        if (compatibleWrite(site, call, parameter)) {
                            continue;
                        }
                        var encoder = matchingTarget ? null : helper(TypeRule.Kind.ENCODE, site, parameter);
                        MethodKey original = symbol(call);
                        MethodKey replacement = target != null
                                ? new MethodKey(call.owner, call.name, target.implementation().descriptor()) : original;
                        var source = sourceRequirement(List.of(ConstraintGraph.use(site.point(), parameter)));
                        arguments.add(new RepresentationPlan.Argument(location(site), original, replacement, parameter,
                                representation, encoder == null ? null : accessibleHelper(encoder.helper(), site, source), migration.id(), source));
                        changes.add(PlanChange.of(flow.source().key(), index, matchingTarget ? PlanAction.REFERENCE_ARGUMENT : PlanAction.ENCODE_ARGUMENT,
                                migration.id(), encoder == null ? "" : encoder.id()));
                    }
                }
            } else if (instruction.getOpcode() == Opcodes.LRETURN && input.work.touches(flow.source().top(index), migrated)) {
                edit(site, RepresentationPlan.Action.ENCODE_RETURN, helper(TypeRule.Kind.ENCODE, site));
            }
        }
        planLocalMetadata(flow);
    }

    private void edit(AnalysisSite site, RepresentationPlan.Action action, RepresentationRule rule) {
        var source = sourceRequirement(graph.at(site.point(), -1));
        edits.add(new RepresentationPlan.Edit(location(site), action, rule == null ? null : accessibleHelper(rule.helper(), site, source), representation,
                rule == null ? TypeRule.Binding.RECEIVER : rule.binding(), rule == null ? 0 : rule.parameter(), source));
        changes.add(PlanChange.of(site.method(), site.index(), action.planAction(), migration.id(), rule == null ? "" : rule.id()));
    }

    private MethodKey accessibleHelper(MethodKey helper, AnalysisSite site, MigrationEvidence source) {
        ClassNode owner = classes.get(helper.owner());
        MethodNode method = ClassFiles.findMethod(owner, helper.name(), helper.descriptor());
        if (helper.owner().equals(site.method().owner()) || (method.access & Opcodes.ACC_PUBLIC) != 0) {
            return helper;
        }
        var existingBridge = bridges.get(helper);
        if (existingBridge != null) {
            bridges.put(helper, new RepresentationPlan.Bridge(existingBridge.method(), helper,
                    MigrationEvidence.merge(List.of(existingBridge.contributors(), List.of(source)))));
            return existingBridge.method();
        }
        return bridges.computeIfAbsent(helper, key -> {
            String name = "mixinta$rule$" + key.name() + "$" + owner.methods.indexOf(method);
            Set<String> names = new HashSet<>();
            owner.methods.forEach(existing -> names.add(existing.name));
            bridges.values().stream().filter(bridge -> bridge.method().owner().equals(owner.name))
                    .forEach(bridge -> names.add(bridge.method().name()));
            while (names.contains(name)) name += "$";
            MethodKey bridge = new MethodKey(owner.name, name, key.descriptor());
            changes.add(PlanChange.of(bridge, -1, PlanAction.GENERATE_RULE_BRIDGE, migration.id(), key.toString()));
            return new RepresentationPlan.Bridge(bridge, key, source);
        }).method();
    }

    private record LocalMetadata(String descriptor, String signature) { }

    private record LocalSignatureChange(String signature, Set<StorageSlot> storages) {
        private LocalSignatureChange {
            storages = Set.copyOf(storages);
        }
    }

    private void planLocalMetadata(ContainerFlow flow) {
        if (flow.source().method().localVariables == null) {
            return;
        }
        for (int local = 0; local < flow.source().method().localVariables.size(); local++) {
            var variable = flow.source().method().localVariables.get(local);
            int start = flow.source().indexOf(variable.start);
            int end = flow.source().indexOf(variable.end);
            Map<LocalMetadata, Set<ConstraintGraph.Subject>> candidates = new LinkedHashMap<>();
            for (int index = start; index < end; index++) {
                if (!flow.source().reachable(index) || variable.index >= flow.source().localCount(index)) {
                    continue;
                }
                OriginValue value = flow.source().local(index, variable.index);
                if ((variable.desc.equals("J") || variable.desc.equals("Ljava/lang/Long;")) && input.work.touches(value, migrated)) {
                    if (nullOnly(value)) continue;
                    Set<ConstraintGraph.Subject> origins = value.origins().stream().filter(migrated::contains).map(sites::get).filter(java.util.Objects::nonNull)
                            .map(origin -> ConstraintGraph.value(origin.point())).collect(java.util.stream.Collectors.toSet());
                    candidates.computeIfAbsent(new LocalMetadata(representation.getDescriptor(), null), ignored -> new LinkedHashSet<>()).addAll(origins);
                } else if (variable.signature != null) {
                    var change = localSignatureChange(variable.signature, flow.aliases().local(index, variable.index));
                    if (!change.signature().equals(variable.signature)) {
                        candidates.computeIfAbsent(new LocalMetadata(variable.desc, change.signature()), ignored -> new LinkedHashSet<>())
                                .addAll(change.storages().stream().map(ConstraintGraph::storage).toList());
                    }
                }
            }
            for (var entry : candidates.entrySet()) {
                locals.add(new RepresentationPlan.Local(flow.source().key(), local, entry.getKey().descriptor(), entry.getKey().signature(),
                        sourceRequirement(entry.getValue())));
            }
        }
    }

    private MigrationEvidence sourceRequirement(
            java.util.Collection<ConstraintGraph.Subject> anchors) {
        return new MigrationEvidence(migration.id(), migration.target(), migration.parameter(),
                representation.getDescriptor(), graph.evidence(anchors),
                anchors.stream().map(ConstraintGraph.Subject::point).distinct().sorted(java.util.Comparator.comparing(ProgramPoint::key)).toList());
    }

    private LocalSignatureChange localSignatureChange(String signature, StorageReferences references) {
        Map<Integer, Set<StorageSlot>> arguments = new LinkedHashMap<>();
        for (ContainerReference reference : references.references()) {
            for (StorageSlot slot : storages) {
                if (!slot.storage().equals(reference.storage()) || reference.channel() >= 0 && reference.channel() != slot.channel()) continue;
                int argument = reference.channel() >= 0 ? 0 : slot.channel();
                arguments.computeIfAbsent(argument, ignored -> new LinkedHashSet<>()).add(slot);
            }
        }
        return storageSignature(signature, arguments, references.references().stream().anyMatch(ContainerReference::entries));
    }

    private LocalSignatureChange storageSignature(String signature, Map<Integer, Set<StorageSlot>> arguments, boolean entries) {
        int open = signature.indexOf('<');
        if (open < 0 || arguments.isEmpty()) return new LocalSignatureChange(signature, Set.of());
        String owner = signature.substring(1, open);
        if (entries && !owner.equals("java/util/Map$Entry")) {
            int entry = signature.indexOf("Ljava/util/Map$Entry<");
            if (entry >= 0) {
                int depth = 0;
                int end = entry;
                for (; end < signature.length(); end++) {
                    if (signature.charAt(end) == '<') depth++;
                    if (signature.charAt(end) == '>') depth--;
                    if (signature.charAt(end) == ';' && depth == 0) break;
                }
                var change = storageSignature(signature.substring(entry, end + 1), arguments, true);
                return new LocalSignatureChange(signature.substring(0, entry) + change.signature() + signature.substring(end + 1), change.storages());
            }
            return new LocalSignatureChange(signature, Set.of());
        }
        int start = open + 1;
        int argument = 0;
        int depth = 0;
        Set<StorageSlot> changed = new LinkedHashSet<>();
        StringBuilder result = new StringBuilder(signature.substring(0, start));
        for (int index = start; index < signature.length(); index++) {
            char symbol = signature.charAt(index);
            if (symbol == '<') depth++;
            if (symbol == '>') depth--;
            if (depth == 0 && (symbol == ';' || symbol == '*')) {
                String value = signature.substring(start, index + 1);
                if (arguments.containsKey(argument)) {
                    if (value.equals("Ljava/lang/Long;") || value.equals("+Ljava/lang/Long;")
                            || value.equals("-Ljava/lang/Long;")) {
                        value = value.replace("Ljava/lang/Long;", representation.getDescriptor());
                        changed.addAll(arguments.get(argument));
                    }
                }
                result.append(value);
                argument++;
                start = index + 1;
            }
        }
        return new LocalSignatureChange(result.append(signature.substring(start)).toString(), changed);
    }

    private boolean effectiveValue(AbstractInsnNode instruction) {
        return migrated.contains(instruction) || batch != null && batch.value(location(sites.get(instruction)), representation);
    }

    private boolean compatibleWrite(AnalysisSite site, MethodInsnNode call, int parameter) {
        if (!isContainerWrite(site, call, parameter, input)) return false;
        OriginValue value = site.flow().source().argument(site.index(), call, parameter);
        if (nullOnly(value)) return true;
        var summary = input.summary(site, call);
        var references = site.flow().receiverReferences(site.index(), call);
        var slots = references.slots(summary.channel());
        return !slots.isEmpty() && references.unknownOrigins().isEmpty() && slots.stream()
                .allMatch(slot -> storages.contains(slot) || batch != null && batch.storage(slot, representation));
    }

    private static boolean nullOnly(OriginValue value) {
        return value != null && !value.origins().isEmpty() && value.origins().stream().allMatch(origin -> origin.getOpcode() == Opcodes.ACONST_NULL);
    }

    private boolean isMigratedObservation(ContainerFlow flow, int index, MethodInsnNode call) {
        ContainerSummary summary = input.summary(new AnalysisSite(flow, index), call);
        return summary != null && summary.observedParameter() >= 0 && touchesChannel(flow.receiverReferences(index, call), summary.channel());
    }

    private boolean touchesChannel(StorageReferences references, int channel) {
        return references.slots(channel).stream().anyMatch(storages::contains);
    }

}
