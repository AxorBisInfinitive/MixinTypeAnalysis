package love.crysh.mixinta.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明原方法及其替换实现。method 使用原方法名和完整 JVM 描述符。
 * 返回类型保持一致，数值参数按 Java 显式 primitive cast 转换。
 * 引用表示迁移通过 parameters 声明其规则和传播范围。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypedOverwrite {
    String method() default "";

    ParameterMigration[] parameters() default {};

    String[] aliases() default {};

    boolean remap() default true;

    String constraints() default "";
}
