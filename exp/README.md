# Exp

`exp` 是使用 JDK 25 和 Maven 构建的 Java 工程。`Main` 包含一个 `BlockPos -> long -> Queue<Long> -> lookup(long)` 的业务链，用于验证表示传播场景。

在仓库根目录构建。

```powershell
mvn -f exp/pom.xml clean verify
```
