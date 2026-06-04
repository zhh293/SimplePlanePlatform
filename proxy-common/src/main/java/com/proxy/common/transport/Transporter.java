package com.proxy.common.transport;

import com.proxy.common.model.URL;
import com.proxy.common.spi.SPI;

/**
 * 传输层 SPI 接口 —— 工厂模式
 * <p>
 * 负责创建到远程服务器的 Client 连接，以及绑定本地端口创建 Server 实例。
 * 调用方传入 MessageHandler，Transporter 在建连/绑定时将其设置到 Pipeline 上，
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

    /**
     * 绑定本地端口，创建并启动 Server 实例
     * <p>
     * 与 {@link #connect(URL, MessageHandler)} 对称：connect 创建客户端连接，bind 创建服务端监听。
     * Transporter 实现负责组装 Pipeline（编解码、心跳、加密等），
     * 并将 handler 设置为消息处理入口。
     * </p>
     *
     * @param url     绑定地址及参数（host、port、workerThreads、backlog 等）
     * @param handler 消息处理器（由上层 Exchanger 传入，处理请求并回写响应）
     * @return Server 实例（已启动，可接受连接）
     * @throws TransportException 绑定失败时抛出
     */
    default Server bind(URL url, MessageHandler handler) throws TransportException {
        throw new UnsupportedOperationException("bind() not supported by " + getClass().getName());
    }
}
