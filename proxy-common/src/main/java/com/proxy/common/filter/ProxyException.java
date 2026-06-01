package com.proxy.common.filter;

/**
 * 代理异常 —— 调用链中的通用异常
 */
public class ProxyException extends RuntimeException {

    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
