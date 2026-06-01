package com.proxy.common.filter;

import java.util.concurrent.CompletableFuture;

/**
 * 调用抽象 —— Filter 链中每一环都是一个 Invoker
 * <p>
 * 统一的调用接口，无论是 Filter 链中的某一环，
 * 还是最终的真实调用者（ClientInvoker），都实现此接口。
 * </p>
 */
public interface Invoker {

    /**
     * 执行调用
     *
     * @param invocation 调用上下文（目标地址、请求数据、附加参数等）
     * @return 异步结果
     * @throws ProxyException 调用异常
     */
    CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException;

    /**
     * 是否可用
     *
     * @return true 表示可用
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 获取当前活跃调用数
     *
     * @return 活跃调用数
     */
    default int getActiveCount() {
        return 0;
    }
}
