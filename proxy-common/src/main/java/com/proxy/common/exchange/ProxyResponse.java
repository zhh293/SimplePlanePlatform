package com.proxy.common.exchange;

/**
 * 代理响应
 */
public class ProxyResponse {

    public static final int OK = 200;
    public static final int ERROR = 500;
    public static final int TIMEOUT = 408;

    private long requestId;
    private int status;
    private byte[] data;
    private String errorMessage;

    public ProxyResponse() {
    }

    public ProxyResponse(long requestId, int status) {
        this.requestId = requestId;
        this.status = status;
    }

    public static ProxyResponse ok(long requestId) {
        return new ProxyResponse(requestId, OK);
    }

    public static ProxyResponse ok(long requestId, byte[] data) {
        ProxyResponse response = new ProxyResponse(requestId, OK);
        response.setData(data);
        return response;
    }

    public static ProxyResponse error(long requestId, String message) {
        ProxyResponse response = new ProxyResponse(requestId, ERROR);
        response.setErrorMessage(message);
        return response;
    }

    public boolean isSuccess() {
        return status == OK;
    }

    // ==================== Getters & Setters ====================

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public String toString() {
        return "ProxyResponse{" +
                "requestId=" + requestId +
                ", status=" + status +
                ", errorMessage='" + errorMessage + '\'' +
                ", dataLength=" + (data != null ? data.length : 0) +
                '}';
    }
}
