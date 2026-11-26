# Mixin Type Analysis

本工程是 Fabric 模组，mod ID 为 `mixinta`，Maven 坐标为 `love.crysh:mixin-type-analysis:0.1.0-Snapshot`。开发使用 JDK 25 和 Fabric Loader 0.20.1。

在仓库根目录构建并安装主工程。

```powershell
mvn clean install
```

`exp-fabric-testmod` 以 `compile` scope 依赖本工程，并将主工程 JAR 嵌入 `META-INF/jars/`。testmod 的 `fabric.mod.json` 同时声明 `depends.mixinta` 和嵌套 JAR 路径，供 Fabric Loader 发现和加载。

通过独立 Exp 应用验证模组加载和 TypedOverwrite。

```powershell
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean package
./exp-fabric/run.ps1
```

启动日志会列出 `mixinta` 并输出入口初始化信息。testmod 使用普通 `@Overwrite` 将 `add` 改为乘法，使用 `@TypedOverwrite` 将 `div` 的参数改为 double，保留 int 返回类型，计算除法结果的十倍。示例最终输出 `90`。

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

引用表示迁移使用 `parameters` 声明迁移参数和规则类。规则类中的 `@TypeRule` 方法提供来源替换、旧表示编码和读取边界检查。`SOURCE` 的 `method` 使用 owner、方法名和完整 JVM 描述符，规则辅助方法必须是已实现的 static 方法。

```java
@Mixin(value = Example.class, remap = false)
public class ExampleMixin {
    @TypedOverwrite(method = "lookup(J)I",
            parameters = @ParameterMigration(parameter = 0, rules = ExampleMixin.class))
    public static int lookup(BlockPos value) {
        return value.hashCode();
    }

    @TypeRule(value = TypeRule.Kind.SOURCE, method = "example/BlockPos.asLong()J")
    private static BlockPos keep(BlockPos value) {
        return value;
    }

    @TypeRule(TypeRule.Kind.ENCODE)
    private static long encode(BlockPos value) {
        return value.asLong();
    }

    @TypeRule(TypeRule.Kind.READ)
    private static BlockPos read(BlockPos value) {
        return java.util.Objects.requireNonNull(value);
    }
}
```

首版传播覆盖固定批次中的方法内来源、分支、循环、局部变量，以及局部分配的 `ArrayDeque`、`LinkedList` 和 `ArrayList`。内置摘要识别顺序容器的元素读写、结构观察和清空，`List.set` 同时处理写入参数与返回旧元素。分析按分配点统一元素表示，清空前后的写入都参与求解。来源不明、容器逃逸、未建模访问和缺少边界转换会汇总为 `TypedOverwriteException`，不会发布部分改写结果。

局部 `HashMap` 和 `LinkedHashMap` 支持 `put`、`get`、`remove`、`containsKey`、`containsValue`、`size`、`isEmpty` 和 `clear`。value 独立传播新表示，key 保持原存储表示，已迁移值用作 key 时通过 `ENCODE` 恢复为 Long。`containsValue` 通过生成的编码比较方法保持旧表示的查询行为，相关查询需要 `ENCODE` 规则。

容器引用按方法内的指令位置、局部槽和操作数栈位置建立显式别名事实，保留全部可能的分配位置、未知来源和 null。局部赋值、引用 cast、分支、循环、异常路径和栈复制共享对应存储约束，局部槽复用按各自生命周期处理。字段、数组读取和普通方法返回的容器仍作为未知来源参与检查。

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
| 引用表示迁移 | 支持单参数 `long -> object`、来源规则、编码规则、读取规则和局部容器摘要 |
| 传播诊断 | 保存迁移身份、传播依赖、候选规则和稳定排序的根因诊断 |

准备结果和计划持有输入快照，公开返回的字节数组可以独立修改。分析失败和输出验证失败均抛出 `TypedOverwriteException`，其 `diagnostics()` 返回稳定排序的完整诊断。改写不会修改调用方传入的类数据，计划可以重复执行。

诊断消息默认按未解决约束分组，包含目标迁移、调用位置、规则要求和一条依赖路径。分析失败的最后一行固定为 `Analysis failed. No transformations published.`。设置系统属性 `-Dmixinta.diagnostics=verbose`，或调用 `format(DiagnosticVerbosity.VERBOSE)`，可追加完整描述符、迁移身份、候选规则和全部传播边。

栈帧所需的层次信息先从输入类读取，再通过资源 ClassLoader 读取外部 class 文件。计算过程中避免定义或初始化被分析的类，缺少层次信息时报告错误。

## Fabric 运行时接入

使用方在 `fabric.mod.json` 的 `custom.mixinta` 中登记参与分析的完整类集合和声明所在的 Mixin 类。名称使用 Java 类名。所有目标类、调用方和参与继承解析的业务类应加入 `classes`。

```json
"custom": {
    "mixinta": {
        "classes": ["example.Subject", "example.Caller", "example.Main"],
        "mixins": ["example.mixin.SubjectMixin"]
    }
}
```

在 Mixin 配置中声明准备插件，并按常规方式列出 Mixin。

```json
{
    "required": true,
    "package": "example.mixin",
    "plugin": "love.crysh.mixinta.runtime.TypedOverwritePlugin",
    "compatibilityLevel": "JAVA_25",
    "mixins": ["SubjectMixin"]
}
```

已有 config plugin 的配置可以在其 `onLoad` 中调用 `TypedOverwriteRuntime.prepare()`。使用方模组入口应在引用业务类前调用 `TypedOverwriteRuntime.initialize()`，testmod 提供了实际入口示例。mixinta 自身入口也会调用初始化，但 Fabric 模组入口的先后顺序不适合充当业务类加载屏障。

`prepare` 汇总全部登记声明，通过辅助 JVM 动态附加 mixinta 自身 JAR 中的 Agent。Agent 在 `MixinInfo.loadMixinClass` 返回处插入准备钩子，在 Mixin 预处理开始前提供准备后的 Mixin 字节码。该入口采用 Mixin 内部实现，已验证 Fabric Loader 0.20.1 和 `sponge-mixin:0.17.5+mixin.0.8.7`。升级依赖需要重新验证。

`initialize` 对固定类集合调用实际 `IMixinTransformer`，取得完整的 Mixin 后快照，再运行核心分析和改写。所有输出成功后整批发布。Agent 对 `MixinTransformer.transformClassBytes` 安装缓存钩子，Knot 实际加载时复用同一份 Mixin 后快照，每个登记类的 config plugin 和 extension 在本批次执行一次。

JVM 定义类前，Agent 检查输入与 Mixin 后快照一致，再提供 MixinTA 结果。处理顺序为 `Mixin -> MixinTA -> defineClass`。核心和运行时由 Knot 定义，Agent 由 app ClassLoader 定义，两者通过 JDK 的函数接口通信，共用父 ClassLoader 提供的 ASM/Mixin API。

初始化异常会保存在运行时状态中，由模组入口向外传播。集合内的类如果提前加载，或实际输入偏离快照，则拒绝加载并保留失败状态。由于 JVM 会忽略 `ClassFileTransformer` 抛出的异常，定义阶段失败会返回非法 class 数据触发 `ClassFormatError`，入口检查也会继续报告原始原因。

当前运行时覆盖显式登记的固定类集合和一个 Knot ClassLoader。未登记调用方、反射目标、之后生成的类、运行期追加 Mixin、跨 ClassLoader 迁移以及混淆映射生成仍在支持范围之外。原始输入检查要求 Knot 提交的类与准备快照一致，其他转换器改变这部分输入时会报告冲突。业务类必须在整批初始化完成后定义，动态附加 Agent 也必须得到 JVM 支持。开发目录启动尚未接入，mixinta 需要从打包 JAR 加载。

## 核心测试

在仓库根目录执行完整核心检查。

```powershell
mvn clean verify
```

测试使用 JDK 25 的 javac 在内存中编译样本，通过独立 ClassLoader 执行改写结果，并由 ASM 校验。primitive 转换以 javac 生成的 Java 显式 cast 为参照，覆盖数值组合、NaN、无穷大和窄化边界。

方法转换测试覆盖跨类调用、实例 receiver、双槽参数、求值顺序、递归、控制流、异常处理、诊断汇总和输入快照。核心测试中的阶段样本构造分析器所需的 Mixin 后字节码。

真实 Fabric/Mixin 集成检查位于 `exp-fabric-testmod`，在根目录按顺序执行。

```powershell
mvn clean install
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean verify
```

Failsafe 在 package 后启动独立 JVM，每个场景生成独立的应用 JAR、验证模组和运行目录。子 JVM 的启动 classpath 只包含桥接工程及其运行库，mixinta 通过验证模组的 JiJ 加载。

集成检查覆盖真实原生 Overwrite 的 priority 和 `@Final` 对照、跨类调用、目标与调用方不同加载顺序、Mixin 新增调用、实例 receiver、可见性恢复、输出字节码中的 cast 和描述符、ClassLoader 隔离、插件单次执行，以及声明错误、方法句柄、原方法被注入或覆盖、重复声明、Agent 附加失败和过早定义的启动失败。失败场景同时检查退出码、完整诊断和业务入口未执行。
