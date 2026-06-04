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

        // 设置 streamId（由 HttpConnectHandler/RelayHandler 通过 attachment 传入）
        Object streamIdObj = invocation.getAttachment("streamId");
        if (streamIdObj instanceof Long) {
            message.setStreamId((Long) streamIdObj);
        } else if (streamIdObj instanceof String) {
            message.setStreamId(Long.parseLong((String) streamIdObj));
        }

        // 数据面（DATA）：发后即忘的流式发送，不生成 requestId/Future。
        // 上行数据仅依赖 streamId 寻址，服务端回包经由 PushHandler 异步推送。
        if (invocation.getType() == ProxyMessage.MessageType.DATA) {
            exchangeClient.stream(message);
            // 流式发送无响应可等，返回已完成的 OK 供上层 whenComplete 错误处理保持一致
            return CompletableFuture.completedFuture(Response.ok());
        }

        // 控制面（CONNECT/DISCONNECT）：请求-响应，生成 requestId + DefaultFuture
        // 活跃计数 +1
        activeCount.incrementAndGet();

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
