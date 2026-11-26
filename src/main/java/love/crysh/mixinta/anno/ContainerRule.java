package love.crysh.mixinta.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 ParameterMigration.rules 类中声明容器操作的完整行为契约。
 * 目标实现须接受元素的新引用表示，保留对象身份、异常和访问顺序，并且只产生声明的存储效果。
 * ALLOCATE 创建各通道均为空的新存储，VIEW 返回与 receiver 共享通道的对象。
 * 分析器检查结构与派发覆盖，声明者负责方法体与契约一致。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ContainerRule.List.class)
public @interface ContainerRule {
    /** owner.name 和完整 JVM 方法描述符。 */
    String method();

    Operation operation();

    /** 根存储的元素通道，同时对应根容器泛型参数的位置。 */
    int channel() default 0;

    /** WRITE 与 READ_WRITE 的元素参数索引，按参数计数。 */
    int parameter() default -1;

    View view() default View.NONE;

    String id() default "";

    int version() default 1;

    enum Operation { ALLOCATE, WRITE, READ, READ_REMOVE, READ_WRITE, OBSERVE, CLEAR, VIEW }

    enum View { NONE, ALIAS, ITERATOR, LIST_ITERATOR, LIST, SEQUENCE, KEYS, VALUES, ENTRIES, ENTRY }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        ContainerRule[] value();
    }
}
