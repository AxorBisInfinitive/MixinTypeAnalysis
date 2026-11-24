# Exp Fabric

`exp-fabric` 是用于通过 Fabric Loader 启动 `exp` 业务应用的独立桥接工程。当前使用 Fabric Loader 0.20.1，以及该版本提供的 Mixin 0.8.7 运行时。

## 构建

在仓库根目录执行以下命令，依次构建业务应用、启动桥接和验证模组。

```powershell
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean package
```

`exp-fabric-testmod` 以 `provided` 依赖 `love.crysh:exp:0.1.0-Snapshot`，因此第一次构建需要先将 `exp` 安装到本机 Maven 仓库。该依赖只用于编译期类型检查，不会打入验证模组 JAR。运行时业务类由 `exp-fabric` 加入 Knot classpath。

## 运行

在仓库根目录启动由 Fabric Loader 管理的业务应用。

```powershell
./exp-fabric/run.ps1
```

脚本会将验证模组复制到 `exp-fabric/run/mods/`，并使用 `exp-fabric/target/lib/` 中的 Loader 运行依赖。应用默认监听 `8080`。指定 `-Port` 时，脚本会通过 `exp.port` 覆盖应用端口。其他参数会传给应用的 `main` 方法。

启动后请求 `/api/products`。日志出现 `exp-fabric-testmod Mixin applied to BusinessService.products`，表示验证用 Mixin 已转换并运行于业务应用中。
