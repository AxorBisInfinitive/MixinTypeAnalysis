package love.crysh.mixinta.model;

/** 固定输入中的位置，负索引沿用方法参数来源的编码。 */
public record InstructionLocation(MethodKey method, int instruction) {
}
