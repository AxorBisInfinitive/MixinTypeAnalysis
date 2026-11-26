package love.crysh.mixinta.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

public final class PrimitiveConversions {
    private PrimitiveConversions() {
    }

    public static boolean isNumeric(Type type) {
        return type.getSort() >= Type.CHAR && type.getSort() <= Type.DOUBLE;
    }

    public static boolean isSupported(Type source, Type destination) {
        boolean identity = source.equals(destination) && source.getSort() >= Type.BOOLEAN && source.getSort() <= Type.OBJECT;
        return identity || isNumeric(source) && isNumeric(destination);
    }

    /** 返回一组新的转换指令。数值窄化遵循 Java 显式 cast 的截断和精度损失规则。 */
    public static List<Integer> opcodes(Type source, Type destination) {
        if (!isSupported(source, destination)) {
            throw new IllegalArgumentException("Unsupported conversion " + source + " -> " + destination);
        }
        if (source.equals(destination)) {
            return List.of();
        }

        int sourceSort = computationalSort(source);
        int destinationSort = computationalSort(destination);
        List<Integer> instructions = new ArrayList<>(2);
        if (sourceSort != destinationSort) {
            instructions.add(conversionOpcode(sourceSort, destinationSort));
        }
        switch (destination.getSort()) {
            case Type.BYTE -> instructions.add(Opcodes.I2B);
            case Type.CHAR -> instructions.add(Opcodes.I2C);
            case Type.SHORT -> instructions.add(Opcodes.I2S);
            default -> {
            }
        }
        return List.copyOf(instructions);
    }

    public static int computationalSort(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return Type.OBJECT;
        }
        if (type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.INT) {
            return Type.INT;
        }
        return type.getSort();
    }

    private static int conversionOpcode(int source, int destination) {
        return switch (source) {
            case Type.INT -> switch (destination) {
                case Type.LONG -> Opcodes.I2L;
                case Type.FLOAT -> Opcodes.I2F;
                case Type.DOUBLE -> Opcodes.I2D;
                default -> throw new IllegalArgumentException("Invalid destination sort " + destination);
            };
            case Type.LONG -> switch (destination) {
                case Type.INT -> Opcodes.L2I;
                case Type.FLOAT -> Opcodes.L2F;
                case Type.DOUBLE -> Opcodes.L2D;
                default -> throw new IllegalArgumentException("Invalid destination sort " + destination);
            };
            case Type.FLOAT -> switch (destination) {
                case Type.INT -> Opcodes.F2I;
                case Type.LONG -> Opcodes.F2L;
                case Type.DOUBLE -> Opcodes.F2D;
                default -> throw new IllegalArgumentException("Invalid destination sort " + destination);
            };
            case Type.DOUBLE -> switch (destination) {
                case Type.INT -> Opcodes.D2I;
                case Type.LONG -> Opcodes.D2L;
                case Type.FLOAT -> Opcodes.D2F;
                default -> throw new IllegalArgumentException("Invalid destination sort " + destination);
            };
            default -> throw new IllegalArgumentException("Invalid source sort " + source);
        };
    }
}
