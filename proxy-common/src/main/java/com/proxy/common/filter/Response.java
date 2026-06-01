package com.proxy.common.filter;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用响应
 */
public class Response {

    public static final int OK = 200;
    public static final int ERROR = 500;
    public static final int TIMEOUT = 408;

    private int status;
    private byte[] data;
    private String errorMessage;
    private Map<String, Object> attachments;

    public Response() {
        this.attachments = new HashMap<>();
    }

    public Response(int status) {
        this();
        this.status = status;
    }

    public static Response ok() {
        return new Response(OK);
    }

    public static Response ok(byte[] data) {
        Response response = new Response(OK);
        response.setData(data);
        return response;
    }

    public static Response error(String message) {
        Response response = new Response(ERROR);
        response.setErrorMessage(message);
        return response;
    }

    public static Response empty() {
        return new Response(OK);
    }

    public boolean isSuccess() {
        return status == OK;
    }

    // ==================== Getters & Setters ====================

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

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, Object> attachments) {
        this.attachments = attachments;
    }

    @Override
    public String toString() {
        return "Response{" +
                "status=" + status +
                ", errorMessage='" + errorMessage + '\'' +
                ", dataLength=" + (data != null ? data.length : 0) +
                '}';
    }
}
