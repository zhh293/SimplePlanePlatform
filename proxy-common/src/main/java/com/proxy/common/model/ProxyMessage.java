package com.proxy.common.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 代理消息 —— 本地与远程之间传输的统一消息格式
 */
public class ProxyMessage {

    /**
     * 消息类型
     */
    public enum MessageType {
        /** 建立隧道连接 */
        CONNECT,
        /** 连接响应 */
        CONNECT_RESPONSE,
        /** 数据传输 */
        DATA,
        /** 断开连接 */
        DISCONNECT,
        /** 心跳请求 */
        HEARTBEAT_REQUEST,
        /** 心跳响应 */
        HEARTBEAT_RESPONSE
    }

    private long requestId;
    private MessageType type;
    private String host;
    private int port;
    private byte[] data;
    private long streamId;
    private int status;
    private String message;
    private Map<String, String> attachments;

    public ProxyMessage() {
        this.attachments = new HashMap<>();
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProxyMessage msg = new ProxyMessage();

        public Builder requestId(long requestId) {
            msg.requestId = requestId;
            return this;
        }

        public Builder type(MessageType type) {
            msg.type = type;
            return this;
        }

        public Builder host(String host) {
            msg.host = host;
            return this;
        }

        public Builder port(int port) {
            msg.port = port;
            return this;
        }

        public Builder data(byte[] data) {
            msg.data = data;
            return this;
        }

        public Builder streamId(long streamId) {
            msg.streamId = streamId;
            return this;
        }

        public Builder status(int status) {
            msg.status = status;
            return this;
        }

        public Builder message(String message) {
            msg.message = message;
            return this;
        }

        public Builder attachment(String key, String value) {
            msg.attachments.put(key, value);
            return this;
        }

        public ProxyMessage build() {
            return msg;
        }
    }

    // ==================== Getters & Setters ====================

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
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

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public long getStreamId() {
        return streamId;
    }

    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, String> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, String> attachments) {
        this.attachments = attachments;
    }

    @Override
    public String toString() {
        return "ProxyMessage{" +
                "requestId=" + requestId +
                ", type=" + type +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", streamId=" + streamId +
                ", status=" + status +
                ", dataLength=" + (data != null ? data.length : 0) +
                '}';
    }
}
