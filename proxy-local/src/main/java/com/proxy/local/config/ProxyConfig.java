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
    private long timeoutMs = 30000;

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
     * 远程服务器配置
     */
    public static class RemoteServer {
        private String host;
        private int port;
        private boolean ssl = true;
        private String cipher = "aes-gcm";
        private String cipherKey = "";

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
}
