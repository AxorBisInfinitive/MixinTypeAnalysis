# Mixin Type Analysis

本工程是 Fabric 模组，mod ID 为 `mixinta`，Maven 坐标为 `love.crysh:mixin-type-analysis:0.1.0-Snapshot`。开发使用 JDK 25 和 Fabric Loader 0.20.1。

在仓库根目录构建并安装主工程。

```powershell
mvn clean install
```

`exp-fabric-testmod` 以 `compile` scope 依赖本工程，并将主工程 JAR 嵌入 `META-INF/jars/`。testmod 的 `fabric.mod.json` 同时声明 `depends.mixinta` 和嵌套 JAR 路径，供 Fabric Loader 发现和加载。

通过独立 Exp 应用验证模组加载、Mixin 和 ASM Agent。

```powershell
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean package
./exp-fabric/run.ps1
```

启动日志会列出 `mixinta` 并输出入口初始化信息，testmod 会直接引用主工程的 `MixinTypeAnalysis` 类。当前 ASM 示例最终输出 `9.0`。
