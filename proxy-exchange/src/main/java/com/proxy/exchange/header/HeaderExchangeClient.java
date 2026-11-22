package com.proxy.exchange.header;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ExchangeClient 默认实现 —— 包装底层 Client，提供请求-响应能力
 * <p>
 * 核心职责：
 * - request() 时生成 requestId → 创建 DefaultFuture → client.send() → 返回 Future
 * - send() 数据面发后即忘，不生成 requestId
 * - IO 线程收到响应后，ExchangeHandler 调用 DefaultFuture.received() 唤醒业务线程
 * </p>
 * <p>
 * 被 ClientInvoker 持有，ClientInvoker.invoke() 内部调用 request() 或 send()。
 * </p>
 */
public class HeaderExchangeClient implements ExchangeClient {
    @Override public void setDatagramRegistry(ConcurrentHashMap<Long, com.proxy.common.exchange.DatagramReceiver> receivers) {
        if (exchangeHandler != null) exchangeHandler.setDatagramRegistry(receivers);
    }
    private static boolean opens(ProxyMessage message) {
        return message.getType() == ProxyMessage.MessageType.CONNECT || message.getType() == ProxyMessage.MessageType.UDP_CONNECT;
    }

    private static final Logger log = LoggerFactory.getLogger(HeaderExchangeClient.class);

    private static final long DEFAULT_TIMEOUT = 10000;

    private final Client client;
    private final URL url;
    private final ExchangeHandler exchangeHandler;
    private volatile boolean closed = false;

    public HeaderExchangeClient(Client client, URL url, ExchangeHandler exchangeHandler) {
        this.client = client;
        this.url = url;
        this.exchangeHandler = exchangeHandler;
    }

    @Override
    public CompletableFuture<Response> request(ProxyMessage message, long timeoutMs) {
        if (!isAvailable()) {
            CompletableFuture<Response> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("ExchangeClient is not available"));
            return failed;
        }

        // 1. 生成 requestId
        long requestId = RequestIdGenerator.next();

        // 2. 设置 requestId 到消息
        message.setRequestId(requestId);

        // 3. 创建 DefaultFuture（注册超时任务）
        long timeout = timeoutMs > 0 ? timeoutMs : getDefaultTimeout();
        DefaultFuture future = DefaultFuture.newFuture(requestId, timeout);
        if (exchangeHandler != null) exchangeHandler.trackRequest(requestId, message.getStreamId(), future,
                opens(message));

        // Register before send: even an immediate write failure must release the stream.
        future.whenComplete((response, error) -> {
            if (message.getType() == ProxyMessage.MessageType.DISCONNECT
                    || (opens(message)
                    && (error != null || response == null || !response.isSuccess()))) {
                client.closeStream(message.getStreamId());
            }
        });

        // 4. 通过底层 Client 发送
        try {
            if (future.isDone()) return future;
            client.send(message);
            if (opens(message) && future.isCompletedExceptionally()) {
                client.closeStream(message.getStreamId());
            }
        } catch (Exception e) {
            // 发送失败，立即完成 Future
            DefaultFuture.received(requestId, Response.error(e.getMessage()));
        }

        // 6. 返回 Future
        return future;
    }

    @Override
    public void send(ProxyMessage message) {
        if (!isAvailable()) {
            throw new IllegalStateException("ExchangeClient is not available");
        }
        // 数据面：不生成 requestId、不创建 Future，仅依赖 streamId 发后即忘
        message.setRequestId(0);
        if (message.getType() == ProxyMessage.MessageType.UDP_OPEN && exchangeHandler != null) {
            exchangeHandler.ownDatagramStream(message.getStreamId(), client);
        }
        try {
            client.send(message);
        } catch (Exception e) {
            log.error("send() failed: streamId={}", message.getStreamId(), e);
            client.closeStream(message.getStreamId());
            if (exchangeHandler != null) exchangeHandler.onStreamClosed(message.getStreamId(), e);
            throw new IllegalStateException("Stream send failed", e);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (exchangeHandler != null) exchangeHandler.onDisconnected();
            client.close();
            log.info("HeaderExchangeClient closed");
        }
    }

    @Override
    public boolean isAvailable() {
        return !closed && client.isAvailable();
    }

    @Override
    public Client getClient() {
        return client;
    }

    @Override
    public void setStreamClosedListener(java.util.function.LongConsumer listener) {
        if (exchangeHandler != null) exchangeHandler.setStreamClosedListener(listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setStreamRegistry(ConcurrentHashMap streamRegistry) {
        if (exchangeHandler != null) {
            exchangeHandler.setStreamRegistry(streamRegistry);
        } else {
            log.warn("setStreamRegistry called but no ExchangeHandler bound");
        }
    }

    private long getDefaultTimeout() {
        if (url != null) {
            return url.getParameter("timeout", DEFAULT_TIMEOUT);
        }
        return DEFAULT_TIMEOUT;
    }
}
