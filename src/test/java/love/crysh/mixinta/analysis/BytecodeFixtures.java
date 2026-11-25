package love.crysh.mixinta.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BytecodeFixtures {
    private BytecodeFixtures() {
    }

    static Map<String, byte[]> compile(Map<String, String> sources) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Map<String, ByteArrayOutputStream> output = new LinkedHashMap<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
                var manager = new ForwardingJavaFileManager<>(standard) {
                    @Override
                    public JavaFileObject getJavaFileForOutput(Location location, String name,
                            JavaFileObject.Kind kind, FileObject sibling) {
                        var bytes = new ByteArrayOutputStream();
                        output.put(name.replace('.', '/'), bytes);
                        return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                            @Override
                            public OutputStream openOutputStream() {
                                return bytes;
                            }
                        };
                    }
                }) {
            List<JavaFileObject> units = new ArrayList<>();
            sources.forEach((name, source) -> units.add(new SimpleJavaFileObject(
                    URI.create("memory:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            }));
            List<String> options = List.of("--release", "25", "-g", "-proc:none",
                    "-classpath", System.getProperty("java.class.path"));
            if (!compiler.getTask(null, manager, diagnostics, options, null, units).call()) {
                throw new AssertionError(diagnostics.getDiagnostics().toString());
            }
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        output.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
        return classes;
    }

    static Fixture fixture(String subjectBody, String mixinBody) {
        return fixture(subjectBody, mixinBody, Map.of());
    }

    static Fixture fixture(String subjectBody, String mixinBody, Map<String, String> additionalSources) {
        Map<String, String> sources = new LinkedHashMap<>(additionalSources);
        sources.put("sample.Subject", "package sample; public final class Subject { " + subjectBody + " }");
        sources.put("sample.SubjectMixin", """
                package sample;
                import love.crysh.mixinta.TypedOverwrite;
                import org.spongepowered.asm.mixin.Mixin;
                @Mixin(value = Subject.class, remap = false)
                public class SubjectMixin {
                """ + mixinBody + " }");
        return split(compile(sources));
    }

    static Fixture split(Map<String, byte[]> classes) {
        Map<String, byte[]> originals = new LinkedHashMap<>();
        Map<String, byte[]> mixins = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> {
            ClassNode node = ClassFiles.read(bytes);
            if (ClassFiles.annotation(node.invisibleAnnotations, ClassFiles.MIXIN) == null) {
                originals.put(name, bytes);
            } else {
                mixins.put(name, bytes);
            }
        });
        return new Fixture(originals, mixins);
    }

    /** 构造核心分析的阶段输入，只搬运测试方法并重绑定 owner。 */
    static Map<String, byte[]> mergeForCoreTest(Fixture fixture, PreparedOverwrites preparation) {
        Map<String, byte[]> classes = ClassFiles.snapshot(fixture.originals());
        for (byte[] bytes : preparation.mixins().values()) {
            ClassNode mixin = ClassFiles.read(bytes);
            var annotation = ClassFiles.annotation(mixin.invisibleAnnotations, ClassFiles.MIXIN);
            var targets = (List<?>) ClassFiles.value(annotation, "value", List.of());
            String owner = ((Type) targets.getFirst()).getInternalName();
            ClassNode target = ClassFiles.read(classes.get(owner));
            for (MethodNode method : mixin.methods) {
                if (method.name.startsWith("<")) {
                    continue;
                }
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode invocation && invocation.owner.equals(mixin.name)) {
                        invocation.owner = owner;
                    } else if (instruction instanceof FieldInsnNode field && field.owner.equals(mixin.name)) {
                        field.owner = owner;
                    }
                }
                target.methods.removeIf(existing -> existing.name.equals(method.name) && existing.desc.equals(method.desc));
                target.methods.add(method);
            }
            classes.put(owner, ClassFiles.write(target));
        }
        return classes;
    }

    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(BytecodeFixtures.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    static void verify(Map<String, byte[]> classes) {
        ClassLoader loader = loader(classes);
        classes.forEach((name, bytes) -> {
            StringWriter errors = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(errors));
            assertEquals("", errors.toString(), name);
        });
    }

    record Fixture(Map<String, byte[]> originals, Map<String, byte[]> mixins) {
        PreparedOverwrites prepare() {
            return new TypedOverwriteAnalyzer().prepare(originals, mixins);
        }

        Map<String, byte[]> transform() {
            PreparedOverwrites preparation = prepare();
            return preparation.analyze(mergeForCoreTest(this, preparation)).apply();
        }
    }
}
