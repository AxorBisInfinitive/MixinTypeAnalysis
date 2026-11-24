# Exp Fabric

`exp-fabric` 是通过 Fabric Loader 启动 `exp` 的独立桥接工程，使用 Fabric Loader 0.20.1 及其 Mixin 0.8.7 运行时。当前 `exp` 只保留空的 `Main.main`，此工程用于验证 Loader、模组入口和 Mixin 注入的启动链路。

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

脚本会将验证模组复制到 `exp-fabric/run/mods/`，并使用 `exp-fabric/target/lib/` 中的 Loader 运行依赖。验证模组会输出入口初始化信息，Mixin 会注入空的 `Main.main` 方法。由于 `Main.main` 当前为空，程序完成初始化后会直接退出。
