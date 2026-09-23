package com.proxy.local.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地代理配置 —— 从 YAML 文件加载
 * <p>
 * 配置项包括：本地监听端口、远程服务器列表、加密算法、集群策略等。
 * </p>
 */
public class ProxyConfig {

    /**
     * 本地 SOCKS5 监听端口
     */
    private int localPort = 1080;

    /**
     * 远程代理服务器列表
     */
    private List<RemoteServer> remoteServers = new ArrayList<>();

    /**
     * 集群容错策略（failover / failfast / forking / failback）
     */
    private String cluster = "failover";

    /**
     * 负载均衡策略（roundrobin / random / leastactive / consistenthash）
     */
    private String loadBalance = "roundrobin";

    /**
     * 请求超时时间（毫秒）
     */
    private long timeoutMs = 8000;

    /**
     * 每个远程节点的连接数
     */
    private int connectionsPerNode = 2;

    /**
     * 是否启用 HTTP CONNECT 代理（除了 SOCKS5）
     */
    private boolean httpProxyEnabled = true;

    /**
     * 系统代理配置
     */
    private SystemProxy systemProxy = new SystemProxy();

    /**
     * 路由规则配置
     */
    private RouteConfig route = new RouteConfig();

    /**
     * 系统代理配置
     */
    public static class SystemProxy {
        /**
         * 是否在启动时自动设置系统代理
         */
        private boolean enabled = true;

        /**
         * 代理监听地址（设置到系统代理中的地址）
         */
        private String host = "127.0.0.1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }
    }

    /**
     * 路由规则配置
     */
    public static class RouteConfig {
        private String defaultRoute = "direct";
        private List<String> proxyList = new ArrayList<>();
        private List<String> directList = new ArrayList<>();
        private List<String> directProviders = new ArrayList<>();
        private List<String> proxyProviders = new ArrayList<>();
        private List<RouteEntry> rules = new ArrayList<>();
        private List<String> systemDirectList = new ArrayList<>();

        /** 新版 first-match-wins 路由规则。旧列表继续保留用于兼容已有 proxy.yml。 */
        public static class RouteEntry {
            private String id;
            private String type;
            private String value;
            private String action;
            private int priority;

            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            public String getValue() { return value; }
            public void setValue(String value) { this.value = value; }
            public String getAction() { return action; }
            public void setAction(String action) { this.action = action; }
            public int getPriority() { return priority; }
            public void setPriority(int priority) { this.priority = priority; }
        }

        public String getDefaultRoute() {
            return defaultRoute;
        }

        public void setDefaultRoute(String defaultRoute) {
            this.defaultRoute = defaultRoute;
        }

        public List<String> getProxyList() {
            return proxyList;
        }

        public void setProxyList(List<String> proxyList) {
            this.proxyList = proxyList;
        }

        public List<String> getDirectList() {
            return directList;
        }

        public void setDirectList(List<String> directList) {
            this.directList = directList;
        }

        public List<String> getDirectProviders() { return directProviders; }
        public void setDirectProviders(List<String> directProviders) { this.directProviders = directProviders; }

        public List<String> getProxyProviders() { return proxyProviders; }
        public void setProxyProviders(List<String> proxyProviders) { this.proxyProviders = proxyProviders; }

        public List<RouteEntry> getRules() { return rules; }
        public void setRules(List<RouteEntry> rules) { this.rules = rules; }
        public List<String> getSystemDirectList() { return systemDirectList; }
        public void setSystemDirectList(List<String> systemDirectList) { this.systemDirectList = systemDirectList; }
    }

    /**
     * 远程服务器配置
     */
    public static class RemoteServer {
        private String host;
        private int port;
        private boolean ssl = true;
        private String cipher = "aes-gcm";
        private String cipherKey = "";
        private String transport = "http2";
        private Http3Config http3 = new Http3Config();

        public static class Http3Config {
            private String serverName = "";
            private String caFile = "";
            private String certificatePin = "";
            private int handshakeTimeoutMs = 5000;
            private int idleTimeoutMs = 60000;
            private int maxStreams = 1000;
            private long initialConnectionWindow = 16777216L;
            private long initialStreamWindow = 1048576L;
            private int maxProxyMessageBytes = 8388608;
            private long streamPendingHardLimit = 4194304L;
            private long connectionPendingHardLimit = 67108864L;
            private int writeBufferLowWaterMark = 262144;
            private int writeBufferHighWaterMark = 1048576;

            public void toUrlParameters(com.proxy.common.model.URL url) {
                url.addParameter("http3.serverName", serverName);
                url.addParameter("http3.caFile", caFile);
                url.addParameter("http3.certificatePin", certificatePin);
                url.addParameter("http3.handshakeTimeoutMs", handshakeTimeoutMs);
                url.addParameter("http3.idleTimeoutMs", idleTimeoutMs);
                url.addParameter("http3.maxStreams", maxStreams);
                url.addParameter("http3.initialConnectionWindow", initialConnectionWindow);
                url.addParameter("http3.initialStreamWindow", initialStreamWindow);
                url.addParameter("http3.maxProxyMessageBytes", maxProxyMessageBytes);
                url.addParameter("http3.streamPendingHardLimit", streamPendingHardLimit);
                url.addParameter("http3.connectionPendingHardLimit", connectionPendingHardLimit);
                url.addParameter("http3.writeBufferLowWaterMark", writeBufferLowWaterMark);
                url.addParameter("http3.writeBufferHighWaterMark", writeBufferHighWaterMark);
            }

            public String getServerName() { return serverName; }
            public void setServerName(String value) { serverName = value; }
            public String getCaFile() { return caFile; }
            public void setCaFile(String value) { caFile = value; }
            public String getCertificatePin() { return certificatePin; }
            public void setCertificatePin(String value) { certificatePin = value; }
            public int getHandshakeTimeoutMs() { return handshakeTimeoutMs; }
            public void setHandshakeTimeoutMs(int value) { handshakeTimeoutMs = value; }
            public int getIdleTimeoutMs() { return idleTimeoutMs; }
            public void setIdleTimeoutMs(int value) { idleTimeoutMs = value; }
            public int getMaxStreams() { return maxStreams; }
            public void setMaxStreams(int value) { maxStreams = value; }
            public long getInitialConnectionWindow() { return initialConnectionWindow; }
            public void setInitialConnectionWindow(long value) { initialConnectionWindow = value; }
            public long getInitialStreamWindow() { return initialStreamWindow; }
            public void setInitialStreamWindow(long value) { initialStreamWindow = value; }
            public int getMaxProxyMessageBytes() { return maxProxyMessageBytes; }
            public void setMaxProxyMessageBytes(int value) { maxProxyMessageBytes = value; }
            public long getStreamPendingHardLimit() { return streamPendingHardLimit; }
            public void setStreamPendingHardLimit(long value) { streamPendingHardLimit = value; }
            public long getConnectionPendingHardLimit() { return connectionPendingHardLimit; }
            public void setConnectionPendingHardLimit(long value) { connectionPendingHardLimit = value; }
            public int getWriteBufferLowWaterMark() { return writeBufferLowWaterMark; }
            public void setWriteBufferLowWaterMark(int value) { writeBufferLowWaterMark = value; }
            public int getWriteBufferHighWaterMark() { return writeBufferHighWaterMark; }
            public void setWriteBufferHighWaterMark(int value) { writeBufferHighWaterMark = value; }
        }

        public String getTransport() { return transport; }
        public void setTransport(String value) { transport = value == null ? "http2" : value; }
        public Http3Config getHttp3() { return http3; }
        public void setHttp3(Http3Config value) { http3 = value == null ? new Http3Config() : value; }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public String getCipher() {
            return cipher;
        }

        public void setCipher(String cipher) {
            this.cipher = cipher;
        }

        public String getCipherKey() {
            return cipherKey;
        }

        public void setCipherKey(String cipherKey) {
            this.cipherKey = cipherKey;
        }

        @Override
        public String toString() {
            return host + ":" + port + " (ssl=" + ssl + ", cipher=" + cipher + ")";
        }
    }

    /**
     * 从 YAML 文件加载配置
     */
    public static ProxyConfig load(String path) {
        try (InputStream in = new FileInputStream(path)) {
            Yaml yaml = new Yaml();
            ProxyConfig config = yaml.loadAs(in, ProxyConfig.class);
            if (config == null) {
                config = new ProxyConfig();
            }
            config.validate();
            return config;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from: " + path, e);
        }
    }

    /**
     * 从 classpath 加载配置
     */
    public static ProxyConfig loadFromClasspath(String resource) {
        try (InputStream in = ProxyConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("Config resource not found: " + resource);
            }
            Yaml yaml = new Yaml();
            ProxyConfig config = yaml.loadAs(in, ProxyConfig.class);
            if (config == null) {
                config = new ProxyConfig();
            }
            config.validate();
            return config;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from classpath: " + resource, e);
        }
    }

    /**
     * 默认配置
     */
    public static ProxyConfig defaultConfig() {
        return new ProxyConfig();
    }

    private void validate() {
        if (localPort <= 0 || localPort > 65535) {
            throw new IllegalArgumentException("Invalid local port: " + localPort);
        }
        if (remoteServers == null || remoteServers.isEmpty()) {
            throw new IllegalArgumentException("At least one remote server must be configured");
        }
        for (RemoteServer server : remoteServers) {
            if (server.getHost() == null || server.getHost().isEmpty()) {
                throw new IllegalArgumentException("Remote server host must not be empty");
            }
            if (server.getPort() <= 0 || server.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid remote server port: " + server.getPort());
            }
            if (!"http2".equals(server.getTransport()) && !"http3".equals(server.getTransport())
                    && !"netty".equals(server.getTransport())) {
                throw new IllegalArgumentException("Unknown transport: " + server.getTransport());
            }
        }
        long http3Nodes = remoteServers.stream().filter(s -> "http3".equals(s.getTransport())).count();
        if (http3Nodes > 0 && (remoteServers.size() != 1 || connectionsPerNode != 1)) {
            throw new IllegalArgumentException("HTTP/3 V1 requires exactly one remote server and connectionsPerNode=1");
        }
    }

    // ==================== Getters & Setters ====================

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public List<RemoteServer> getRemoteServers() {
        return remoteServers;
    }

    public void setRemoteServers(List<RemoteServer> remoteServers) {
        this.remoteServers = remoteServers;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getLoadBalance() {
        return loadBalance;
    }

    public void setLoadBalance(String loadBalance) {
        this.loadBalance = loadBalance;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getConnectionsPerNode() {
        return connectionsPerNode;
    }

    public void setConnectionsPerNode(int connectionsPerNode) {
        this.connectionsPerNode = connectionsPerNode;
    }

    public boolean isHttpProxyEnabled() {
        return httpProxyEnabled;
    }

    public void setHttpProxyEnabled(boolean httpProxyEnabled) {
        this.httpProxyEnabled = httpProxyEnabled;
    }

    public SystemProxy getSystemProxy() {
        return systemProxy;
    }

    public void setSystemProxy(SystemProxy systemProxy) {
        this.systemProxy = systemProxy;
    }

    public RouteConfig getRoute() {
        return route;
    }

    public void setRoute(RouteConfig route) {
        this.route = route;
    }
}
