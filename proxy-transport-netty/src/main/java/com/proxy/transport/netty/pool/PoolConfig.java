package com.proxy.transport.netty.pool;

import com.proxy.common.model.URL;

/**
 * HTTP/2 连接池配置
 */
public class PoolConfig {

    /**
     * 单个连接上的最大并发 Stream 数
     */
    private int maxStreamsPerConnection;

    /**
     * 连接池中最大连接数（通常 HTTP/2 只需要 1-2 条连接）
     */
    private int maxConnections;

    /**
     * 连接超时时间（毫秒）
     */
    private int connectTimeoutMs;

    /**
     * 是否启用 TLS
     */
    private boolean sslEnabled;

    /**
     * 心跳间隔（秒）
     */
    private int heartbeatIntervalSec;

    /**
     * 读空闲超时（秒）
     */
    private int readIdleTimeoutSec;

    /**
     * 等待可用 Stream 的超时时间（毫秒）
     */
    private int acquireTimeoutMs;

    private PoolConfig() {
    }

    public static PoolConfig fromUrl(URL url) {
        PoolConfig config = new PoolConfig();
        config.maxStreamsPerConnection = url.getParameter("maxStreams", 100);
        config.maxConnections = url.getParameter("maxConnections", 2);
        config.connectTimeoutMs = url.getParameter("connectTimeout", 5000);
        config.sslEnabled = url.getParameter("ssl", false);
        config.heartbeatIntervalSec = url.getParameter("heartbeatInterval", 30);
        config.readIdleTimeoutSec = url.getParameter("readIdleTimeout", 90);
        config.acquireTimeoutMs = url.getParameter("acquireTimeout", 10000);
        return config;
    }

    public int getMaxStreamsPerConnection() {
        return maxStreamsPerConnection;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public int getHeartbeatIntervalSec() {
        return heartbeatIntervalSec;
    }

    public int getReadIdleTimeoutSec() {
        return readIdleTimeoutSec;
    }

    public int getAcquireTimeoutMs() {
        return acquireTimeoutMs;
    }
}
