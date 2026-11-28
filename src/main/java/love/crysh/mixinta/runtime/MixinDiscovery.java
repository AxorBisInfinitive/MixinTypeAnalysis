package love.crysh.mixinta.runtime;

import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;
import love.crysh.mixinta.analysis.declaration.MixinAnnotations;
import love.crysh.mixinta.analysis.declaration.RepresentationRuleReader;
import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 结构登记早于 ClassInfo，目标相关注解投影沿用原生验证与应用节点。 */
final class MixinDiscovery {
    record Candidate(Object identity, IMixinConfig configuration, String name, byte[] original) { }

    private record Registration(List<String> targets, Map<String, Boolean> nativeMethods) { }
    private record MixinBinding(Object identity, int priority, String name) { }
    private record ConfigurationState(int priority, MixinEnvironment environment, String mixinPackage,
            Map<String, List<MixinBinding>> targets) { }

    record ConfigurationSnapshot(Object processor, List<?> active, List<?> pending, Map<?, ?> bindings) {
        ConfigurationSnapshot {
            active = List.copyOf(active);
            pending = List.copyOf(pending);
            bindings = Map.copyOf(bindings);
        }

        boolean sameProcessor(ConfigurationSnapshot other) {
            return processor == other.processor && sameIdentities(active, other.active) && sameIdentities(pending, other.pending);
        }

        private static boolean sameIdentities(List<?> first, List<?> second) {
            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++) {
                if (first.get(index) != second.get(index)) return false;
            }
            return true;
        }
    }

    record Bindings(Map<String, byte[]> mixins, Map<String, List<String>> targets) {
        Bindings {
            mixins = ClassFiles.snapshot(mixins);
            targets = Map.copyOf(targets);
        }
    }

    private final Map<IMixinConfig, Boolean> configurations = new IdentityHashMap<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final Set<String> loadableClasses = new LinkedHashSet<>();
    private final Map<Object, Map<String, Boolean>> validationModes = new IdentityHashMap<>();
    private final Map<Object, Registration> registrations = new IdentityHashMap<>();

    boolean knows(Object configuration) {
        return configurations.containsKey(configuration);
    }

    List<String> selected(Object value) throws ReflectiveOperationException {
        IMixinConfig configuration = (IMixinConfig) value;
        configurations.put(configuration, Boolean.TRUE);
        String prefix = configuration.getMixinPackage();
        if (prefix == null || prefix.isEmpty()) return List.of();
        if (!prefix.endsWith(".")) prefix += ".";
        List<String> names = new ArrayList<>();
        List<String> fields = new ArrayList<>(List.of("mixinClasses"));
        var side = configuration.getEnvironment().getSide();
        if (side == MixinEnvironment.Side.CLIENT) fields.add("mixinClassesClient");
        if (side == MixinEnvironment.Side.SERVER) fields.add("mixinClassesServer");
        for (String name : fields) {
            var field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object classes = field.get(value);
            if (classes instanceof Collection<?> values) {
                for (Object mixin : values) if (mixin != null) names.add((prefix + mixin).replace('.', '/'));
            }
        }
        return names;
    }

    ClassNode prepare(Object value, ClassNode node) {
        IMixinInfo info = (IMixinInfo) value;
        IMixinConfig configuration = info.getConfig();
        configurations.put(configuration, Boolean.TRUE);
        RepresentationRuleReader.assignRuleIds(node);
        candidates.add(new Candidate(value, configuration, node.name, ClassFiles.write(node)));
        return node;
    }

    void project(Object value, ClassNode node, ClassByteSource source, boolean validating) throws ReflectiveOperationException {
        if (!hasDeclarations(node)) return;
        IMixinInfo info = (IMixinInfo) value;
        Registration registration = registrations.get(value);
        List<String> selected = registration == null ? effectiveTargets(value, info.getConfig()) : registration.targets();
        Map<String, Boolean> methods = new LinkedHashMap<>();
        node.methods.stream().filter(method -> ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE) != null)
                .forEach(method -> methods.put(method.name + method.desc, false));
        new TypedOverwriteAnalyzer(source).projectDeclarations(node, selected);
        methods.replaceAll((key, ignored) -> node.methods.stream().anyMatch(method -> (method.name + method.desc).equals(key)
                && ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.OVERWRITE) != null));
        if (validating) validationModes.put(value, Map.copyOf(methods));
        if (registration != null && !registration.nativeMethods().equals(methods)) {
            throw new IllegalStateException("DECLARATION_PROJECTION_CHANGED " + node.name);
        }
    }

    boolean allowsStatic(Object info, String target, MethodNode method) {
        Registration registration = registrations.get(info);
        return registration != null && registration.targets().contains(target)
                && Boolean.FALSE.equals(registration.nativeMethods().get(method.name + method.desc));
    }

    Bindings bind(ConfigurationSnapshot configurationSnapshot) throws ReflectiveOperationException {
        Set<Object> active = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        active.addAll(configurationSnapshot.active());
        configurations.keySet().removeIf(configuration -> !active.contains(configuration));
        Map<String, byte[]> mixins = new LinkedHashMap<>();
        Map<String, List<String>> targets = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (!active.contains(candidate.configuration())) continue;
            List<String> effective = effectiveTargets(candidate.identity(), candidate.configuration());
            if (effective.isEmpty()) continue;
            Method loadable = candidate.identity().getClass().getDeclaredMethod("isLoadable");
            loadable.setAccessible(true);
            if ((boolean) loadable.invoke(candidate.identity())) loadableClasses.add(candidate.name());
            byte[] previous = mixins.putIfAbsent(candidate.name(), candidate.original());
            if (previous != null && !Arrays.equals(previous, candidate.original())) {
                throw new IllegalStateException("MIXIN_DECLARATION_CHANGED " + candidate.name());
            }
            targets.computeIfAbsent(candidate.name(), ignored -> new ArrayList<>()).addAll(effective);
        }
        targets.replaceAll((name, values) -> values.stream().distinct().sorted().toList());
        Set<String> needed = new LinkedHashSet<>();
        mixins.forEach((name, bytes) -> { if (hasDeclarations(ClassFiles.read(bytes))) needed.add(name); });
        Map<String, byte[]> declarations = new LinkedHashMap<>();
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (String name : List.copyOf(needed)) {
            for (String rule : TypedOverwriteAnalyzer.requiredRuleClasses(Map.of(name, mixins.get(name)))) {
                if (mixins.containsKey(rule)) needed.add(rule);
            }
        }
        for (String name : needed) {
            declarations.put(name, mixins.get(name));
            bindings.put(name, targets.get(name));
        }
        return new Bindings(declarations, bindings);
    }

    void seal(Map<String, byte[]> prepared, Bindings bindings) {
        for (Candidate candidate : candidates) {
            if (!bindings.mixins().containsKey(candidate.name())) continue;
            Map<String, Boolean> methods = new LinkedHashMap<>();
            ClassNode node = ClassFiles.read(prepared.get(candidate.name()));
            for (MethodNode method : ClassFiles.read(candidate.original()).methods) {
                if (ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE) == null) continue;
                MethodNode projected = ClassFiles.findMethod(node, method.name, method.desc);
                methods.put(method.name + method.desc,
                        ClassFiles.annotation(projected.visibleAnnotations, MixinAnnotations.OVERWRITE) != null);
            }
            if (!methods.isEmpty() && !methods.equals(validationModes.get(candidate.identity()))) {
                throw new IllegalStateException("DECLARATION_VALIDATION_CHANGED " + candidate.name());
            }
            registrations.put(candidate.identity(), new Registration(bindings.targets().get(candidate.name()), Map.copyOf(methods)));
        }
    }

    void releaseCandidates() {
        candidates.clear();
        validationModes.clear();
    }

    private static List<String> effectiveTargets(Object identity, IMixinConfig configuration) throws ReflectiveOperationException {
        Method getMixins = configuration.getClass().getDeclaredMethod("getMixinsFor", String.class);
        getMixins.setAccessible(true);
        List<String> effective = new ArrayList<>();
        for (String target : configuration.getTargets().stream().sorted().toList()) {
            Collection<?> actual = (Collection<?>) getMixins.invoke(configuration, target);
            if (actual != null && actual.stream().anyMatch(info -> info == identity)) effective.add(target.replace('.', '/'));
        }
        return effective;
    }

    boolean ownsPackage(String name) {
        String binary = name.replace('/', '.');
        return configurations.keySet().stream().anyMatch(configuration -> {
            String prefix = configuration.getMixinPackage();
            return prefix != null && !prefix.isEmpty()
                    && binary.startsWith(prefix.endsWith(".") ? prefix : prefix + ".");
        });
    }

    boolean loadable(String name) {
        return loadableClasses.contains(name);
    }

    ConfigurationSnapshot configurationSnapshot(Object transformer) throws ReflectiveOperationException {
        var processorField = transformer.getClass().getDeclaredField("processor");
        processorField.setAccessible(true);
        Object processor = processorField.get(transformer);
        List<?> active = processorConfigurations(processor, "configs");
        List<?> pending = processorConfigurations(processor, "pendingConfigs");
        Map<IMixinConfig, ConfigurationState> configurations = new IdentityHashMap<>();
        for (Object activeConfiguration : active) {
            IMixinConfig configuration = (IMixinConfig) activeConfiguration;
            Method getMixins = configuration.getClass().getDeclaredMethod("getMixinsFor", String.class);
            getMixins.setAccessible(true);
            Map<String, List<MixinBinding>> mappings = new LinkedHashMap<>();
            for (String target : configuration.getTargets()) {
                Collection<?> mixins = (Collection<?>) getMixins.invoke(configuration, target);
                mappings.put(target, mixins.stream().map(value -> {
                    IMixinInfo info = (IMixinInfo) value;
                    return new MixinBinding(value, info.getPriority(), info.getClassName());
                }).toList());
            }
            configurations.put(configuration, new ConfigurationState(configuration.getPriority(), configuration.getEnvironment(),
                    configuration.getMixinPackage(), Map.copyOf(mappings)));
        }
        var snapshot = new ConfigurationSnapshot(processor, active, pending, configurations);
        var after = new ConfigurationSnapshot(processorField.get(transformer), processorConfigurations(processor, "configs"),
                processorConfigurations(processor, "pendingConfigs"), configurations);
        if (!snapshot.sameProcessor(after)) {
            throw new IllegalStateException("MIXIN_CONFIGURATION_CHANGED processor configurations changed during inspection");
        }
        return snapshot;
    }

    private static List<?> processorConfigurations(Object processor, String name) throws ReflectiveOperationException {
        var field = processor.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return List.copyOf((List<?>) field.get(processor));
    }

    static boolean hasDeclarations(ClassNode node) {
        return node.methods.stream().anyMatch(method ->
                ClassFiles.annotation(method.visibleAnnotations, MixinAnnotations.TYPED_OVERWRITE) != null);
    }

    static boolean isMixin(ClassNode node) {
        return ClassFiles.annotation(node.invisibleAnnotations, MixinAnnotations.MIXIN) != null
                || ClassFiles.annotation(node.visibleAnnotations, MixinAnnotations.MIXIN) != null;
    }

    static List<String> targets(ClassNode node) {
        var annotation = ClassFiles.annotation(node.invisibleAnnotations, MixinAnnotations.MIXIN);
        if (annotation == null) annotation = ClassFiles.annotation(node.visibleAnnotations, MixinAnnotations.MIXIN);
        List<String> targets = new ArrayList<>();
        for (Object target : (Collection<?>) ClassFiles.value(annotation, "value", List.of())) {
            targets.add(((Type) target).getInternalName());
        }
        for (Object target : (Collection<?>) ClassFiles.value(annotation, "targets", List.of())) {
            targets.add(target.toString().replace('.', '/'));
        }
        return targets.stream().distinct().toList();
    }
}
