package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.analysis.declaration.RepresentationRule;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.model.InstructionLocation;

import org.objectweb.asm.tree.AbstractInsnNode;

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

    RepresentationInput(ContainerAnalysis containers) {
        this.containers = containers;
        Map<AbstractInsnNode, AnalysisSite> sites = new IdentityHashMap<>();
        Map<InstructionLocation, AbstractInsnNode> nodes = new LinkedHashMap<>();
        for (ContainerFlow flow : containers.flows()) {
            flow.source().indices().forEach((node, index) -> {
                sites.put(node, new AnalysisSite(flow, index));
                nodes.put(new InstructionLocation(flow.source().key(), index), node);
            });
        }
        this.sites = Map.copyOf(sites);
        this.nodes = Map.copyOf(nodes);
        uses = containers.accesses().stream().map(use -> new ContainerUse(new AnalysisSite(use.flow(), use.instruction()),
                use.call(), use.summary(), use.receivers())).toList();
    }

    ContainerAnalysis containers() { return containers; }
    List<ContainerFlow> flows() { return containers.flows(); }
    Map<AbstractInsnNode, AnalysisSite> sites() { return sites; }
    List<ContainerUse> containerUses() { return uses; }

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
