package love.crysh.mixinta.runtime;

import love.crysh.mixinta.bytecode.ClassFiles;
import love.crysh.mixinta.diagnostic.Diagnostic;
import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import net.fabricmc.api.EnvType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 来源可先于方法体枚举。未知输入保留失败原因，全局批次据此判断范围能否验证。 */
final class ClassSourceIndex {
    enum Status { DISCOVERED, AVAILABLE, ENVIRONMENT_EXCLUDED, RESTRICTED, MISSING, RESOURCE_NAME_MISMATCH, READ_FAILED, PARSE_FAILED, PREPROCESS_FAILED }

    record Source(String name, URL resource, Path root, ClassLoader definingLoader, Status status) { }
    record Input(Source source, Status status, byte[] bytes, Throwable cause) {
        boolean failed() { return status == Status.MISSING || status == Status.READ_FAILED || status == Status.PARSE_FAILED || status == Status.PREPROCESS_FAILED; }
        Diagnostic diagnostic() {
            return new Diagnostic("UNVERIFIABLE_ANALYSIS_INPUT", null, -1, -1,
                    source.name() + " source=" + source.resource() + " stage=" + status + " cause=" + cause);
        }
    }

    private final Map<String, Source> sources;
    private final Map<String, Input> inputs = new LinkedHashMap<>();
    private long inspectionNanos;

    ClassSourceIndex(Map<String, Source> sources) { this.sources = Map.copyOf(sources); }

    Map<String, Source> sources() { return sources; }
    List<Input> inputs() { return List.copyOf(inputs.values()); }
    void releaseInputs() { inputs.clear(); }
    long inspectionNanos() { return inspectionNanos; }

    Input inspect(String name, EnvType environment) {
        Input cached = inputs.get(name);
        if (cached != null) return cached;
        Source source = sources.get(name);
        if (source == null) return null;
        long started = System.nanoTime();
        try { return inspect(source, environment); }
        finally { inspectionNanos += System.nanoTime() - started; }
    }

    private Input inspect(Source source, EnvType environment) {
        String name = source.name();
        if (source.status() != Status.DISCOVERED) return remember(new Input(source, source.status(), null, null));
        byte[] bytes;
        try {
            bytes = KnotClassSources.read(source.resource());
        } catch (java.io.IOException | RuntimeException failure) {
            return remember(new Input(source, Status.READ_FAILED, null, failure));
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            if (!reader.getClassName().equals(name)) return remember(new Input(source, Status.RESOURCE_NAME_MISMATCH, null, null));
            ClassNode header = new ClassNode();
            reader.accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var annotation = ClassFiles.annotation(header.visibleAnnotations, "Lnet/fabricmc/api/Environment;");
            if (annotation == null) annotation = ClassFiles.annotation(header.invisibleAnnotations, "Lnet/fabricmc/api/Environment;");
            Object side = ClassFiles.value(annotation, "value", null);
            Status status = side instanceof String[] value && !value[1].equals(environment.name())
                    ? Status.ENVIRONMENT_EXCLUDED : Status.AVAILABLE;
            return remember(new Input(source, status, status == Status.AVAILABLE ? bytes : null, null));
        } catch (RuntimeException failure) {
            return remember(new Input(source, Status.PARSE_FAILED, null, failure));
        }
    }

    void failed(String name, Throwable cause) {
        failed(name, Status.PREPROCESS_FAILED, cause);
    }

    void parsingFailed(String name, Throwable cause) {
        failed(name, Status.PARSE_FAILED, cause);
    }

    private void failed(String name, Status status, Throwable cause) {
        Source source = sources.get(name);
        if (source != null) remember(new Input(source, status, null, cause));
    }

    void verifyComplete() {
        verifyIncluded(name -> true);
    }

    void verifyIncluded(java.util.function.Predicate<String> included) {
        TypedOverwriteException.throwIfErrors(inputs.values().stream().filter(input -> included.test(input.source().name()))
                .filter(Input::failed).sorted(java.util.Comparator.comparing(input -> input.source().name()))
                .map(Input::diagnostic).toList());
    }

    private Input remember(Input input) {
        inputs.put(input.source().name(), input);
        return input;
    }
}
