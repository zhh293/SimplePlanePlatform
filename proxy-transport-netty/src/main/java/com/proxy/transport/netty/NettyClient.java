package com.proxy.transport.netty;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.transport.netty.conn.Http2Connection;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Netty HTTP/2 的 Client 实现（支持自动重连）。
 * <p>
 * 每个 NettyClient 实例对应<strong>一条</strong> HTTP/2 TCP 连接（{@link Http2Connection}）。
 * 一条连接上通过 Http2MultiplexHandler 支持成百上千个并发 Stream，
 * 每个客户端会话（由 streamId 标识）独占一个 Stream。
 * </p>
 * <p>
 * 重连行为：
 * <ul>
 *   <li>连接断开后 Http2Connection 自动重连；</li>
 *   <li>重连期间 isAvailable() 返回 false，上层请求会立即失败或等待；</li>
 *   <li>重连成功后 isAvailable() 自动恢复为 true，无需重启进程。</li>
 * </ul>
 * </p>
 */
public class NettyClient implements Client {

    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private final URL url;
    private final Http2Connection connection;
    private final MessageHandler messageHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** streamId → Stream 状态（含 Channel 与建流期间的待发队列）。 */
    private final ConcurrentHashMap<Long, StreamState> streams = new ConcurrentHashMap<>();

    public NettyClient(URL url, MessageHandler handler) {
        this.url = url;
        this.messageHandler = handler;
        this.connection = new Http2Connection(url, handler);
        try {
            // 预热唯一的 HTTP/2 TCP 连接（在初始化线程，允许阻塞，不在 IO 线程）
            connection.connect();
            log.info("NettyClient connected to {}:{} via HTTP/2", url.getHost(), url.getPort());
        } catch (Exception e) {
            log.error("Failed to connect to {}:{}", url.getHost(), url.getPort(), e);
            throw new RuntimeException("Failed to establish HTTP/2 connection", e);
        }
    }

    @Override
    public void send(ProxyMessage message) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Client is not available (connection " +
                    (connection.isReconnecting() ? "reconnecting" : "closed") + ")");
        }

        long streamId = message.getStreamId();
        StreamState state = streams.get(streamId);
        if (state == null) {
            if (message.getType() != ProxyMessage.MessageType.CONNECT
                    && !message.getType().opensUdp()) {
                throw new IllegalStateException("No open HTTP/2 stream for " + streamId);
            }
            if (streams.size() >= 1024) throw new IllegalStateException("HTTP/2 stream limit reached");
            StreamState candidate = new StreamState();
            candidate.udp = message.getType().opensUdp();
            StreamState previous = streams.putIfAbsent(streamId, candidate);
            state = previous == null ? candidate : previous;
            if (previous == null) {
                try { createStream(streamId, state); }
                catch (RuntimeException failure) { closeStream(streamId); throw failure; }
            }
        }

        // Stream 仍在建立中：把消息入队，建流完成后由监听器统一 flush。
        synchronized (state) {
            Channel ch = state.channel;
            if (state.failed) {
                throw new IllegalStateException("Stream " + streamId + " is closed");
            } else if (ch != null && ch.isActive() && (!state.udp || ch.isWritable())) {
                writeToStream(streamId, state, ch, message);
            } else if (ch != null && state.udp && message.getType() == ProxyMessage.MessageType.DATA) {
                log.debug("UDP drop: HTTP/2 stream {} backpressure", streamId);
            } else if (ch != null) {
                throw new IllegalStateException("HTTP/2 stream is not writable");
            } else {
                long bytes = 128L + (message.getData() == null ? 0 : message.getData().length);
                if (state.pending.size() >= 128 || state.pendingBytes + bytes > 4194304L) {
                    closeStream(streamId);
                    throw new IllegalStateException("HTTP/2 pending queue limit reached");
                }
                state.pending.add(message);
                state.pendingBytes += bytes;
            }
        }
    }

    /**
     * 非阻塞地为 streamId 创建一个新的 Stream 状态，并异步开启底层 HTTP/2 Stream。
     */
    private void createStream(long streamId, StreamState state) {
        Future<Channel> openFuture = connection.openStream();
        openFuture.addListener(f -> onStreamOpened(streamId, state, f));
        log.debug("Requesting HTTP/2 stream for streamId={}", streamId);
    }

    @SuppressWarnings("unchecked")
    private void onStreamOpened(long streamId, StreamState state, Future<? super Channel> f) {
        if (!f.isSuccess()) {
            synchronized (state) {
                state.failed = true;
                state.pending.clear();
            }
            streams.remove(streamId, state);
            log.error("Failed to open HTTP/2 stream for streamId={}", streamId, f.cause());
            messageHandler.onStreamClosed(streamId, f.cause());
            return;
        }

        Channel streamChannel = (Channel) f.getNow();
        synchronized (state) {
            if (closed.get() || state.failed || streams.get(streamId) != state) {
                state.pending.clear();
                streamChannel.close();
                return;
            }
            state.channel = streamChannel;
            while (!state.pending.isEmpty()) writeToStream(streamId, state, streamChannel, state.pending.removeFirst());
            state.pendingBytes = 0;
        }

        // Stream 关闭时自动清理映射
        streamChannel.closeFuture().addListener(cf -> {
            streams.remove(streamId, state);
            messageHandler.onStreamClosed(streamId, null);
            log.debug("Stream {} closed and removed from mapping", streamId);
        });

        log.debug("Created HTTP/2 stream for streamId={}", streamId);
    }

    private void writeToStream(long streamId, StreamState state, Channel channel, ProxyMessage message) {
        boolean counted = state.udp && message.getType() == ProxyMessage.MessageType.DATA;
        if (counted && state.writes.incrementAndGet() > 64) {
            state.writes.decrementAndGet();
            log.debug("UDP drop: HTTP/2 stream {} write task limit", streamId);
            return;
        }
        try {
            channel.eventLoop().execute(() -> channel.writeAndFlush(message).addListener(future -> {
                if (counted) state.writes.decrementAndGet();
                if (!future.isSuccess()) {
                    log.error("Failed to send message via HTTP/2 stream {}: type={}, requestId={}",
                            streamId, message.getType(), message.getRequestId(), future.cause());
                    messageHandler.onStreamClosed(streamId, future.cause());
                    streams.remove(streamId, state);
                    if (channel.isActive()) channel.close();
                }
            }));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (counted) state.writes.decrementAndGet();
            closeStream(streamId);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Long id : streams.keySet().toArray(new Long[0])) closeStream(id);
            streams.clear();
            connection.close();
            log.info("NettyClient closed, all streams released");
        }
    }

    /**
     * 判断客户端是否可用。
     * <p>
     * 注意：如果连接断开但正在重连中，此方法返回 false；
     * 当重连成功后会自动恢复为 true，无需重建 Client 实例。
     * </p>
     */
    @Override
    public boolean isAvailable() {
        return !closed.get() && connection.isActive();
    }

    @Override
    public int getActiveStreamCount() {
        int count = 0;
        for (StreamState state : streams.values()) {
            Channel ch = state.channel;
            if (ch != null && ch.isActive()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void closeStream(long streamId) {
        StreamState state = streams.remove(streamId);
        if (state != null) {
            synchronized (state) {
                state.failed = true;
                state.pending.clear();
                state.pendingBytes = 0;
                if (state.channel != null) state.channel.close();
            }
            messageHandler.onStreamClosed(streamId, null);
        }
    }

    /**
     * 获取连接状态（用于监控）。
     */
    public String getPoolStats() {
        return connection.getStats();
    }

    /**
     * 单个 streamId 对应的 Stream 状态。
     * <p>
     * channel 为 null 表示底层 Stream 仍在建立中，此时 send 的消息暂存于 pending，
     * 待建流完成后由监听器统一 flush；failed 表示建流失败。
     * </p>
     */
    private static final class StreamState {
        volatile Channel channel;
        volatile boolean failed;
        boolean udp;
        final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        final Deque<ProxyMessage> pending = new ArrayDeque<>();
        long pendingBytes;
    }
}
