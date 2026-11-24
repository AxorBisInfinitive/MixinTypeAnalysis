# Exp Fabric

`exp-fabric` 是通过 Fabric Loader 启动 `exp` 的独立桥接工程，使用 Fabric Loader 0.20.1 及其 Mixin 0.8.7 运行时。此工程用于验证 Loader、模组入口、Mixin 注入和模组内 ASM Agent 的启动链路。

## 构建

在仓库根目录依次构建业务工程、桥接工程和验证模组。

```powershell
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean package
```

`exp-fabric-testmod` 以 `provided` 依赖 `love.crysh:exp:0.1.0-Snapshot`，所以先安装 `exp` 可让 IDE 解析 `Main` 及其 sources JAR。此依赖只用于编译，不会打入验证模组。运行时业务 JAR 由 `exp-fabric` 加入 Knot classpath。

## 运行

在仓库根目录执行。

```powershell
./exp-fabric/run.ps1
```

脚本会将验证模组复制到 `exp-fabric/run/mods/`，并使用 `exp-fabric/target/lib/` 中的 Loader 运行依赖。`MainMixin` 会将 `Main.add` 改为乘法。模组入口会通过辅助 JVM 附加 Agent，在 `Main` 首次加载时将 `Main.div` 中的整数除法改为减法。当前示例最终输出 `32`。
