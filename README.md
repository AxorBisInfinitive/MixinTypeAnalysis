# Mixin Type Analysis

Mixin Type Analysis（MixinTA）是一个面向 Fabric 的模组，用于在 Mixin 应用后分析并改写 Java 字节码。它扩展了 `@Overwrite` 的参数类型表达能力，让替换方法可以使用不同的参数表示，并将兼容的类型变化传播到已知调用点和局部容器中。

| 项目 | 信息 |
| --- | --- |
| Mod ID | `mixinta` |
| Maven 坐标 | `love.crysh:mixin-type-analysis:0.1.0-Snapshot` |
| Java | JDK 25 |
| Fabric Loader | 0.20.1 |
| Mixin | 0.8.7 |
| 许可证 | MIT |

## 能力示例

下面展示当前最完整的一类引用表示迁移。目标方法原本接收 `long`，替换实现改为接收 `BlockPos`。`BlockPos.asLong()` 的结果进入 `Queue<Long>`，之后从队列取出并传给目标方法。MixinTA 会沿来源、局部变量、队列写入与读取、拆箱和方法调用传播新表示，并相应调整调用描述符和局部变量泛型签名。

以下示例省略 package、import 和 `BlockPos` 定义。目标类的相关逻辑可以是：

```java
static int lookup(long packed) {
    return (int) (packed ^ (packed >>> 32));
}

static int process(BlockPos position) {
    Queue<Long> queue = new ArrayDeque<>();
    queue.add(position.asLong());
    long next = queue.remove();
    return lookup(next);
}
```

Mixin 声明新的参数类型，并提供来源规则和读取规则：

```java
@Mixin(value = Main.class, remap = false)
public class MainMixin {
    @TypedOverwrite(
            method = "lookup(J)I",
            parameters = @ParameterMigration(parameter = 0, rules = MainMixin.class))
    private static int lookup(BlockPos position) {
        return (int) (position.packed() ^ (position.packed() >>> 32));
    }

    @TypeRule(value = TypeRule.Kind.SOURCE,
            method = "example/Main$BlockPos.asLong()J")
    private static BlockPos keep(BlockPos position) {
        return position;
    }

    @TypeRule(TypeRule.Kind.READ)
    private static BlockPos read(BlockPos position) {
        return Objects.requireNonNull(position);
    }
}
```

等价源代码形态如下。这里展示的是改写后的类型关系，实际产物仍是字节码：

```java
static int lookup(BlockPos position) {
    return (int) (position.packed() ^ (position.packed() >>> 32));
}

static int process(BlockPos position) {
    Queue<BlockPos> queue = new ArrayDeque<>();
    queue.add(position);
    BlockPos next = Objects.requireNonNull(queue.remove());
    return lookup(next);
}
```

当传播过程遇到来源不明、无法证明兼容的容器访问或缺失规则时，MixinTA 汇总诊断并终止该批次，不发布部分改写结果。`SOURCE`、`READ` 与 `ENCODE` 规则是表示迁移的行为契约，规则实现需要保留相应的求值、空值和编码语义。

## `@TypedOverwrite` 的工作方式

`@TypedOverwrite` 既可声明与原方法描述符一致的普通 overwrite，也可声明参数类型变化。

- 描述符一致时，MixinTA 将声明转换为 Sponge Mixin 的原生 `@Overwrite`，由 Mixin 应用方法。
- 数值 primitive 参数变化时，MixinTA 按 Java 显式 cast 的数值转换规则改写调用点，返回类型保持一致。
- 引用表示迁移通过 `@ParameterMigration`、`@TypeRule` 和可选的 `@ContainerRule` 声明。分析器跨固定类批次传播类型与存储约束，再生成统一改写计划。

这些声明注解位于 `love.crysh.mixinta.anno` 包中。

| 注解 | 用途 |
| --- | --- |
| `TypedOverwrite` | 声明目标方法和替换实现 |
| `ParameterMigration` | 为发生引用表示迁移的参数指定规则类与迁移身份 |
| `TypeRule` | 声明来源替换、读取边界和旧表示编码方法 |
| `ContainerRule` | 为自定义容器声明分配、读写、视图等存储行为 |

目标选择器 `method` 使用原方法名和完整 JVM 描述符，例如 `lookup(J)I`。引用类型迁移要求替换方法与目标方法参数数量一致，返回类型保持一致。迁移涉及继承覆盖、开放派发或无法完成的传播时，当前会报告错误。

## 引用类型与容器传播

分析器按容器分配点追踪元素表示，并将同一存储的读写约束合并。内置摘要覆盖常见顺序容器、Map、迭代器和集合视图。方法摘要与固定调用图支持参数转发、返回别名、工厂方法、递归调用及已知派发实现。

自定义容器可在迁移规则类上重复声明 `@ContainerRule`。声明需要使用 owner、方法名和完整 JVM 描述符，并准确描述元素通道及操作，例如 `ALLOCATE`、`WRITE`、`READ`、`READ_REMOVE`、`CLEAR` 和 `VIEW`。容器规则是对实际实现行为的承诺，MixinTA 会检查规则结构，但由规则编写者保证行为契约成立。

分析范围由调用方提供的固定类集合决定。字段、数组、反射、动态生成类、未知来源、容器逃逸、未建模操作和不完整派发信息可能导致未解决约束。Stream、Spliterator 和 bulk 操作等摘要仍有扩展空间。诊断通过 `TypedOverwriteException` 汇总，可用 `-Dmixinta.diagnostics=verbose` 查看更完整的传播路径和候选规则。

## Fabric 模组接入

将 MixinTA 作为 Fabric 模组依赖，并在模组 JAR 中通过 JiJ 嵌入。参与分析的类与声明 Mixin 类通过 `fabric.mod.json` 的 `custom.mixinta` 登记：

```json
{
  "custom": {
    "mixinta": {
      "classes": ["example.Main", "example.Main$BlockPos"],
      "mixins": ["example.mixin.MainMixin"]
    }
  }
}
```

MixinTA 通过自身的 language adapter 安装早期转换设施。Mixin 配置按常规方式编写：

```json
{
  "required": true,
  "package": "example.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": ["MainMixin"]
}
```

完整分析由安全转换入口自动触发，MixinTA 的 preLaunch 入口负责主动完成和检查。使用方可以在普通模组入口直接使用登记的业务类：

```java
public void onInitialize() {
    // 直接使用参与分析的业务类
}
```

运行时从真实前置转换结果建立输入，取得完整的 Mixin 后快照，再分析并一次性发布转换结果，执行顺序为 `Fabric 前置处理 -> Mixin -> MixinTA -> 类定义`。已有 `prepare()`、`initialize()` 调用和 `TypedOverwritePlugin` 会检查并复用同一批次。

当前运行时支持单个 Knot ClassLoader 的显式固定分析域，依赖 JVM 动态附加支持，MixinTA 需要从打包 JAR 加载。实际需要改写的类若已定义，或实际字节码与分析快照不一致，会形成启动诊断。发布后出现的旧描述符引用和新增 Mixin 配置也会受到检查。

## 构建与验证

使用 JDK 25 和 Maven。在仓库根目录执行核心单元测试：

```powershell
mvn clean verify
```

构建主模组并运行 Fabric 集成测试：

```powershell
mvn clean install
mvn -f exp/pom.xml clean install
mvn -f exp-fabric/pom.xml clean package
mvn -f exp-fabric-testmod/pom.xml clean verify
```

`exp` 提供集成验证用的业务类，`exp-fabric` 提供独立 Fabric Loader 启动环境，`exp-fabric-testmod` 提供 Mixin 样例与端到端测试。集成测试在独立 JVM 中验证 Mixin 应用、类型分析、字节码输出和运行时加载行为。

## 项目结构

```text
src/main/java/love/crysh/mixinta/
├── anno/        声明注解
├── analysis/    表示传播、约束求解与改写计划
├── bytecode/    类文件、层次结构与 primitive 转换
├── diagnostic/  诊断模型与格式化
├── model/       方法、位置与传播模型
└── runtime/     Fabric 接入与 Agent 生命周期

src/test/        核心分析器测试
exp/             集成测试业务样例
exp-fabric/      独立 Fabric 启动器
exp-fabric-testmod/  Mixin 样例和端到端测试
```
