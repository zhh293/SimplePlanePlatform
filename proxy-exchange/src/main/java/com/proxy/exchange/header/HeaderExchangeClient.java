package com.proxy.exchange.header;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.PushHandler;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * ExchangeClient 默认实现 —— 包装底层 Client，提供请求-响应能力
 * <p>
 * 核心职责：
 * - request() 时生成 requestId → 创建 DefaultFuture → client.send() → 返回 Future
 * - IO 线程收到响应后，ExchangeHandler 调用 DefaultFuture.received() 唤醒业务线程
 * </p>
 * <p>
 * 被 ClientInvoker 持有，ClientInvoker.invoke() 内部调用 request()。
 * </p>
 */
public class HeaderExchangeClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(HeaderExchangeClient.class);

    private static final long DEFAULT_TIMEOUT = 10000;

    private final Client client;
    private final URL url;
    /**
     * 持有 ExchangeHandler 引用，用于转发 PushHandler 注册（数据面推送出口）。
     * 可为 null（若未传入，则不支持推送回调）。
     */
    private final ExchangeHandler exchangeHandler;
    private volatile boolean closed = false;

    public HeaderExchangeClient(Client client, URL url) {
        this(client, url, null);
    }

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

        // 4. 通过底层 Client 发送
        try {
            client.send(message);
        } catch (Exception e) {
            // 发送失败，立即完成 Future
            DefaultFuture.received(requestId, Response.error(e.getMessage()));
        }

        // 5. DISCONNECT 类型：收到响应后关闭对应 stream
        if (message.getType() == ProxyMessage.MessageType.DISCONNECT) {
            long streamId = message.getStreamId();
            future.whenComplete((response, ex) -> {
                client.closeStream(streamId);
            });
        }

        // 6. 返回 Future
        return future;
    }

    @Override
    public void stream(ProxyMessage message) {
        if (!isAvailable()) {
            log.warn("stream() called but ExchangeClient not available, dropping: streamId={}",
                    message != null ? message.getStreamId() : -1);
            return;
        }
        // 数据面：不生成 requestId、不创建 Future，仅依赖 streamId 发后即忘
        message.setRequestId(0);
        try {
            client.send(message);
        } catch (Exception e) {
            log.error("stream() send failed: streamId={}", message.getStreamId(), e);
        }
    }

    @Override
    public void setPushHandler(PushHandler pushHandler) {
        if (exchangeHandler != null) {
            exchangeHandler.setPushHandler(pushHandler);
        } else {
            log.warn("setPushHandler called but no ExchangeHandler bound, push callbacks disabled");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
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

    private long getDefaultTimeout() {
        if (url != null) {
            return url.getParameter("timeout", DEFAULT_TIMEOUT);
        }
        return DEFAULT_TIMEOUT;
    }
}
