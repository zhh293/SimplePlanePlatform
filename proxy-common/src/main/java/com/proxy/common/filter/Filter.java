package com.proxy.common.filter;

import com.proxy.common.spi.SPI;

import java.util.concurrent.CompletableFuture;

/**
 * 过滤器 SPI 接口 —— 责任链中的每一环
 * <p>
 * 每个 Filter 是一个独立的横切关注点（限流、监控、重试、路由等），
 * 通过 SPI 动态注册，运行时按 @Order 排序组装成链。
 * </p>
 */
@SPI
public interface Filter {

    /**
     * 过滤逻辑
     *
     * @param invoker    下一环（可能是下一个 Filter，也可能是最终的真实 Invoker）
     * @param invocation 调用上下文
     * @return 异步结果
     * @throws ProxyException 过滤异常
     */
    CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException;
}
