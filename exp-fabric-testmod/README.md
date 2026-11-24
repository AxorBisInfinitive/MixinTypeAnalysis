# Exp Fabric 验证模组

这是 `exp-fabric` 的 Loader 集成验证模组。模组入口和 `MainMixin` 用于验证 Fabric Loader 能否初始化模组，并转换独立 Exp 应用的 `Main` 类。

验证模组以 `provided` 方式依赖 `love.crysh:exp`，因此源码可以直接引用 `Main`。Exp JAR 不会打入模组，运行时由 `exp-fabric` 加入 Knot classpath。
