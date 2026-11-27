package love.crysh.mixinta.runtime;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.model.MethodKey;

import java.util.Map;
import java.util.Set;

/** 批次验证完成后一次性发布，运行时按需复制提供给 JVM 的数组。 */
record RuntimeSnapshot(Map<String, byte[]> beforeMixins, Map<String, byte[]> afterMixins,
        Map<String, byte[]> outputs, Set<String> modifiedClasses, Set<MethodKey> retiredMethods) {
    RuntimeSnapshot {
        beforeMixins = Map.copyOf(ClassFiles.snapshot(beforeMixins));
        afterMixins = Map.copyOf(ClassFiles.snapshot(afterMixins));
        outputs = Map.copyOf(ClassFiles.snapshot(outputs));
        modifiedClasses = Set.copyOf(modifiedClasses);
        retiredMethods = Set.copyOf(retiredMethods);
    }
}
