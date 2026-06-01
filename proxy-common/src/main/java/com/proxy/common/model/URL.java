package com.proxy.common.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一资源定位 —— 封装连接地址和参数
 * <p>
 * 类似 Dubbo 的 URL 模型，用于传递地址信息和配置参数。
 * </p>
 */
public class URL {

    private String protocol;
    private String host;
    private int port;
    private String path;
    private Map<String, String> parameters;

    public URL() {
        this.parameters = new HashMap<>();
    }

    public URL(String protocol, String host, int port) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.parameters = new HashMap<>();
    }

    public URL(String protocol, String host, int port, String path) {
        this(protocol, host, port);
        this.path = path;
    }

    // ==================== 参数操作 ====================

    public String getParameter(String key) {
        return parameters.get(key);
    }

    public String getParameter(String key, String defaultValue) {
        String value = parameters.get(key);
        return value != null ? value : defaultValue;
    }

    public int getParameter(String key, int defaultValue) {
        String value = parameters.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getParameter(String key, long defaultValue) {
        String value = parameters.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getParameter(String key, boolean defaultValue) {
        String value = parameters.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public URL addParameter(String key, String value) {
        parameters.put(key, value);
        return this;
    }

    public URL addParameter(String key, int value) {
        parameters.put(key, String.valueOf(value));
        return this;
    }

    // ==================== Getters & Setters ====================

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

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

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (protocol != null) {
            sb.append(protocol).append("://");
        }
        sb.append(host).append(":").append(port);
        if (path != null) {
            sb.append("/").append(path);
        }
        if (!parameters.isEmpty()) {
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!first) sb.append("&");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        URL url = (URL) o;
        return port == url.port &&
                Objects.equals(protocol, url.protocol) &&
                Objects.equals(host, url.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, host, port);
    }
}
