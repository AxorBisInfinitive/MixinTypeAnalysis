package love.crysh.mixinta.analysis;

/** 从需求位置指向其依赖位置，reason 描述产生该依赖的操作。 */
public record PropagationEdge(String from, String to, String reason) {
}
