package com.proxy.local.handler;

/**
 * 一次路由决策所需的连接上下文。
 *
 * <p>系统代理模式目前只提供目标域名/IP、端口和 TCP 协议；其余字段
 * 为 TUN/Android 复用同一套语义预留，不会触发 DNS 查询。</p>
 */
public final class RouteContext {

    public enum Protocol {
        TCP, UDP
    }

    public enum Mode {
        PROXY, TUN, ANDROID
    }

    private final String domain;
    private final String destination;
    private final int port;
    private final Protocol protocol;
    private final String sourceIp;
    private final boolean fakeIp;
    private final Mode mode;

    public RouteContext(String domain, String destination, int port, Protocol protocol) {
        this(domain, destination, port, protocol, null, false, Mode.PROXY);
    }

    public RouteContext(String domain, String destination, int port, Protocol protocol,
                        String sourceIp, boolean fakeIp, Mode mode) {
        this.domain = normalize(domain);
        this.destination = normalize(destination);
        this.port = port;
        this.protocol = protocol == null ? Protocol.TCP : protocol;
        this.sourceIp = normalize(sourceIp);
        this.fakeIp = fakeIp;
        this.mode = mode == null ? Mode.PROXY : mode;
    }

    public String getDomain() { return domain; }
    public String getDestination() { return destination; }
    public int getPort() { return port; }
    public Protocol getProtocol() { return protocol; }
    public String getSourceIp() { return sourceIp; }
    public boolean isFakeIp() { return fakeIp; }
    public Mode getMode() { return mode; }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
