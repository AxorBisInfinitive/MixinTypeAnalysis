package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.StorageSlot;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.ProgramPoint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在当前阶段收集问题并把使用位置关联到来源证据。 */
final class RepresentationProblems {
    private final Map<AbstractInsnNode, AnalysisSite> sites;
    private final Set<AbstractInsnNode> migrated;
    private final ConstraintGraph graph;
    private final List<RepresentationIssue> issues = new ArrayList<>();
    private final Set<String> diagnosticKeys = new HashSet<>();

    RepresentationProblems(Map<AbstractInsnNode, AnalysisSite> sites, Set<AbstractInsnNode> migrated,
            ConstraintGraph graph, List<RepresentationIssue> initial) {
        this.sites = sites;
        this.migrated = migrated;
        this.graph = graph;
        for (RepresentationIssue issue : initial) candidateProblem(issue.code(), issue.site(), issue.parameter(),
                issue.message(), issue.candidates(), issue.storages(), issue.anchors(), issue.identity());
    }

    List<RepresentationIssue> issues() { return List.copyOf(issues); }

    void problem(String code, AnalysisSite site, String message) {
        candidateProblem(code, site, message, List.of());
    }

    void candidateProblem(String code, AnalysisSite site, String message, List<String> candidates) {
        candidateProblem(code, site, -1, message, candidates);
    }

    void candidateProblem(String code, AnalysisSite site, int parameter, String message, List<String> candidates) {
        candidateProblem(code, site, parameter, message, candidates, Set.of());
    }

    void candidateProblem(String code, AnalysisSite site, int parameter, String message, List<String> candidates, Set<StorageSlot> slots) {
        Set<ConstraintGraph.Subject> anchors = new LinkedHashSet<>();
        var use = ConstraintGraph.use(site.point(), parameter);
        if (code.equals("MISSING_PRODUCER_RULE") || code.equals("AMBIGUOUS_SOURCE_RULE")) {
            anchors.add(ConstraintGraph.value(site.point()));
        } else {
            anchors.add(use);
        }
        if (site.index() >= 0) {
            for (AbstractInsnNode origin : site.flow().source().consumed(site.flow().source().instruction(site.index()))) {
                if (migrated.contains(origin)) {
                    var value = ConstraintGraph.value(sites.get(origin).point());
                    graph.link(use, value, DependencyKind.SOURCE_RESULT);
                    graph.link(value, use, DependencyKind.SOURCE_RESULT);
                    anchors.add(value);
                }
            }
        }
        candidateProblem(code, site, parameter, message, candidates, slots, anchors, "");
    }

    void candidateProblem(String code, AnalysisSite site, int parameter, String message, List<String> candidates,
            Set<StorageSlot> slots, Set<ConstraintGraph.Subject> anchors, String identity) {
        int diagnosticParameter = parameter < 0 && site.index() < 0 ? formalParameter(site) : parameter;
        String key = code + " " + site.describe() + " parameter=" + diagnosticParameter + " identity=" + identity
                + " anchors=" + anchors.stream().map(ConstraintGraph.Subject::point).map(ProgramPoint::key).sorted().toList();
        if (diagnosticKeys.add(key)) {
            issues.add(new RepresentationIssue(code, site, diagnosticParameter, message, candidates, Set.copyOf(slots), Set.copyOf(anchors), identity));
        }
    }

    static int formalParameter(AnalysisSite site) {
        if (site.index() >= 0) return -1;
        int local = -1 - site.index();
        boolean isStatic = (site.flow().source().method().access & Opcodes.ACC_STATIC) != 0;
        if (!isStatic && local == 0) return -1;
        int slot = isStatic ? 0 : 1;
        Type[] parameters = Type.getArgumentTypes(site.flow().source().method().desc);
        for (int parameter = 0; parameter < parameters.length; parameter++) {
            if (slot == local) return parameter;
            slot += parameters[parameter].getSize();
        }
        return -1;
    }
}
