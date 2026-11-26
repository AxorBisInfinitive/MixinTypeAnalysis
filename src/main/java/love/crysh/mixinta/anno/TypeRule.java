package love.crysh.mixinta.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SOURCE 辅助方法以绑定值为参数，返回新表示，并承担替换原调用的行为契约。
 * ENCODE 将新表示转换为 long，作者须保证其纯度及与原编码的对应关系。
 * READ 接收并返回新表示，承担原拆箱的空值检查及相关异常契约。辅助方法可以是 private static，Mixin 合并后由目标类内部调用。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeRule {
    Kind value();

    /** SOURCE 匹配 owner.name 和完整 JVM 描述符，owner 使用内部类名。 */
    String method() default "";

    Binding binding() default Binding.RECEIVER;

    int parameter() default 0;

    String id() default "";

    int version() default 1;

    enum Kind { SOURCE, ENCODE, READ }

    enum Binding { RECEIVER, ARGUMENT }
}
