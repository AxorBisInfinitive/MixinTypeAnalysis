package love.crysh.mixinta.analysis;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.Diagnostic;

import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AnalysisClassReader {
    private AnalysisClassReader() { }

    static Map<String, ClassNode> readAll(Map<String, byte[]> classes, List<Diagnostic> diagnostics) {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> {
            try {
                ClassNode node = ClassFiles.read(bytes);
                if (!name.equals(node.name)) {
                    diagnostics.add(new Diagnostic("CLASS_NAME_MISMATCH", null, -1, -1,
                            name + " contains " + node.name));
                } else {
                    nodes.put(name, node);
                }
            } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic("INVALID_CLASS", null, -1, -1, name + " " + failure.getMessage()));
            }
        });
        return nodes;
    }
}
