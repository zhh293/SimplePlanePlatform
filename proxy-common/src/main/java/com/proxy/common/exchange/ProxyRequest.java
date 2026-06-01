package com.proxy.common.exchange;

import com.proxy.common.model.ProxyMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * 代理请求
 */
public class ProxyRequest {

    private long requestId;
    private String targetHost;
    private int targetPort;
    private byte[] data;
    private ProxyMessage.MessageType type;
    private Map<String, String> attachments;

    public ProxyRequest() {
        this.attachments = new HashMap<>();
    }

    public ProxyRequest(String targetHost, int targetPort, byte[] data) {
        this();
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.data = data;
        this.type = ProxyMessage.MessageType.DATA;
    }

    public ProxyMessage toMessage() {
        return ProxyMessage.builder()
                .requestId(requestId)
                .type(type)
                .host(targetHost)
                .port(targetPort)
                .data(data)
                .build();
    }

    // ==================== Getters & Setters ====================

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

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

    public Map<String, String> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, String> attachments) {
        this.attachments = attachments;
    }
}
