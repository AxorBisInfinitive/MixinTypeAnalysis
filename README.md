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

`love.crysh.mixinta.anno.TypedOverwrite` 声明原方法与替换实现。`method` 使用原方法名和完整 JVM 描述符。

四个声明注解统一位于 `love.crysh.mixinta.anno`。

```java
import love.crysh.mixinta.anno.ContainerRule;
import love.crysh.mixinta.anno.ParameterMigration;
import love.crysh.mixinta.anno.TypeRule;
import love.crysh.mixinta.anno.TypedOverwrite;
```

| 包 | 职责 |
| --- | --- |
| `love.crysh.mixinta.anno` | TypedOverwrite、参数迁移、表示规则和容器规则注解 |
| `love.crysh.mixinta.model` | 方法标识、程序位置、传播依赖和变更记录 |
| `love.crysh.mixinta.bytecode` | Class 文件读写、类层次查询和 primitive 转换 |
| `love.crysh.mixinta.diagnostic` | 结构化诊断、异常和输出格式 |
| `love.crysh.mixinta.analysis` | 声明准备、规则校验、传播求解和改写计划 |
| `love.crysh.mixinta.runtime` | Mixin 生命周期接入和批次发布 |
| `love.crysh.mixinta.runtime.agent` | agent 附加和跨 ClassLoader 回调 |

使用旧包名的源码需要更新 import 并重新编译，使 class 文件中的注解描述符指向 `anno` 包。

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

迭代器和视图保存根容器身份、元素通道和引用种类。支持 `iterator`、`descendingIterator`、`listIterator`、`subList`、List 与 Deque 的 `reversed`，以及 Map 的 `values`、`keySet` 和 `entrySet`。`Iterator.next`、`ListIterator.previous` 和写入操作关联到根存储，`Map.Entry.getValue`、`setValue` 传播 value 表示，`getKey` 保留原 key 表示。增强 for 循环通过普通迭代器链处理，派生引用可通过方法参数、返回值和递归传播。

视图上的 `contains` 和 `remove(Object)` 使用编码后的旧表示比较，remove 通过原迭代器删除第一个匹配元素。keySet 的查询和迭代保留 Long。原迭代器、视图和 Entry 继续执行状态检查、区间检查和 fail-fast 检查，拆箱位置继续使用 READ。Entry 和嵌套迭代器的泛型调试信息只改写 value。视图逃逸、来源不明、Entry 与普通元素迭代器合流、Map key 物理迁移、回调及未建模操作会形成诊断。stream、spliterator 和 bulk 操作仍需扩展摘要。

容器引用按指令位置、局部槽和操作数栈位置建立显式别名事实，保留全部可能的分配位置、未知来源和 null。局部赋值、引用 cast、分支、循环、异常路径和栈复制共享对应存储约束，局部槽复用按各自生命周期处理。固定批次中的普通调用通过方法摘要绑定 receiver、实参和返回来源，支持容器工厂、参数转发、递归和相互递归。字段、数组读取和缺少实现的工厂返回仍作为未知来源参与检查。

容器访问按分配位置汇总 `LOCAL`、`UNKNOWN` 和 `ESCAPED` 状态。未覆盖的返回、字段或数组写入、动态捕获生成逃逸约束，缺少实现或派发覆盖的调用生成未知访问约束。批次内部返回可传递存储身份，callee 内的逃逸效果会关联到实际传入的存储。诊断包含受影响存储、元素通道、调用参数和传播路径，同一调用中的多个容器参数分别报告。访问时序和 `clear` 都服从分配点统一表示模型。容器对象的同步块保留原有锁和空引用行为。

正式分析器从 Mixin 后方法体提取符号化方法摘要，保存 receiver、参数、分配点、返回别名、容器操作绑定和外部访问效果。调用图对有限来源集合单调合并到固定点。固定派发调用需要批次内的可分析实现，virtual 和 interface 调用根据 receiver 的已知分配类型枚举全部可能实现。未知 receiver、native、缺少方法体、方法句柄和无来源种子的递归环保留未解决约束。package-private 声明涉及跨包同名实现且缺少覆盖链证明时也保留未知边界。verbose 诊断显示摘要统计、组合状态和派发覆盖原因。

同一方法体汇总全部调用输入，相关存储共同满足元素表示要求。容器参数和返回值保留原始 JVM 描述符，跨方法传播存储身份及其元素约束。当前支持旧 long 消费者通过 `ENCODE` 取得原表示，任意元素参数与返回类型的跨方法改写仍待实现。其他类调用 private 规则时，计划在规则所属类生成 public static synthetic 转发方法，并检查规则所属类和表示类型的访问权限。

同一批次中的多个引用迁移声明共同检查值与存储通道。`RepresentationBatch` 按容器规则作用域复用稳定来源帧和调用图，每个声明独立保留需求、规则选择及推导依据。共享通道要求不同表示时报告 `CONTAINER_FACT_CONFLICT`，共享值的类型冲突报告 `VALUE_FACT_CONFLICT`，同一使用位置要求不同辅助方法、绑定或规则版本时报告 `REPRESENTATION_RULE_CONFLICT`。显式相同 id 要求不同表示时报告 `MIGRATION_ID_CONFLICT`。不同 id 可以提出兼容要求，相同 JVM 类型仍须检查实际改写动作。

兼容动作在固定 Mixin 后快照上合并，每条指令只执行一次最终改写。同一调用上的全部参数一起分配临时槽并应用编码及 primitive 转换，独立通道的泛型调试信息共同更新。来源、读取与编码继续遵守各声明的规则作用域。未选中迁移的已知存储通道保持原表示，缺少来源或规则的要求保持未解决状态。四态汇总为 `UNKNOWN`、`ORIGINAL`、`MIGRATED` 和 `CONFLICT`。

各声明先求解需求固定点，批次汇总全部值与通道后校验实际使用，最后生成统一计划。共享 Long、共享 long 分别装箱，以及容器之间转移元素可以跨声明协作。编码边界依据完整批次判定。容器比较的存储元素编码和查询参数编码分别保留各自规则，再合并到同一次调用。

`ConstraintGraph` 区分值、调用参数、接收者、操作数、动态捕获和完整 StorageSlot，并保存每项要求的推导关联。操作数索引贯穿约束边和诊断路径，独立形参不会因显示位置相同而折叠。元素状态按实际关联约束计算，诊断只折叠具有共同推导依据的冲突。确定为 null 的来源可用于不同引用表示，普通引用使用与实际读取的空值异常继续保留。包含非 null 来源的合流继续检查各来源要求。

核心 API 接收一个 ClassLoader 对应的固定类集合，Map 的键使用 JVM 内部类名。调用方负责提供完整的参与类集合。

```java
import love.crysh.mixinta.analysis.PreparedOverwrites;
import love.crysh.mixinta.analysis.TransformationPlan;
import love.crysh.mixinta.analysis.TypedOverwriteAnalyzer;

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

容器诊断通过 `ContainerContext` 保存根分配、通道、真实访问位置、operation 和元素事实状态。跨声明诊断通过 `MigrationRequirement` 保存冲突双方及各自推导。普通输出显示 `Storage`、`Channel`、`Operation`、`State` 和各声明的依赖路径，verbose 追加完整分配描述符、访问位置和传播边。表示与规则冲突在 `analyze` 阶段汇总，计划动作冲突使用 `REPRESENTATION_PLAN_CONFLICT`，输出验证继续检查实际生成的字节码。

缺失规则和外部访问也保留相关声明。相同位置及相同表示要求的重复约束共同展示，独立值和容器的冲突分别报告。未知操作定位实际访问，普通路径由真实传播边重建，verbose 补充相关调用图及视图关系。指令、参数、局部元数据和规则转发计划项累计所有兼容贡献者，计划冲突列出该位置所有实际贡献声明及各自推导。

栈帧所需的层次信息先从输入类读取，再通过资源 ClassLoader 读取外部 class 文件。计算过程中避免定义或初始化被分析的类，缺少层次信息时报告错误。

## 自定义容器摘要

在 `ParameterMigration.rules` 指定的类上重复声明 `@ContainerRule`。规则可以放在独立类或同目标的 Mixin 类上，`method` 使用 owner、方法名和完整 JVM 描述符。单条声明和重复声明均按 class 元数据读取。

```java
@ContainerRule(method = "example.Box.<init>()V", operation = ContainerRule.Operation.ALLOCATE)
@ContainerRule(method = "example.Box.put(Ljava/lang/Object;)V",
        operation = ContainerRule.Operation.WRITE, parameter = 0)
@ContainerRule(method = "example.Box.take()Ljava/lang/Object;", operation = ContainerRule.Operation.READ_REMOVE)
@ContainerRule(method = "example.Box.iterator()Ljava/util/Iterator;",
        operation = ContainerRule.Operation.VIEW, view = ContainerRule.View.ITERATOR)
public final class ExampleRules {
    // 此处同时声明迁移所需的 SOURCE、READ 和 ENCODE 辅助方法。
}
```

这个示例要求 `example.Box<T>` 是 final 类，`put(T)` 和 `take()` 的元素类型在字节码中擦除为 Object。`ALLOCATE` 声明构造器创建空存储，`WRITE` 将指定参数写入 receiver 的通道，`READ`、`READ_REMOVE` 和 `READ_WRITE` 从通道返回元素。`OBSERVE` 只返回结构标量，`CLEAR` 清空存储。`VIEW` 可声明 `ALIAS`、`ITERATOR`、`LIST_ITERATOR`、`LIST`、`SEQUENCE`、`KEYS`、`VALUES`、`ENTRIES` 或 `ENTRY` 关系，派生对象共享根存储。`ALIAS` 将自定义包装对象绑定到声明的单个元素通道。

`channel` 默认是 0，多通道容器按操作分别声明，并以对应根类型参数的位置更新泛型调试信息。`parameter` 按方法参数计数，支持前面存在 long、double 参数。`id` 默认使用方法键，`version` 默认为 1。准备阶段检查方法存在性、固定派发、元素擦除类型、参数索引、view 返回类型及重复声明，Mixin 后重新验证结构并检查跨规则冲突。

自定义规则描述完整的存储与行为契约。作者须保证实现可接受新引用表示，遵守声明的读写通道、异常和求值顺序，且不额外保留或暴露容器。摘要覆盖的方法体作为契约边界参与分析，未声明操作继续接受普通方法分析或生成未知访问诊断。批次内摘要方法直接调用需要引用迁移的目标时报告冲突，因为该方法体中的调用也需要改写。

当前接受构造器，以及 private、final 方法或 final 类中的实例方法。元素读写描述符使用 Object，其他参数使用 primitive，结构返回值使用 primitive 或 void。static 操作、开放 virtual 派发、额外引用参数和数组元素描述符暂未实现。声明不能覆盖内置摘要。容器实现可从资源 ClassLoader 读取元数据，分析阶段保持其未定义、未初始化。

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

集成检查覆盖真实原生 Overwrite 的 priority 和 `@Final` 对照、跨类调用、目标与调用方不同加载顺序、Mixin 新增调用、实例 receiver、可见性恢复、输出字节码中的 cast 和描述符、ClassLoader 隔离、插件单次执行、容器递归工厂与参数转发、interface 派发及跨包规则转发，以及声明错误、方法句柄、原方法被注入或覆盖、重复声明、Agent 附加失败和过早定义的启动失败。失败场景同时检查退出码、完整诊断和业务入口未执行。
