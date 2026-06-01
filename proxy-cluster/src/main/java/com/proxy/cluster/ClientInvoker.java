package com.proxy.cluster;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClientInvoker —— 将 ExchangeClient 适配为 Invoker
 * <p>
 * 这是 Filter 链的末端，真正发起网络调用的地方。
 * invoke() 内部将 Invocation 转换为 ProxyMessage，
 * 通过 ExchangeClient.request() 发送并返回 Future。
 * </p>
 */
public class ClientInvoker implements Invoker {

    private final ExchangeClient exchangeClient;
    private final long timeoutMs;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public ClientInvoker(ExchangeClient exchangeClient, long timeoutMs) {
        this.exchangeClient = exchangeClient;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(
                    Response.error("ExchangeClient is not available"));
        }

        // 构建 ProxyMessage
        ProxyMessage message = new ProxyMessage();
        message.setType(invocation.getType());
        message.setHost(invocation.getTargetHost());
        message.setPort(invocation.getTargetPort());
        message.setData(invocation.getData());

        // 活跃计数 +1
        activeCount.incrementAndGet();

        // 通过 ExchangeClient 发送请求
        return exchangeClient.request(message, timeoutMs)
                .whenComplete((response, throwable) -> {
                    // 活跃计数 -1
                    activeCount.decrementAndGet();
                });
    }

    @Override
    public boolean isAvailable() {
        return exchangeClient.isAvailable();
    }

    @Override
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * 获取底层 ExchangeClient
     */
    public ExchangeClient getExchangeClient() {
        return exchangeClient;
    }
}
