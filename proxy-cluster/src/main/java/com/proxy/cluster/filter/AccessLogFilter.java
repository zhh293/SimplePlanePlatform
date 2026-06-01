package com.proxy.cluster.filter;

import com.proxy.common.filter.*;
import com.proxy.common.spi.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 访问日志过滤器 —— 记录请求/响应的关键信息
 * <p>
 * 记录每次请求的目标地址、耗时、状态等信息，
 * 使用独立的 ACCESS_LOG logger，方便单独配置日志输出。
 * </p>
 */
@Activate(group = "client", order = 500)
public class AccessLogFilter implements Filter {

    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS_LOG");

    @Override
    public CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException {
        long startTime = System.currentTimeMillis();
        String host = invocation.getTargetHost();
        int port = invocation.getTargetPort();

        return invoker.invoke(invocation)
                .whenComplete((response, throwable) -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (throwable != null) {
                        accessLog.info("[FAIL] {}:{} elapsed={}ms error={}",
                                host, port, elapsed, throwable.getMessage());
                    } else {
                        accessLog.info("[OK] {}:{} elapsed={}ms status={}",
                                host, port, elapsed, response.getStatus());
                    }
                });
    }
}
