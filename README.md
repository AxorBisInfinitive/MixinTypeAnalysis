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

## TypedOverwrite 核心

`love.crysh.mixinta.TypedOverwrite` 声明原方法与替换实现。`method` 使用原方法名和完整 JVM 描述符。

```java
@Mixin(value = Example.class, remap = false)
public class ExampleMixin {
    @TypedOverwrite(method = "div(II)I")
    public static int div(double a, double b) {
        return (int) (a / b * 10);
    }
}
```

核心 API 接收一个 ClassLoader 对应的固定类集合，Map 的键使用 JVM 内部类名。调用方负责提供完整的参与类集合。

```java
TypedOverwriteAnalyzer analyzer = new TypedOverwriteAnalyzer();
PreparedOverwrites prepared = analyzer.prepare(originalClasses, mixinClasses);
Map<String, byte[]> preparedMixins = prepared.mixins();

// 将 preparedMixins 交给 Mixin，取得这一批类处理后的字节码。
TransformationPlan plan = prepared.analyze(classesAfterMixins);
Map<String, byte[]> transformedClasses = plan.apply();
```

`prepare` 解析声明并汇总结构和转换规则错误。省略 `method` 时优先匹配同名同描述符方法，否则按名称、aliases 和参数数量查找唯一候选。重载歧义需要通过 `method` 消除。

签名一致时，准备结果会将注解转换为原生 `@Overwrite`，传递 `aliases`、`remap` 和 `constraints`，保留 Mixin priority 和目标的 `@Final`。原生 Mixin 负责实际合并和冲突处理。显式指定的 private 目标名称会保留为 alias。

参数变更时，准备后的 Mixin 包含 typed 实现。`analyze` 接收 Mixin 处理后的类快照，要求原方法和 typed 实现同时存在。它检查原方法冲突、调用指令与操作数栈，汇总所有诊断后生成计划。`apply` 删除原方法、保留 typed 实现，在直接调用处插入转换，并重新计算和验证栈帧。

| 项目 | 当前契约 |
| --- | --- |
| 返回类型 | 与原方法完全一致，变更时报暂未实现 |
| 参数数量 | 与原方法一致 |
| 参数转换 | 发生变化的参数采用数值 primitive 显式 cast，包含有损窄化 |
| boolean | 保持原类型，数值转换报暂未实现 |
| 引用和数组参数 | 支持保持原类型，类型变更报暂未实现 |
| 修饰符 | static 一致，可见性采用原生默认规则 |
| 参数变更的方法 | 支持 static、private、final 方法，以及 final 类中的固定派发方法 |
| 方法覆盖关系 | 参数变更涉及继承或接口覆盖契约时报暂未实现 |
| 多目标与别名 | 参数变更的多目标 Mixin 和方法别名报暂未实现 |
| 旧方法受到其他修改 | 参数变更时报冲突，包含 `@Final`、`@MixinMerged` 和方法体变化 |
| 新签名冲突 | 新签名已存在，或多个声明产生同一新签名时报告错误 |
| 方法句柄 | 旧签名出现在方法引用、bootstrap 参数或动态常量中时报暂未实现 |

准备结果和计划持有输入快照，公开返回的字节数组可以独立修改。分析失败和输出验证失败均抛出 `TypedOverwriteException`，其 `diagnostics()` 返回稳定排序的完整诊断。改写不会修改调用方传入的类数据，计划可以重复执行。

栈帧所需的层次信息先从输入类读取，再通过资源 ClassLoader 读取外部 class 文件。计算过程中避免定义或初始化被分析的类，缺少层次信息时报告错误。

当前阶段提供声明准备、分析和批量改写 API。Fabric/Mixin 生命周期注册、Agent 接线和混淆映射生成属于后续接入工作。反射目标、后续动态生成类和不同 ClassLoader 之间的完整覆盖也需要由接入层继续处理。

## 核心测试

在仓库根目录执行完整核心检查。

```powershell
mvn clean verify
```

测试使用 JDK 25 的 javac 在内存中编译样本，通过独立 ClassLoader 执行改写结果，并由 ASM 校验。primitive 转换以 javac 生成的 Java 显式 cast 为参照，覆盖数值组合、NaN、无穷大和窄化边界。

方法转换测试覆盖跨类调用、实例 receiver、双槽参数、求值顺序、递归、控制流、异常处理、诊断汇总和输入快照。阶段样本只构造核心 API 所需的 Mixin 后字节码，真实 Mixin/Fabric 启动验证留在后续集成测试中。
