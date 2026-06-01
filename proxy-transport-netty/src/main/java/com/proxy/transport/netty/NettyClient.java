package com.proxy.transport.netty;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.transport.netty.pool.ConnectionPool;
import com.proxy.transport.netty.pool.Http2PooledConnection;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Netty HTTP/2 的 Client 实现
 * <p>
 * 每个 NettyClient 实例对应一条 HTTP/2 TCP 连接（由内部连接池管理）。
 * 一条连接上通过 Http2MultiplexHandler 支持多个并发 Stream，
 * 每个客户端会话（由 streamId 标识）独占一个 Stream。
 * </p>
 * <p>
 * MessageHandler 在构造时传入，由 Exchanger 层创建（ExchangeHandler），
 * IO 线程收到消息后直接回调 handler，handler 内部通过 requestId 唤醒对应的 Future。
 * </p>
 */
public class NettyClient implements Client {

    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private final URL url;
    private final ConnectionPool connectionPool;
    private final MessageHandler messageHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * streamId → Stream Channel 的映射
     */
    private final ConcurrentHashMap<Long, Channel> streamChannels = new ConcurrentHashMap<>();

    public NettyClient(URL url, MessageHandler handler) {
        this.url = url;
        this.messageHandler = handler;
        this.connectionPool = new ConnectionPool(url, handler);

        try {
            // 预建立 HTTP/2 TCP 连接（预热）
            connectionPool.acquireConnection();
            log.info("NettyClient connected to {}:{} via HTTP/2", url.getHost(), url.getPort());
        } catch (Exception e) {
            log.error("Failed to connect to {}:{}", url.getHost(), url.getPort(), e);
            throw new RuntimeException("Failed to establish HTTP/2 connection", e);
        }
    }

    @Override
    public void send(ProxyMessage message) {
        if (!isAvailable()) {
            throw new IllegalStateException("Client is not available");
        }

        long streamId = message.getStreamId();
        Channel channel = getOrCreateStream(streamId);

        channel.writeAndFlush(message).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("Failed to send message via HTTP/2 stream {}: type={}, requestId={}",
                        streamId, message.getType(), message.getRequestId(), future.cause());
                if (messageHandler != null) {
                    messageHandler.onError(future.cause());
                }
                // 发送失败，移除失效的 Stream
                streamChannels.remove(streamId, channel);
            }
        });

        // 如果是 DISCONNECT 消息，关闭对应的 Stream
        if (message.getType() == ProxyMessage.MessageType.DISCONNECT) {
            closeStream(streamId);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // 关闭所有活跃的 Stream
            for (Channel channel : streamChannels.values()) {
                if (channel.isActive()) {
                    channel.close();
                }
            }
            streamChannels.clear();

            connectionPool.close();
            log.info("NettyClient closed, all streams released");
        }
    }

    @Override
    public boolean isAvailable() {
        return !closed.get() && !connectionPool.isClosed();
    }

    @Override
    public int getActiveStreamCount() {
        int count = 0;
        for (Channel ch : streamChannels.values()) {
            if (ch.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取或创建指定 streamId 对应的 HTTP/2 Stream Channel
     */
    private Channel getOrCreateStream(long streamId) {
        Channel channel = streamChannels.get(streamId);
        if (channel != null && channel.isActive()) {
            return channel;
        }

        synchronized (this) {
            // Double-check
            channel = streamChannels.get(streamId);
            if (channel != null && channel.isActive()) {
                return channel;
            }

            try {
                Http2PooledConnection conn = connectionPool.acquireConnection();
                channel = connectionPool.openStream(conn);
                Channel prev = streamChannels.put(streamId, channel);

                // 关闭旧的失效 Stream
                if (prev != null && prev.isActive()) {
                    prev.close();
                }

                // 监听 Stream 关闭，自动清理映射
                final Channel streamChannel = channel;
                channel.closeFuture().addListener(f -> {
                    streamChannels.remove(streamId, streamChannel);
                    log.debug("Stream {} closed and removed from mapping", streamId);
                });

                log.debug("Created HTTP/2 stream for streamId={}", streamId);
                return channel;
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to create HTTP/2 stream for streamId=" + streamId, e);
            }
        }
    }

    /**
     * 关闭指定 streamId 对应的 Stream
     */
    public void closeStream(long streamId) {
        Channel channel = streamChannels.remove(streamId);
        if (channel != null && channel.isActive()) {
            channel.close();
            log.debug("Closed stream for streamId={}", streamId);
        }
    }

    /**
     * 获取连接池状态（用于监控）
     */
    public String getPoolStats() {
        return connectionPool.getStats();
    }
}
