package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.model.InstructionLocation;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static love.crysh.mixinta.analysis.representation.RepresentationQueries.*;

/** 阶段完成后的语义事实，约束图与跨方法传播边分别保存。 */
record RepresentationFacts(Set<InstructionLocation> requested, Set<InstructionLocation> migrated,
        Set<StorageSlot> observedStorages, Set<StorageSlot> storages, List<PropagationEdge> dependencies,
        ConstraintGraph graph, Map<InstructionLocation, ProgramPoint> points, Set<InstructionLocation> nulls,
        Set<InstructionLocation> unresolvedValues, Set<StorageSlot> unresolvedStorages) {
    RepresentationFacts {
        requested = Set.copyOf(requested);
        migrated = Set.copyOf(migrated);
        observedStorages = Set.copyOf(observedStorages);
        storages = Set.copyOf(storages);
        dependencies = List.copyOf(dependencies);
        graph = graph.snapshot();
        points = Map.copyOf(points);
        nulls = Set.copyOf(nulls);
        unresolvedValues = Set.copyOf(unresolvedValues);
        unresolvedStorages = Set.copyOf(unresolvedStorages);
    }

    static RepresentationFacts capture(RepresentationInput input,
            Set<AbstractInsnNode> requested, Set<AbstractInsnNode> migrated, Set<StorageSlot> storages,
            java.util.Collection<PropagationEdge> dependencies, ConstraintGraph graph, List<RepresentationIssue> issues) {
        var sites = input.sites();
        Set<StorageSlot> unresolvedStorages = new LinkedHashSet<>();
        Set<InstructionLocation> unresolvedValues = new LinkedHashSet<>();
        for (RepresentationIssue issue : issues) {
            Set<StorageSlot> related = new LinkedHashSet<>(issue.storages());
            related.addAll(graph.storages(issue.anchors()));
            related.retainAll(storages);
            unresolvedStorages.addAll(related);
            graph.ancestors(issue.anchors()).stream().filter(subject -> subject.kind() == ConstraintGraph.Kind.VALUE)
                    .forEach(subject -> unresolvedValues.add(new InstructionLocation(subject.location().method(), subject.location().instruction())));
        }
        Map<InstructionLocation, ProgramPoint> points = new LinkedHashMap<>();
        sites.values().forEach(site -> points.put(location(site), site.point()));
        Set<InstructionLocation> values = requested.stream().map(origin -> location(sites.get(origin)))
                .collect(java.util.stream.Collectors.toSet());
        Set<InstructionLocation> nulls = requested.stream().filter(origin -> origin.getOpcode() == Opcodes.ACONST_NULL)
                .map(origin -> location(sites.get(origin))).collect(java.util.stream.Collectors.toSet());
        Set<InstructionLocation> migratedValues = migrated.stream().map(origin -> location(sites.get(origin)))
                .collect(java.util.stream.Collectors.toSet());
        Set<StorageSlot> observed = input.containerUses().stream().filter(use -> use.summary() != null)
                .flatMap(use -> use.receivers().slots(use.summary().channel()).stream()).collect(java.util.stream.Collectors.toSet());
        return new RepresentationFacts(values, migratedValues, observed, storages, orderedEdges(dependencies), graph, points,
                nulls, unresolvedValues, unresolvedStorages);
    }
}
