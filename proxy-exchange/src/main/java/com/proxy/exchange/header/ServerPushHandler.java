package com.proxy.exchange.header;

import com.proxy.common.model.ProxyMessage;

/**
 * 服务端推送消息处理器接口
 * <p>
 * 用于处理远程服务端主动推送的消息（requestId=0），
 * 例如目标网站返回的数据通过 streamId 路由回浏览器。
 * </p>
 */
@FunctionalInterface
public interface ServerPushHandler {

    /**
     * 处理服务端推送的消息
     *
     * @param message 推送消息（requestId=0，携带 streamId 和 data）
     */
    void onServerPush(ProxyMessage message);
}
