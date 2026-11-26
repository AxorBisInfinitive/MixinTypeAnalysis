package love.crysh.mixinta;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明参数的表示关系。rules 的辅助方法及其依赖必须在运行时可达。 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ParameterMigration {
    int parameter();

    Class<?> rules();

    /** 同一身份关联同一批次中兼容的表示迁移，默认身份由目标参数生成。 */
    String id() default "";
}
