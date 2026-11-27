package love.crysh.mixinta.bytecode;

import java.io.IOException;

/** 按 JVM 内部类名取得同一分析阶段的字节码，缺失时返回 null。 */
@FunctionalInterface
public interface ClassByteSource {
    byte[] read(String name) throws IOException;

    static ClassByteSource from(ClassLoader loader) {
        return name -> {
            try (var input = loader.getResourceAsStream(name + ".class")) {
                return input == null ? null : input.readAllBytes();
            }
        };
    }
}
