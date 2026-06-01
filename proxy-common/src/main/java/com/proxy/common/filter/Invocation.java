package com.proxy.common.filter;

import com.proxy.common.model.ProxyMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用上下文 —— 携带一次请求的所有信息
 * <p>
 * Filter 可以通过 attachments 传递信息给下游 Filter。
 * </p>
 */
public class Invocation {

    private String targetHost;
    private int targetPort;
    private byte[] data;
    private ProxyMessage.MessageType type;
    private Map<String, Object> attachments;

    public Invocation() {
        this.attachments = new HashMap<>();
    }

    public Invocation(String targetHost, int targetPort, byte[] data, ProxyMessage.MessageType type) {
        this();
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.data = data;
        this.type = type;
    }

    // ==================== Attachment 操作 ====================

    public void setAttachment(String key, Object value) {
        attachments.put(key, value);
    }

    public Object getAttachment(String key) {
        return attachments.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttachment(String key, T defaultValue) {
        Object value = attachments.get(key);
        return value != null ? (T) value : defaultValue;
    }

    // ==================== Getters & Setters ====================

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public ProxyMessage.MessageType getType() {
        return type;
    }

    public void setType(ProxyMessage.MessageType type) {
        this.type = type;
    }

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, Object> attachments) {
        this.attachments = attachments;
    }

    @Override
    public String toString() {
        return "Invocation{" +
                "targetHost='" + targetHost + '\'' +
                ", targetPort=" + targetPort +
                ", type=" + type +
                ", dataLength=" + (data != null ? data.length : 0) +
                '}';
    }
}
