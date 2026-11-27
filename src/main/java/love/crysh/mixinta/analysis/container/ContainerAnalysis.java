package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.flow.MethodFlow;
import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.MethodKey;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只在同一输入快照和相同契约集合内共享容器调用图。 */
public final class ContainerAnalysis {
    private final List<ContainerFlow> flows;
    private final Map<MethodKey, MethodContainerSummary> summaries;
    private final ContainerCallGraph callGraph;
    private final List<Failure> failures;
    private final List<ContainerAccess> accesses;
    private final ContainerEffects effects;

    private ContainerAnalysis(List<ContainerFlow> flows, Map<MethodKey, MethodContainerSummary> summaries,
            ContainerCallGraph callGraph, List<Failure> failures, List<ContainerAccess> accesses, ContainerEffects effects) {
        this.flows = List.copyOf(flows);
        this.summaries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
        this.callGraph = callGraph;
        this.failures = List.copyOf(failures);
        this.accesses = List.copyOf(accesses);
        this.effects = effects;
    }

    public List<ContainerFlow> flows() { return flows; }
    public Map<MethodKey, MethodContainerSummary> summaries() { return summaries; }
    public ContainerCallGraph callGraph() { return callGraph; }
    public List<Failure> failures() { return failures; }
    public List<ContainerAccess> accesses() { return accesses; }
    public ContainerEffects effects() { return effects; }

    public record Failure(ContainerFlow flow, String message) { }

    public static Map<Set<ContainerContract>, ContainerAnalysis> analyzeAll(Map<String, ClassNode> classes,
            Set<MethodKey> targets, ClassHierarchy hierarchy,
            java.util.Collection<? extends java.util.Collection<ContainerContract>> contractGroups) {
        return analyzeAll(classes, targets, hierarchy, contractGroups, new AnalysisMetrics(), null);
    }

    public static Map<Set<ContainerContract>, ContainerAnalysis> analyzeAll(Map<String, ClassNode> classes,
            Set<MethodKey> targets, ClassHierarchy hierarchy,
            java.util.Collection<? extends java.util.Collection<ContainerContract>> contractGroups,
            AnalysisMetrics metrics, Set<MethodKey> scope) {
        Builder builder = new Builder(classes, targets, hierarchy, metrics, scope);
        Map<Set<ContainerContract>, ContainerAnalysis> completed = new LinkedHashMap<>();
        contractGroups.stream().map(Set::copyOf).distinct()
                .sorted(java.util.Comparator.comparing(Builder::groupKey))
                .forEach(group -> completed.put(group, builder.analyze(group)));
        return java.util.Collections.unmodifiableMap(completed);
    }

    private static final class Builder {
        private final Map<String, ClassNode> classes;
        private final Set<MethodKey> targets;
        private final ClassHierarchy hierarchy;
        private final Map<MethodKey, MethodFlow> sources = new LinkedHashMap<>();
        private final AnalysisMetrics metrics;
        private final Set<MethodKey> scope;

        Builder(Map<String, ClassNode> classes, Set<MethodKey> targets, ClassHierarchy hierarchy, AnalysisMetrics metrics, Set<MethodKey> scope) {
            this.classes = classes;
            this.targets = Set.copyOf(targets);
            this.hierarchy = hierarchy;
            this.metrics = metrics;
            this.scope = scope;
        }

        private static String groupKey(Set<ContainerContract> contracts) {
            return contracts.stream().map(Object::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        }

        private ContainerAnalysis analyze(Set<ContainerContract> contracts) {
            long containerStarted = System.nanoTime();
            long flowBefore = metrics.nanos().getOrDefault(AnalysisMetrics.Stage.DATAFLOW, 0L);
            ContainerRules containers = new ContainerRules(contracts.stream().sorted(java.util.Comparator.comparing(Object::toString)).toList());
            List<ContainerFlow> flows = new ArrayList<>();
            List<Failure> failures = new ArrayList<>();
            Map<MethodKey, MethodContainerSummary> summaries = new LinkedHashMap<>();
            for (ClassNode owner : classes.values().stream().sorted(java.util.Comparator.comparing(node -> node.name)).toList()) {
                for (MethodNode method : owner.methods) {
                    MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                    if (targets.contains(key) || containers.models(key)) continue;
                    if (scope != null && !scope.contains(key)) continue;
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        summaries.put(key, ContainerMethodAnalyzer.unavailable(key, method.access));
                        continue;
                    }
                    MethodFlow source = sources.get(key);
                    if (source == null) {
                        long started = System.nanoTime();
                        source = new MethodFlow(owner.name, method);
                        metrics.add(AnalysisMetrics.Stage.DATAFLOW, System.nanoTime() - started);
                        metrics.count(AnalysisMetrics.Count.ANALYZED_METHODS, 1);
                        sources.put(key, source);
                    } else {
                        metrics.count(AnalysisMetrics.Count.FLOW_CACHE_HITS, 1);
                    }
                    ContainerFlow flow = new ContainerFlow(source, containers);
                    metrics.count(AnalysisMetrics.Count.SUMMARY_CONTEXTS, 1);
                    flows.add(flow);
                    if (source.failure() != null) failures.add(new Failure(flow, source.failure().getMessage()));
                }
            }
            ContainerCallGraph graph = null;
            if (failures.isEmpty()) {
                graph = new ContainerCallGraph(classes, flows, hierarchy, containers);
                graph.solve();
                summaries.putAll(graph.summaries());
            }
            List<ContainerAccess> accesses = new ArrayList<>();
            if (graph != null) {
                for (ContainerFlow flow : flows) {
                    for (int index = 0; index < flow.source().instructionCount(); index++) {
                        if (flow.source().reachable(index) && flow.source().instruction(index) instanceof MethodInsnNode call
                                && call.getOpcode() != Opcodes.INVOKESTATIC) {
                            var receivers = flow.receiverReferences(index, call);
                            accesses.add(new ContainerAccess(flow, index, call, containers.resolve(call, receivers), receivers));
                        }
                    }
                }
            }
            ContainerEffects effects = ContainerEffects.collect(flows, summaries, graph, containers, accesses);
            long flowElapsed = metrics.nanos().getOrDefault(AnalysisMetrics.Stage.DATAFLOW, 0L) - flowBefore;
            metrics.add(AnalysisMetrics.Stage.CONTAINER, System.nanoTime() - containerStarted - flowElapsed);
            return new ContainerAnalysis(flows, summaries, graph, failures, accesses, effects);
        }

    }
}
