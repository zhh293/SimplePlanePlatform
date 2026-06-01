package com.proxy.common.transport;

import com.proxy.common.model.URL;
import com.proxy.common.spi.SPI;

/**
 * 传输层 SPI 接口 —— 工厂模式
 * <p>
 * 负责创建到远程服务器的 Client 连接。
 * 调用方传入 MessageHandler，Transporter 在建连时将其设置到 Client 的 Pipeline 上，
 * 这样 IO 线程收到消息后能直接回调上层处理器。
 * </p>
 * <p>
 * 默认实现为 Netty（基于 HTTP/2 多路复用）。
 * </p>
 */
@SPI("netty")
public interface Transporter {

    /**
     * 创建一个到远程服务器的 Client 连接
     * <p>
     * 每次调用返回一个新的 Client 实例，底层对应一条 HTTP/2 TCP 连接。
     * handler 会被设置到连接的 Pipeline 上，IO 线程收到消息时回调 handler。
     * </p>
     *
     * @param url     远程服务器地址及参数
     * @param handler 消息处理器（由上层 Exchanger 创建，处理响应回调）
     * @return Client 实例
     * @throws TransportException 连接失败时抛出
     */
    Client connect(URL url, MessageHandler handler) throws TransportException;
}
