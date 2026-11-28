package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.analysis.container.ContainerSummary;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一个已完成契约组的只读输入。节点身份及指令编号在批次内固定。 */
final class RepresentationInput {
    private final ContainerAnalysis containers;
    private final Map<AbstractInsnNode, AnalysisSite> sites;
    private final Map<InstructionLocation, AbstractInsnNode> nodes;
    private final List<ContainerUse> uses;
    private final Map<InstructionLocation, ContainerUse> usesByLocation;
    private final Map<InstructionLocation, ProgramPoint> points;
    private final List<CallCandidate> calls;
    private final List<CallCandidate> unboxes;
    private final List<LocalCandidate> locals;
    final RepresentationWork work = new RepresentationWork();

    record CallCandidate(AnalysisSite site, MethodInsnNode call) { }
    record LocalCandidate(AnalysisSite site, OriginValue value) { }

    RepresentationInput(ContainerAnalysis containers, Set<MethodKey> targets) {
        this.containers = containers;
        Map<AbstractInsnNode, AnalysisSite> sites = new IdentityHashMap<>();
        Map<InstructionLocation, AbstractInsnNode> nodes = new LinkedHashMap<>();
        Map<InstructionLocation, ProgramPoint> points = new LinkedHashMap<>();
        List<CallCandidate> calls = new ArrayList<>();
        List<CallCandidate> unboxes = new ArrayList<>();
        List<LocalCandidate> locals = new ArrayList<>();
        for (ContainerFlow flow : containers.flows()) {
            flow.source().indices().forEach((node, index) -> {
                AnalysisSite site = new AnalysisSite(flow, index);
                InstructionLocation location = RepresentationQueries.location(site);
                sites.put(node, site);
                nodes.put(location, node);
                points.put(location, site.point());
            });
            for (int index = 0; index < flow.source().instructionCount(); index++) {
                if (!flow.source().reachable(index)) continue;
                var instruction = flow.source().instruction(index);
                AnalysisSite site = sites.get(instruction);
                if (instruction instanceof MethodInsnNode call) {
                    if (targets.stream().anyMatch(key -> key.name().equals(call.name)
                            && key.descriptor().equals(call.desc))) calls.add(new CallCandidate(site, call));
                    if (RepresentationQueries.isUnbox(call)) unboxes.add(new CallCandidate(site, call));
                } else if (instruction instanceof VarInsnNode variable) {
                    OriginValue value = switch (variable.getOpcode()) {
                        case Opcodes.LLOAD, Opcodes.ALOAD -> flow.source().local(index, variable.var);
                        case Opcodes.LSTORE, Opcodes.ASTORE -> flow.source().top(index);
                        default -> null;
                    };
                    if (value != null) locals.add(new LocalCandidate(site, value));
                }
            }
        }
        this.sites = Map.copyOf(sites);
        this.nodes = Map.copyOf(nodes);
        this.points = Map.copyOf(points);
        this.calls = List.copyOf(calls);
        this.unboxes = List.copyOf(unboxes);
        this.locals = List.copyOf(locals);
        uses = containers.accesses().stream().map(use -> new ContainerUse(new AnalysisSite(use.flow(), use.instruction()),
                use.call(), use.summary(), use.receivers())).toList();
        Map<InstructionLocation, ContainerUse> indexed = new LinkedHashMap<>();
        uses.forEach(use -> indexed.put(RepresentationQueries.location(use.site()), use));
        usesByLocation = Map.copyOf(indexed);
    }

    ContainerAnalysis containers() { return containers; }
    List<ContainerFlow> flows() { return containers.flows(); }
    Map<AbstractInsnNode, AnalysisSite> sites() { return sites; }
    List<ContainerUse> containerUses() { return uses; }
    List<CallCandidate> targetCalls() { return calls; }
    List<CallCandidate> unboxes() { return unboxes; }
    List<LocalCandidate> locals() { return locals; }
    Map<InstructionLocation, ProgramPoint> points() {
        work.pointReuses += points.size();
        return points;
    }

    ContainerSummary summary(AnalysisSite site, MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        ContainerUse use = usesByLocation.get(RepresentationQueries.location(site));
        return use == null ? null : use.summary();
    }

    Set<AbstractInsnNode> nodes(Set<InstructionLocation> locations) {
        return locations.stream().map(nodes::get).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    Map<AbstractInsnNode, RepresentationRule> sourceRules(Map<RuleUse, RepresentationRule> rules) {
        Map<AbstractInsnNode, RepresentationRule> selected = new IdentityHashMap<>();
        rules.forEach((use, rule) -> {
            if (use.kind() == TypeRule.Kind.SOURCE) selected.put(nodes.get(use.location()), rule);
        });
        return Map.copyOf(selected);
    }

}
