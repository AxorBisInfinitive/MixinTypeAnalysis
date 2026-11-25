package love.crysh.mixinta.analysis;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrimitiveConversionsTest {
    private static final List<Class<?>> PRIMITIVES = List.of(
            boolean.class, byte.class, short.class, char.class, int.class, long.class, float.class, double.class);
    private static Class<?> javaCasts;

    @BeforeAll
    static void compileJavaOracle() throws Exception {
        StringBuilder source = new StringBuilder("package oracle; public class Casts {");
        for (Class<?> from : PRIMITIVES) {
            for (Class<?> to : PRIMITIVES) {
                if (supported(from, to)) {
                    source.append("public static ").append(to.getName()).append(' ').append(methodName(from, to))
                            .append('(').append(from.getName()).append(" value) { return (")
                            .append(to.getName()).append(") value; }");
                }
            }
        }
        source.append('}');
        javaCasts = BytecodeFixtures.loader(BytecodeFixtures.compile(Map.of("oracle.Casts", source.toString())))
                .loadClass("oracle.Casts");
    }

    static Stream<Arguments> pairs() {
        return PRIMITIVES.stream().flatMap(from -> PRIMITIVES.stream().map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void agreesWithJavaExplicitCasts(Class<?> source, Class<?> destination) throws Exception {
        Type from = Type.getType(source);
        Type to = Type.getType(destination);
        assertEquals(supported(source, destination), PrimitiveConversions.isSupported(from, to));
        if (!supported(source, destination)) {
            assertThrows(IllegalArgumentException.class, () -> PrimitiveConversions.opcodes(from, to));
            return;
        }
        Object value = sample(source);
        Object expected = javaCasts.getMethod(methodName(source, destination), source).invoke(null, value);
        assertEquals(expected, converter(from, to).getMethod("cast", source).invoke(null, value));
    }

    static Stream<Arguments> floatingBoundaries() {
        return Stream.of(byte.class, short.class, char.class, int.class, long.class, float.class)
                .flatMap(destination -> Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        1.0e30, -1.0e30, -129.75, 65537.75).map(value -> Arguments.of(destination, value)));
    }

    @ParameterizedTest
    @MethodSource("floatingBoundaries")
    void preservesFloatingPointNarrowing(Class<?> destination, double value) throws Exception {
        Object expected = javaCasts.getMethod(methodName(double.class, destination), double.class).invoke(null, value);
        Class<?> converted = converter(Type.DOUBLE_TYPE, Type.getType(destination));
        assertEquals(expected, converted.getMethod("cast", double.class).invoke(null, value));
    }

    @Test
    void rejectsReferenceConversions() {
        assertThrows(IllegalArgumentException.class,
                () -> PrimitiveConversions.opcodes(Type.getType(Integer.class), Type.INT_TYPE));
    }

    @Test
    void rejectsVoidAndMethodTypes() {
        assertEquals(false, PrimitiveConversions.isSupported(Type.VOID_TYPE, Type.VOID_TYPE));
        Type method = Type.getMethodType("()V");
        assertEquals(false, PrimitiveConversions.isSupported(method, method));
    }

    private static Class<?> converter(Type source, Type destination) throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V25;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "converted/Cast";
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cast",
                Type.getMethodDescriptor(destination, source), null, null);
        method.instructions.add(new VarInsnNode(source.getOpcode(Opcodes.ILOAD), 0));
        PrimitiveConversions.opcodes(source, destination).forEach(opcode -> method.instructions.add(new InsnNode(opcode)));
        method.instructions.add(new InsnNode(destination.getOpcode(Opcodes.IRETURN)));
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Map<String, byte[]> classes = Map.of(node.name, writer.toByteArray());
        BytecodeFixtures.verify(classes);
        return BytecodeFixtures.loader(classes).loadClass("converted.Cast");
    }

    private static boolean supported(Class<?> source, Class<?> destination) {
        return source == destination || source != boolean.class && destination != boolean.class;
    }

    private static String methodName(Class<?> source, Class<?> destination) {
        return source.getName() + "To" + destination.getName();
    }

    private static Object sample(Class<?> source) {
        if (source == boolean.class) {
            return true;
        }
        if (source == byte.class) {
            return (byte) -127;
        }
        if (source == short.class) {
            return (short) -12345;
        }
        if (source == char.class) {
            return '\ufffe';
        }
        if (source == int.class) {
            return 16909060;
        }
        if (source == long.class) {
            return 9007199254740993L;
        }
        if (source == float.class) {
            return -12345.75f;
        }
        return 65537.75;
    }
}
