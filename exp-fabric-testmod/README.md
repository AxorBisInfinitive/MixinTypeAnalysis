# Exp Fabric 验证模组

这是 `exp-fabric` 的 Loader 集成验证模组。模组入口和 `MainMixin` 用于验证 Fabric Loader 能否初始化模组，并转换独立 Exp 应用的 `Main` 类。模组入口还会启动辅助 JVM，通过 Attach API 动态加载随模组 JAR 声明的 Agent。Agent 注册 ASM transformer，在 `Main` 首次加载时将 `div(II)I` 改为使用 `DDIV` 的双精度除法，并同步改写返回类型、调用点、局部变量和 `println(double)` 重载。

验证模组以 `provided` 方式依赖 `love.crysh:exp`，因此源码可以直接引用 `Main`。Exp JAR 不会打入模组，运行时由 `exp-fabric` 加入 Knot classpath。

验证模组以 `compile` scope 依赖 `love.crysh:mixin-type-analysis:0.1.0-Snapshot`，并直接引用 `MixinTypeAnalysis` 类。打包时将主工程 JAR 嵌入 `META-INF/jars/`，通过 `fabric.mod.json` 的 `jars` 声明提供 JiJ 依赖，并通过 `depends.mixinta` 要求匹配版本。

在仓库根目录先安装主工程和 Exp，再打包验证模组。

```powershell
mvn clean install
mvn -f exp/pom.xml clean install
mvn -f exp-fabric-testmod/pom.xml clean package
```
