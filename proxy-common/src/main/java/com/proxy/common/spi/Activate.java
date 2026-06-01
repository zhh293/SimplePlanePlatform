package com.proxy.common.spi;

import java.lang.annotation.*;

/**
 * 激活条件注解 —— 控制 Filter 的条件激活
 * <p>
 * 通过 group 分组、order 排序，实现 Filter 的条件化加载。
 * 只有满足条件的 Filter 才会被加入到 Filter Chain 中。
 * </p>
 *
 * <p>示例：</p>
 * <pre>
 * {@code @Activate(group = {"local", "remote"}, order = 100)}
 * public class MonitorFilter implements Filter {
 *     // ...
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Activate {

    /**
     * 分组条件，只有匹配的 group 才激活
     * 空数组表示所有 group 都激活
     */
    String[] group() default {};

    /**
     * 排序值，越小越靠前
     */
    int order() default 0;

    /**
     * 激活所需的 key 条件
     * 当 URL 中包含指定 key 时才激活
     */
    String[] value() default {};
}
