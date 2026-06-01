package com.proxy.common.transport;

import com.proxy.common.model.ProxyMessage;

/**
 * 消息处理器接口 —— 处理从远程接收到的消息
 */
public interface MessageHandler {

    /**
     * 收到消息时回调
     *
     * @param message 接收到的消息
     */
    void onMessage(ProxyMessage message);

    /**
     * 发生错误时回调
     *
     * @param cause 异常原因
     */
    void onError(Throwable cause);

    /**
     * 连接断开时回调
     */
    void onDisconnected();
}
