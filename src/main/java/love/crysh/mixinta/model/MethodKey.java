package love.crysh.mixinta.model;

/** owner 使用 JVM 内部类名，descriptor 包含参数和返回类型。 */
public record MethodKey(String owner, String name, String descriptor) {
    @Override
    public String toString() {
        return owner + "." + name + descriptor;
    }
}
