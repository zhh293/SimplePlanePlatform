package com.proxy.common.filter;

import com.proxy.common.spi.SPI;

import java.util.List;

/**
 * FilterChainBuilder SPI —— 过滤器链的构建策略
 * <p>
 * 不同实现决定了 Filter 如何组装、如何执行：
 * <ul>
 *   <li>sequential: 顺序责任链（默认），一个接一个执行</li>
 *   <li>conditional: 条件分支链，根据 Invocation 属性选择不同的子链</li>
 * </ul>
 * </p>
 */
@SPI("sequential")
public interface FilterChainBuilder {

    /**
     * 构建过滤器链
     *
     * @param invoker 最终的真实 Invoker（调用 Transporter）
     * @param filters 过滤器列表（已通过 SPI 加载）
     * @return 包装了 Filter 链的 Invoker
     */
    Invoker build(Invoker invoker, List<Filter> filters);
}
