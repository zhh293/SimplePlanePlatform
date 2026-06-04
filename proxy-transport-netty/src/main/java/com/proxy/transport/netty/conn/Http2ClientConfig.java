package com.proxy.transport.netty.conn;

import com.proxy.common.model.URL;

/**
 * 单条 HTTP/2 客户端连接的配置。
 * <p>
 * 由原 PoolConfig 收敛而来：在“单连接 + 多路复用”模型下，
 * 连接池语义（maxConnections / acquireTimeout / 等待队列）已被移除，
 * 仅保留与单连接和 Stream 行为相关的配置。
 * </p>
 */
public class Http2ClientConfig {

    /** 单条连接上的最大并发 Stream 数（HTTP/2 SETTINGS_MAX_CONCURRENT_STREAMS）。 */
    private int maxStreamsPerConnection;

    /** 连接超时时间（毫秒）。 */
    private int connectTimeoutMs;

    /** 是否启用 TLS。 */
    private boolean sslEnabled;

    /** 心跳间隔（秒）。 */
    private int heartbeatIntervalSec;

    /** 读空闲超时（秒）。 */
    private int readIdleTimeoutSec;

    private Http2ClientConfig() {
    }

    public static Http2ClientConfig fromUrl(URL url) {
        Http2ClientConfig config = new Http2ClientConfig();
        config.maxStreamsPerConnection = url.getParameter("maxStreams", 100);
        config.connectTimeoutMs = url.getParameter("connectTimeout", 5000);
        config.sslEnabled = url.getParameter("ssl", false);
        config.heartbeatIntervalSec = url.getParameter("heartbeatInterval", 30);
        config.readIdleTimeoutSec = url.getParameter("readIdleTimeout", 90);
        return config;
    }

    public int getMaxStreamsPerConnection() {
        return maxStreamsPerConnection;
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
}
