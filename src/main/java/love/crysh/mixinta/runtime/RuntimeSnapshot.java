package love.crysh.mixinta.runtime;

import love.crysh.mixinta.model.MethodKey;

import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** 每类阶段输入固定，TA 输出仅保存实际修改类。所有返回数组与内部数据隔离。 */
final class RuntimeSnapshot {
    private record ClassSnapshot(ClassSourceIndex.Source source, byte[] beforeMixin, byte[] afterMixin) { }

    private final Map<String, ClassSnapshot> classes;
    private final Map<String, byte[]> outputs;
    private final Set<String> modifiedClasses;
    private final Set<MethodKey> retiredMethods;

    RuntimeSnapshot(Map<String, byte[]> beforeMixins, Map<String, byte[]> afterMixins,
            Map<String, byte[]> outputs, Set<String> modifiedClasses, Set<MethodKey> retiredMethods) {
        this(beforeMixins, afterMixins, outputs, modifiedClasses, retiredMethods, Map.of());
    }

    RuntimeSnapshot(Map<String, byte[]> beforeMixins, Map<String, byte[]> afterMixins,
            Map<String, byte[]> outputs, Set<String> modifiedClasses, Set<MethodKey> retiredMethods,
            Map<String, ClassSourceIndex.Source> sources) {
        Map<String, ClassSnapshot> inputs = new LinkedHashMap<>();
        beforeMixins.forEach((name, bytes) -> {
            byte[] before = bytes.clone();
            byte[] after = java.util.Objects.requireNonNull(afterMixins.get(name), name);
            inputs.put(name, new ClassSnapshot(sources.get(name), before, Arrays.equals(before, after) ? before : after.clone()));
        });
        this.classes = Map.copyOf(inputs);
        this.modifiedClasses = Set.copyOf(modifiedClasses);
        this.retiredMethods = Set.copyOf(retiredMethods);
        Map<String, byte[]> changes = new LinkedHashMap<>();
        for (String name : modifiedClasses) changes.put(name, java.util.Objects.requireNonNull(outputs.get(name), name).clone());
        this.outputs = Map.copyOf(changes);
    }

    byte[] beforeMixin(String name) {
        ClassSnapshot input = classes.get(name);
        return input == null ? null : input.beforeMixin().clone();
    }

    byte[] afterMixin(String name) {
        ClassSnapshot input = classes.get(name);
        return input == null ? null : input.afterMixin().clone();
    }

    byte[] outputOf(String name) {
        byte[] changed = outputs.get(name);
        return changed == null ? afterMixin(name) : changed.clone();
    }

    Set<String> modifiedClasses() { return modifiedClasses; }
    Set<MethodKey> retiredMethods() { return retiredMethods; }

    Map<String, byte[]> beforeMixins() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        classes.forEach((name, input) -> result.put(name, input.beforeMixin().clone()));
        return Map.copyOf(result);
    }

    Map<String, byte[]> afterMixins() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        classes.forEach((name, input) -> result.put(name, input.afterMixin().clone()));
        return Map.copyOf(result);
    }

    Map<String, byte[]> outputs() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        outputs.forEach((name, bytes) -> result.put(name, bytes.clone()));
        return Map.copyOf(result);
    }
}
