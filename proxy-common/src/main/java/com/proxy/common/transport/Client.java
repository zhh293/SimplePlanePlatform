package com.proxy.common.transport;

import com.proxy.common.model.ProxyMessage;

/**
 * 客户端连接 —— 代表一条到远程服务器的连接
 * <p>
 * 由 Transporter.connect(url, handler) 创建，每个 Client 底层对应一条 HTTP/2 TCP 连接。
 * MessageHandler 在构造时传入，IO 线程收到消息后直接回调 handler。
 * </p>
 * <p>
 * 上层使用方式：
 * <pre>
 * Exchanger.connect(url) 内部：
 *   1. 创建 ExchangeHandler（实现 MessageHandler）
 *   2. transporter.connect(url, handler) → 返回 Client
 *   3. 包装成 ExchangeClient 返回给上层
 *
 * ProxyBootstrap:
 *   1. exchanger.connect(url) × N → 创建 N 个 ExchangeClient
 *   2. 每个 ExchangeClient 包装成 ClientInvoker
 *   3. 所有 Invoker 汇聚到 ClusterInvoker（负载均衡 + 容错）
 * </pre>
 * </p>
 */
public interface Client {

    /**
     * 发送消息
     * <p>
     * 根据 message.getStreamId() 找到或创建对应的 HTTP/2 Stream 发送。
     * 同一个 streamId 的消息走同一个 Stream，不同 streamId 互相隔离。
     * </p>
     *
     * @param message 代理消息
     */
    void send(ProxyMessage message);

    /**
     * 关闭 Client（释放底层连接和所有 Stream）
     */
    void close();

    /**
     * 是否可用
     *
     * @return true 表示底层连接存活且可以发送消息
     */
    boolean isAvailable();

    /**
     * 获取当前活跃的 Stream 数量（用于负载均衡）
     *
     * @return 活跃 Stream 数
     */
    int getActiveStreamCount();

    /**
     * 关闭指定 streamId 的 Stream
     * <p>
     * 用于在收到 DISCONNECT 响应后清理对应的 Stream 资源。
     * 默认实现为空操作，由具体传输层实现覆写。
     * </p>
     *
     * @param streamId 要关闭的 Stream ID
     */
    default void closeStream(long streamId) {
        // 默认空操作
    }
}
