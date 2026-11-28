package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.analysis.AnalysisMetrics;
import love.crysh.mixinta.analysis.flow.OriginValue;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.model.DependencyKind;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;
import love.crysh.mixinta.model.PropagationEdge;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定批次中的容器引用求解。共享方法体统一形参表示，递归通过有限来源集合的单调合并收敛。 */
public final class ContainerCallGraph {
    private record Binding(MethodKey method, int parameter) {
    }

    record Dispatch(Set<MethodKey> targets, boolean covered, boolean pending, String reason) {
        Dispatch {
            targets = Set.copyOf(targets);
        }
    }

    private static final StorageReferences EMPTY = new StorageReferences(Set.of(), Set.of(), false);
    private final AnalysisMetrics metrics;
    private long rounds;
    private long callVisits;
    private long dispatches;
    private long factUpdates;
    private final ReferenceJoiner joiner = new ReferenceJoiner();
    private final Map<ProgramPoint, StorageReferences> unknowns = new LinkedHashMap<>();
    private final Map<String, ClassNode> classes;
    private final ClassHierarchy hierarchy;
    private final ContainerRules containers;
    private final Map<MethodKey, ContainerFlow> flows = new LinkedHashMap<>();
    private final Map<MethodKey, ContainerMethodAnalyzer> analyzers = new LinkedHashMap<>();
    private final Map<MethodKey, MethodContainerSummary> summaries = new LinkedHashMap<>();
    private final Map<ProgramPoint, MethodContainerSummary.Call> calls = new LinkedHashMap<>();
    private final Map<ProgramPoint, MethodContainerSummary.Call> derived = new LinkedHashMap<>();
    private final Map<ProgramPoint, Set<MethodKey>> potential = new LinkedHashMap<>();
    private final Map<Binding, StorageReferences> inputs = new LinkedHashMap<>();
    private final Map<ProgramPoint, StorageReferences> results = new LinkedHashMap<>();
    private final Set<MethodKey> openEntries = new LinkedHashSet<>();
    private final Map<MethodKey, Set<ProgramPoint>> incoming = new LinkedHashMap<>();
    private final Set<MethodKey> rooted = new LinkedHashSet<>();
    private final Map<MethodKey, Set<MethodKey>> virtualTargets = new LinkedHashMap<>();
    private final Map<String, List<ClassNode>> receiverClasses = new LinkedHashMap<>();
    private Map<ProgramPoint, Dispatch> finalDispatches;
    private List<PropagationEdge> finalDependencies;
    private long finalDispatchBuilds, finalDispatchHits, dependencyBuilds, valueResolutions, valueOrigins, recordedUnknowns;

    ContainerCallGraph(Map<String, ClassNode> classes, List<ContainerFlow> methods, ClassHierarchy hierarchy,
            ContainerRules containers, AnalysisMetrics metrics) {
        this.classes = classes;
        this.hierarchy = hierarchy;
        this.containers = containers;
        this.metrics = metrics;
        long summaryStarted = System.nanoTime();
        try {
            for (ContainerFlow flow : methods) {
                flows.put(flow.source().key(), flow);
                var analyzer = new ContainerMethodAnalyzer(flow, containers);
                analyzers.put(flow.source().key(), analyzer);
                summaries.put(flow.source().key(), analyzer.analyze());
            }
            for (ClassNode owner : classes.values()) {
                for (var method : owner.methods) {
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        MethodKey key = new MethodKey(owner.name, method.name, method.desc);
                        summaries.put(key, ContainerMethodAnalyzer.unavailable(key, method.access));
                    }
                }
            }
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_SUMMARIES, System.nanoTime() - summaryStarted);
        }
        long callsStarted = System.nanoTime();
        try {
            for (MethodContainerSummary summary : summaries.values()) {
                for (var call : summary.calls()) {
                    if (call.intrinsic() == null) {
                        calls.put(call.point(), call);
                        potential.put(call.point(), potentialTargets(call));
                        if (isReference(Type.getReturnType(call.target().descriptor()))) {
                            results.put(call.point(), EMPTY);
                        }
                    } else if (call.intrinsic().view() != null || call.target().owner().equals("java/util/Iterator")
                            && call.target().name().equals("next")) {
                        derived.put(call.point(), call);
                        results.put(call.point(), EMPTY);
                    }
                }
            }
            for (ContainerFlow flow : methods) {
                for (var instruction : flow.source().instructions()) {
                    if (instruction instanceof LdcInsnNode constant) {
                        reference(constant.cst);
                    } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        reference(dynamic.bsm);
                        for (Object argument : dynamic.bsmArgs) reference(argument);
                    }
                }
            }
            Set<MethodKey> callable = new LinkedHashSet<>();
            potential.values().forEach(callable::addAll);
            for (MethodContainerSummary summary : summaries.values()) {
                if (!summary.hasBody()) continue;
                List<Integer> parameters = new ArrayList<>();
                if ((summary.access() & Opcodes.ACC_STATIC) == 0) parameters.add(-1);
                summary.parameters().forEach(parameter -> parameters.add(parameter.index()));
                for (int parameter : parameters) {
                    Binding binding = new Binding(summary.method(), parameter);
                    inputs.put(binding, !callable.contains(summary.method()) || openEntries.contains(summary.method())
                            ? unknown(bindingPoint(binding)) : EMPTY);
                }
            }
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_CALLS, System.nanoTime() - callsStarted);
        }
    }

    void solve() {
        converge(true);
        long unknownStarted = System.nanoTime();
        try {
            // 无种子的递归环不能被解释为已知空引用或空容器。
            inputs.replaceAll((binding, facts) -> empty(facts) ? unknown(bindingPoint(binding)) : facts);
            results.replaceAll((point, facts) -> empty(facts) ? unknown(point) : facts);
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_UNKNOWN, System.nanoTime() - unknownStarted);
        }
        converge(false);
        long dispatchStarted = System.nanoTime();
        try {
            Map<ProgramPoint, Dispatch> completed = new LinkedHashMap<>();
            for (var call : calls.values()) {
                completed.put(call.point(), dispatch(call));
                finalDispatchBuilds++;
            }
            finalDispatches = Map.copyOf(completed);
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_FINAL_DISPATCH, System.nanoTime() - dispatchStarted);
        }
        long relationshipsStarted = System.nanoTime();
        try {
            for (var call : calls.values()) {
                for (MethodKey target : dispatch(call).targets()) {
                    incoming.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(call.point());
                }
            }
            ArrayDeque<MethodKey> pending = new ArrayDeque<>();
            flows.keySet().stream().filter(method -> !incoming.containsKey(method)).forEach(pending::add);
            while (!pending.isEmpty()) {
                MethodKey method = pending.removeFirst();
                if (!rooted.add(method)) continue;
                for (var call : summaries.get(method).calls()) {
                    if (calls.containsKey(call.point())) pending.addAll(dispatch(call).targets().stream().filter(flows::containsKey).toList());
                }
            }
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_RELATIONSHIPS, System.nanoTime() - relationshipsStarted);
        }
        long aliasesStarted = System.nanoTime();
        try {
            for (ContainerFlow flow : flows.values()) {
                var aliases = new ContainerAliases(flow.source(), source -> references(flow.source().key(), source));
                flow.initializeAliases(aliases);
                metrics.count(AnalysisMetrics.Count.ALIAS_POSITIONS, aliases.positionCount());
                metrics.count(AnalysisMetrics.Count.ALIAS_VALUES, aliases.valueCount());
                metrics.count(AnalysisMetrics.Count.ALIAS_CAPACITY_HINTS, flow.source().originCount());
            }
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_ALIASES, System.nanoTime() - aliasesStarted);
        }
        long dependenciesStarted = System.nanoTime();
        try {
            finalDependencies = buildDependencies();
        } finally {
            metrics.add(AnalysisMetrics.Stage.CONTAINER_DEPENDENCIES, System.nanoTime() - dependenciesStarted);
        }
    }

    public void recordMetrics() {
        joiner.recordMetrics(metrics);
        metrics.count(AnalysisMetrics.Count.UNKNOWN_VALUES, unknowns.size() - recordedUnknowns);
        recordedUnknowns = unknowns.size();
        metrics.count(AnalysisMetrics.Count.FINAL_DISPATCH_BUILDS, finalDispatchBuilds);
        metrics.count(AnalysisMetrics.Count.FINAL_DISPATCH_HITS, finalDispatchHits);
        metrics.count(AnalysisMetrics.Count.DEPENDENCY_BUILDS, dependencyBuilds);
        metrics.count(AnalysisMetrics.Count.VALUE_RESOLUTIONS, valueResolutions);
        metrics.count(AnalysisMetrics.Count.VALUE_ORIGINS, valueOrigins);
        finalDispatchBuilds = finalDispatchHits = dependencyBuilds = valueResolutions = valueOrigins = 0;
    }

    private void converge(boolean first) {
        long started = System.nanoTime();
        long dispatchBefore = dispatches;
        rounds = callVisits = factUpdates = 0;
        try {
            boolean changed;
            do {
                rounds++;
                changed = false;
                for (var call : calls.values()) {
                    callVisits++;
                    Dispatch dispatch = dispatch(call);
                    for (MethodKey target : dispatch.targets()) {
                        MethodContainerSummary summary = summaries.get(target);
                        if (summary == null || !summary.hasBody()) continue;
                        if (call.receiver() != null) {
                            changed |= bind(new Binding(target, -1), resolve(call.point().method(), call.receiver()));
                        }
                        for (int parameter = 0; parameter < call.arguments().size(); parameter++) {
                            changed |= bind(new Binding(target, parameter), resolve(call.point().method(), call.arguments().get(parameter)));
                        }
                    }
                    if (results.containsKey(call.point())) {
                        StorageReferences returned = EMPTY;
                        for (MethodKey target : dispatch.targets()) {
                            MethodContainerSummary summary = summaries.get(target);
                            if (summary != null && summary.hasBody()) {
                                for (var value : summary.returns()) returned = join(returned, resolve(target, value.value()));
                            }
                        }
                        if (!dispatch.covered() && !dispatch.pending()) returned = join(returned, unknown(call.point()));
                        StorageReferences previous = results.get(call.point());
                        StorageReferences next = join(previous, returned);
                        if (!previous.equals(next)) {
                            results.put(call.point(), next);
                            factUpdates++;
                            changed = true;
                        }
                    }
                }
                for (var call : derived.values()) {
                    callVisits++;
                    StorageReferences receiver = resolve(call.point().method(), call.receiver());
                    StorageReferences returned = EMPTY;
                    if (!empty(receiver)) {
                        ContainerView view = call.intrinsic().view();
                        if (view == null) view = ContainerView.ENTRY;
                        returned = view.derive(receiver, call.point(), call.intrinsic().channel());
                    }
                    StorageReferences previous = results.get(call.point());
                    StorageReferences next = join(previous, returned);
                    if (!previous.equals(next)) {
                        results.put(call.point(), next);
                        factUpdates++;
                        changed = true;
                    }
                }
            } while (changed);
        } finally {
            metrics.add(first ? AnalysisMetrics.Stage.CONTAINER_FIRST_CONVERGENCE
                    : AnalysisMetrics.Stage.CONTAINER_SECOND_CONVERGENCE, System.nanoTime() - started);
            metrics.count(first ? AnalysisMetrics.Count.FIRST_ROUNDS : AnalysisMetrics.Count.SECOND_ROUNDS, rounds);
            metrics.count(first ? AnalysisMetrics.Count.FIRST_CALL_VISITS : AnalysisMetrics.Count.SECOND_CALL_VISITS, callVisits);
            metrics.count(first ? AnalysisMetrics.Count.FIRST_DISPATCHES : AnalysisMetrics.Count.SECOND_DISPATCHES, dispatches - dispatchBefore);
            metrics.count(first ? AnalysisMetrics.Count.FIRST_FACT_UPDATES : AnalysisMetrics.Count.SECOND_FACT_UPDATES, factUpdates);
        }
    }

    private boolean bind(Binding binding, StorageReferences value) {
        StorageReferences previous = inputs.get(binding);
        if (previous == null) return false;
        StorageReferences next = join(previous, value);
        if (previous.equals(next)) return false;
        inputs.put(binding, next);
        factUpdates++;
        return true;
    }

    public StorageReferences references(MethodKey method, OriginValue source) {
        return resolve(method, analyzers.get(method).value(source, flows.get(method).source().point(-1)));
    }

    public StorageReferences resolve(MethodKey method, MethodContainerSummary.Value value) {
        valueResolutions++;
        valueOrigins += value.origins().size();
        StorageReferences result = new StorageReferences(Set.of(), Set.of(), value.nullable());
        for (var origin : value.origins()) {
            StorageReferences reference = switch (origin) {
                case MethodContainerSummary.Allocation allocation -> new StorageReferences(Set.of(new ContainerReference(
                        allocation.storage(), -1, ContainerReference.Kind.ROOT)), Set.of(), false);
                case MethodContainerSummary.Parameter parameter -> inputs.getOrDefault(new Binding(method, parameter.index()), EMPTY);
                case MethodContainerSummary.Receiver ignored -> inputs.getOrDefault(new Binding(method, -1), EMPTY);
                case MethodContainerSummary.Producer producer -> {
                    StorageReferences facts = results.get(producer.point());
                    yield facts == null ? unknown(producer.point()) : facts;
                }
            };
            result = join(result, reference);
        }
        return result;
    }

    public Map<MethodKey, MethodContainerSummary> summaries() {
        return Map.copyOf(summaries);
    }

    public boolean covered(ProgramPoint point) {
        var call = calls.get(point);
        return call != null && dispatch(call).covered();
    }

    public boolean internalReturn(MethodKey method) {
        return incoming.containsKey(method) && rooted.contains(method) && !openEntries.contains(method);
    }

    public String coverage(ProgramPoint point) {
        var call = calls.get(point);
        return call == null ? "intrinsic or external boundary" : dispatch(call).reason();
    }

    private Dispatch dispatch(MethodContainerSummary.Call call) {
        if (finalDispatches != null) {
            finalDispatchHits++;
            return finalDispatches.get(call.point());
        }
        dispatches++;
        if (call.receiver() != null && call.intrinsic() == null) {
            MethodInsnNode instruction = (MethodInsnNode) flows.get(call.point().method()).source().instruction(call.point().instruction());
            StorageReferences receiver = resolve(call.point().method(), call.receiver());
            if (!receiver.references().isEmpty() && receiver.unknownOrigins().isEmpty()
                    && containers.resolve(instruction, receiver) != null) {
                // 接收者视图确定的操作由契约处理，避免将存储绑定到无关的接口实现。
                return new Dispatch(Set.of(), true, false, "modeled receiver operation");
            }
        }
        MethodKey declared = declaredTarget(call);
        if (call.opcode() == Opcodes.INVOKESTATIC || call.opcode() == Opcodes.INVOKESPECIAL || fixed(declared)) {
            Set<MethodKey> targets = declared == null ? Set.of() : Set.of(declared);
            boolean covered = body(declared);
            return new Dispatch(targets, covered, false, covered ? "complete fixed dispatch" : "callee body unavailable");
        }
        StorageReferences receiver = resolve(call.point().method(), call.receiver());
        if (empty(receiver)) return new Dispatch(Set.of(), false, true, "receiver pending");
        Set<String> types = new LinkedHashSet<>();
        for (ContainerReference reference : receiver.references()) {
            if (reference.kind() == ContainerReference.Kind.ROOT) types.add(Type.getType(reference.storage().descriptor()).getInternalName());
        }
        boolean precise = receiver.references().stream().allMatch(reference -> reference.kind() == ContainerReference.Kind.ROOT);
        for (ProgramPoint origin : receiver.unknownOrigins()) {
            ContainerFlow flow = flows.get(origin.method());
            if (flow != null && origin.instruction() >= 0
                    && flow.source().instruction(origin.instruction()) instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                types.add(type.desc);
            } else {
                precise = false;
            }
        }
        if (!precise) return new Dispatch(potential.getOrDefault(call.point(), Set.of()), false, false, "receiver implementation set is open");
        Set<MethodKey> targets = new LinkedHashSet<>();
        boolean complete = true;
        for (String type : types) {
            MethodKey target = resolveTarget(type, call.target());
            if (target != null) targets.add(target);
            complete &= body(target);
        }
        return new Dispatch(targets, complete, false, complete ? "complete receiver dispatch" : "receiver implementation body unavailable");
    }

    private Set<MethodKey> potentialTargets(MethodContainerSummary.Call call) {
        MethodKey declared = declaredTarget(call);
        if (call.opcode() == Opcodes.INVOKESTATIC || call.opcode() == Opcodes.INVOKESPECIAL || fixed(declared)) {
            return declared == null ? Set.of() : Set.of(declared);
        }
        return virtualTargets.computeIfAbsent(call.target(), signature -> {
            Set<MethodKey> result = new LinkedHashSet<>();
            if (declared != null) result.add(declared);
            List<ClassNode> candidates = receiverClasses.computeIfAbsent(signature.owner(), owner -> classes.values().stream()
                    .filter(candidate -> (candidate.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0
                            && mayBeSubtype(owner, candidate.name)).toList());
            for (ClassNode candidate : candidates) {
                MethodKey target = resolveTarget(candidate.name, signature);
                if (target != null) result.add(target);
            }
            return Set.copyOf(result);
        });
    }

    private MethodKey declaredTarget(MethodContainerSummary.Call call) {
        if (!classes.containsKey(call.target().owner())) return null;
        try {
            return hierarchy.resolve(call.target().owner(), call.target().name(), call.target().descriptor());
        } catch (IllegalArgumentException missing) {
            return null;
        }
    }

    private MethodKey resolveTarget(String owner, MethodKey signature) {
        try {
            MethodKey declaration = hierarchy.resolve(signature.owner(), signature.name(), signature.descriptor());
            MethodKey target = hierarchy.resolveVirtual(owner, signature.name(), signature.descriptor());
            if (declaration != null && target != null) {
                var method = ClassFiles.findMethod(hierarchy.get(declaration.owner()), declaration.name(), declaration.descriptor());
                // 跨包同名方法可能没有覆盖 package-private 声明，缺少覆盖链证明时保留未知边界。
                if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0
                        && !packageName(declaration.owner()).equals(packageName(target.owner()))) return null;
            }
            return target;
        } catch (IllegalArgumentException missing) {
            return null;
        }
    }

    private static String packageName(String owner) {
        return owner.substring(0, Math.max(0, owner.lastIndexOf('/')));
    }

    private boolean fixed(MethodKey target) {
        if (target == null) return false;
        ClassNode owner = classes.get(target.owner());
        if (owner == null) return false;
        var method = ClassFiles.findMethod(owner, target.name(), target.descriptor());
        return (owner.access & Opcodes.ACC_FINAL) != 0
                || (method.access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0;
    }

    private boolean body(MethodKey target) {
        return target != null && summaries.containsKey(target) && summaries.get(target).hasBody();
    }

    private void reference(Object value) {
        if (value instanceof Handle handle) {
            if (handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                MethodKey signature = new MethodKey(handle.getOwner(), handle.getName(), handle.getDesc());
                for (MethodKey candidate : summaries.keySet()) {
                    if (candidate.name().equals(signature.name()) && candidate.descriptor().equals(signature.descriptor())
                            && mayBeSubtype(signature.owner(), candidate.owner())) openEntries.add(candidate);
                }
            }
        } else if (value instanceof ConstantDynamic dynamic) {
            reference(dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) reference(dynamic.getBootstrapMethodArgument(index));
        }
    }

    public List<PropagationEdge> dependencies() {
        return finalDependencies == null ? buildDependencies() : finalDependencies;
    }

    private List<PropagationEdge> buildDependencies() {
        dependencyBuilds++;
        Set<PropagationEdge> edges = new LinkedHashSet<>();
        for (var call : derived.values()) {
            originEdges(edges, call.point(), call.point().method(), call.receiver(), DependencyKind.CONTAINER_VIEW);
        }
        for (var call : calls.values()) {
            for (MethodKey target : dispatch(call).targets()) {
                if (!body(target)) continue;
                if (call.receiver() != null) bindEdges(edges, new Binding(target, -1), call, call.receiver());
                for (int parameter = 0; parameter < call.arguments().size(); parameter++) {
                    bindEdges(edges, new Binding(target, parameter), call, call.arguments().get(parameter));
                }
                for (var returned : summaries.get(target).returns()) {
                    edges.add(new PropagationEdge(call.point(), returned.point(), DependencyKind.CONTAINER_RETURN));
                    originEdges(edges, returned.point(), target, returned.value(), DependencyKind.CONTAINER_RETURN);
                }
            }
        }
        return List.copyOf(edges);
    }

    private void bindEdges(Set<PropagationEdge> edges, Binding binding, MethodContainerSummary.Call call, MethodContainerSummary.Value value) {
        ProgramPoint argument = new ProgramPoint(call.point().method(), call.point().instruction(), call.point().symbol(),
                "parameter=" + binding.parameter());
        edges.add(new PropagationEdge(bindingPoint(binding), argument, DependencyKind.CONTAINER_CALL_ARGUMENT));
        originEdges(edges, argument, call.point().method(), value, DependencyKind.CONTAINER_CALL_ARGUMENT);
    }

    private void originEdges(Set<PropagationEdge> edges, ProgramPoint parent, MethodKey method,
            MethodContainerSummary.Value value, DependencyKind kind) {
        for (var origin : value.origins()) {
            ProgramPoint point = switch (origin) {
                case MethodContainerSummary.Parameter parameter -> bindingPoint(new Binding(method, parameter.index()));
                case MethodContainerSummary.Receiver ignored -> bindingPoint(new Binding(method, -1));
                case MethodContainerSummary.Allocation allocation -> new ProgramPoint(allocation.storage().method(),
                        allocation.storage().instruction(), Type.getType(allocation.storage().descriptor()).getInternalName());
                case MethodContainerSummary.Producer producer -> producer.point();
            };
            edges.add(new PropagationEdge(parent, point, kind));
        }
    }

    private ProgramPoint bindingPoint(Binding binding) {
        var summary = summaries.get(binding.method());
        int local = (summary.access() & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        if (binding.parameter() == -1) local = 0;
        else for (int index = 0; index < binding.parameter(); index++) local += Type.getType(summary.parameters().get(index).descriptor()).getSize();
        return flows.get(binding.method()).source().point(-1 - local);
    }

    private boolean mayBeSubtype(String destination, String source) {
        try {
            return hierarchy.isAssignable(destination, source);
        } catch (IllegalArgumentException missing) {
            return true;
        }
    }

    private StorageReferences unknown(ProgramPoint point) {
        return unknowns.computeIfAbsent(point, origin -> new StorageReferences(Set.of(), Set.of(origin), false));
    }

    private static boolean empty(StorageReferences facts) {
        return ReferenceJoiner.empty(facts);
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private StorageReferences join(StorageReferences first, StorageReferences second) {
        return joiner.join(first, second);
    }
}
