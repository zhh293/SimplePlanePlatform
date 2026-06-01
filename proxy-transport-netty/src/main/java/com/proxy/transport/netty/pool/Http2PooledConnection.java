package com.proxy.transport.netty.pool;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP/2 连接包装
 * <p>
 * 代表一条 TCP 连接（父 Channel），通过 Http2MultiplexHandler 支持多路复用。
 * 跟踪当前活跃的 Stream 数量，用于负载均衡和容量判断。
 * </p>
 */
public class Http2PooledConnection {

    private static final Logger log = LoggerFactory.getLogger(Http2PooledConnection.class);

    /**
     * 底层 TCP Channel（HTTP/2 父连接）
     */
    private final Channel parentChannel;

    /**
     * 当前活跃 Stream 数
     */
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    /**
     * 最大允许的并发 Stream 数
     */
    private final int maxStreams;

    /**
     * 创建时间
     */
    private final long createTime;

    public Http2PooledConnection(Channel parentChannel, int maxStreams) {
        this.parentChannel = parentChannel;
        this.maxStreams = maxStreams;
        this.createTime = System.currentTimeMillis();
    }

    /**
     * 获取底层 TCP Channel
     */
    public Channel getParentChannel() {
        return parentChannel;
    }

    /**
     * 是否可以创建新的 Stream
     */
    public boolean isAvailable() {
        return parentChannel.isActive() && activeStreams.get() < maxStreams;
    }

    /**
     * 是否存活
     */
    public boolean isActive() {
        return parentChannel.isActive();
    }

    /**
     * 增加活跃 Stream 计数
     */
    public int incrementStreams() {
        return activeStreams.incrementAndGet();
    }

    /**
     * 减少活跃 Stream 计数
     */
    public int decrementStreams() {
        int count = activeStreams.decrementAndGet();
        if (count < 0) {
            activeStreams.set(0);
        }
        return Math.max(count, 0);
    }

    /**
     * 获取当前活跃 Stream 数
     */
    public int getActiveStreamCount() {
        return activeStreams.get();
    }

    /**
     * 获取创建时间
     */
    public long getCreateTime() {
        return createTime;
    }

    /**
     * 关闭连接
     */
    public void close() {
        if (parentChannel.isActive()) {
            parentChannel.close();
            log.info("Closed HTTP/2 connection: {}", parentChannel);
        }
    }

    @Override
    public String toString() {
        return "Http2PooledConnection{" +
                "channel=" + parentChannel +
                ", activeStreams=" + activeStreams.get() +
                ", maxStreams=" + maxStreams +
                '}';
    }
}
