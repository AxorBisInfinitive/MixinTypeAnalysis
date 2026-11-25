# Exp Fabric 验证模组

这是 `exp-fabric` 的 Loader 集成验证模组。`MainMixin` 使用原生 `@Overwrite` 将 `add` 改为乘法，使用 `@TypedOverwrite` 将 `div(II)I` 迁移为 `div(DD)I`。新实现使用 double 除法，返回计算结果十倍对应的 int，完整示例输出 `90`。

`fabric.mod.json` 的 `custom.mixinta` 登记 `Main` 和 `MainMixin`。Mixin 配置使用 `TypedOverwritePlugin`，在 Mixin 预处理前安装准备钩子。模组入口先调用 `TypedOverwriteRuntime.initialize()`，在 Exp Main 定义前完成真实 Mixin 处理、整批分析和输出发布。动态附加 Agent、注解准备、结果缓存与类加载转换均由 mixinta 提供。

验证模组以 `provided` 方式依赖 `love.crysh:exp`，因此源码可以直接引用 `Main`。Exp JAR 不会打入模组，运行时由 `exp-fabric` 加入 Knot classpath。

验证模组以 `compile` scope 依赖 `love.crysh:mixin-type-analysis:0.1.0-Snapshot`，并直接引用 `TypedOverwriteRuntime` 和 `TypedOverwrite`。打包时将主工程 JAR 嵌入 `META-INF/jars/`，通过 `fabric.mod.json` 的 `jars` 声明提供 JiJ 依赖，并通过 `depends.mixinta` 要求匹配版本。

在仓库根目录先安装主工程和 Exp，再构建桥接工程并验证。

```powershell
mvn clean install
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean verify
./exp-fabric/run.ps1
```

`verify` 使用 Failsafe 启动独立 JVM。每个场景有独立应用、模组和运行目录，验证成功结果、实际描述符及 cast、插件执行次数、ClassLoader 边界，以及失败时的诊断和退出码。测试使用打包产物中的 JiJ，更新根工程后需要重新 install。

运行时当前采用显式登记的固定类集合，仅对一个 Knot ClassLoader 进行迁移。完整接入方式和边界见仓库根目录 README。
