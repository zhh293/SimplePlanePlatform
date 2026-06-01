package com.proxy.common.spi;

import java.lang.annotation.*;

/**
 * Filter 排序注解，值越小越靠前（越先执行）
 * <p>
 * 用于控制 Filter 在责任链中的执行顺序。
 * </p>
 *
 * <p>示例：</p>
 * <pre>
 * {@code @Order(100)}
 * public class RouterFilter implements Filter {
 *     // ...
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Order {

    /**
     * 排序值，越小越靠前
     */
    int value() default 0;
}
