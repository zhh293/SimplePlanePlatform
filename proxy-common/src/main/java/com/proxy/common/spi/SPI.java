package com.proxy.common.spi;

import java.lang.annotation.*;

/**
 * 标记一个接口为 SPI 扩展点
 * <p>
 * 被此注解标记的接口，其实现类可以通过 SPI 配置文件动态加载。
 * value() 属性指定默认扩展名，当调用 getDefaultExtension() 时使用。
 * </p>
 *
 * <p>示例：</p>
 * <pre>
 * {@code @SPI("netty")}
 * public interface Transporter {
 *     // ...
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {

    /**
     * 默认扩展名
     * <p>
     * 当通过 ExtensionLoader.getDefaultExtension() 获取扩展时，
     * 使用此值作为扩展名查找对应实现。
     * </p>
     */
    String value() default "";
}
