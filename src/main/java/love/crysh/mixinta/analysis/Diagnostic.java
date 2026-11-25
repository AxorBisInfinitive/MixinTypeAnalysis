package love.crysh.mixinta.analysis;

/** instruction 和 parameter 从零计数，-1 表示该位置不适用。 */
public record Diagnostic(
        String code,
        MethodKey method,
        int instruction,
        int parameter,
        String message) {
    @Override
    public String toString() {
        String location = method == null ? "input" : method.toString();
        if (instruction >= 0) {
            location += " instruction=" + instruction;
        }
        if (parameter >= 0) {
            location += " parameter=" + parameter;
        }
        return "[" + code + "] " + location + " " + message;
    }
}
