package love.crysh.mixinta.runtime;

import love.crysh.mixinta.bytecode.ClassByteSource;
import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.bytecode.ClassHierarchy;
import love.crysh.mixinta.bytecode.MethodReferences;

import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** 检查批次发布后加载的类是否仍调用已迁移描述符。 */
final class RetiredMethodGuard {
    static void check(RuntimeSnapshot snapshot, String name, byte[] bytes, ClassByteSource dependencies,
            BiFunction<String, String, IllegalStateException> failure) {
        if (snapshot.retiredMethods().isEmpty() || name.startsWith("love/crysh/mixinta/")) return;
        ClassNode node = ClassFiles.read(bytes);
        Map<String, ClassNode> nodes = new HashMap<>();
        snapshot.afterMixins().forEach((key, value) -> nodes.put(key, ClassFiles.read(value)));
        nodes.put(name, node);
        ClassHierarchy hierarchy = new ClassHierarchy(nodes, dependencies);
        for (var method : node.methods) {
            MethodReferences.visit(method, (instruction, reference) -> {
                boolean candidate = snapshot.retiredMethods().stream().anyMatch(retired ->
                        retired.name().equals(reference.name()) && retired.descriptor().equals(reference.descriptor()));
                if (!candidate) return;
                try {
                    var resolved = hierarchy.resolve(reference.owner(), reference.name(), reference.descriptor());
                    if (snapshot.retiredMethods().contains(resolved)) {
                        throw failure.apply("LATE_OLD_DESCRIPTOR", name + "." + method.name + method.desc
                                + " instruction=" + instruction + " -> " + reference);
                    }
                } catch (IllegalArgumentException missing) {
                    throw failure.apply("UNRESOLVED_LATE_REFERENCE", reference + " " + missing.getMessage());
                }
            });
        }
    }
}
