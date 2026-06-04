package com.proxy.local.sysproxy;

/**
 * 系统代理设置异常
 */
public class SystemProxyException extends RuntimeException {

    public SystemProxyException(String message) {
        super(message);
    }

    public SystemProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
