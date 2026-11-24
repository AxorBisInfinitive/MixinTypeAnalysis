# Exp Fabric 验证模组

这是 `exp-fabric` 的 Loader 集成验证模组。模组入口和 `MainMixin` 用于验证 Fabric Loader 能否初始化模组，并转换独立 Exp 应用的 `Main` 类。模组入口还会启动辅助 JVM，通过 Attach API 动态加载随模组 JAR 声明的 Agent。Agent 注册 ASM transformer，在 `Main` 首次加载时将 `div(II)I` 中的整数除法指令改为减法。

验证模组以 `provided` 方式依赖 `love.crysh:exp`，因此源码可以直接引用 `Main`。Exp JAR 不会打入模组，运行时由 `exp-fabric` 加入 Knot classpath。
