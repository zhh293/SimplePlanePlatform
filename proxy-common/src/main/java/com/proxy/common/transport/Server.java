package com.proxy.common.transport;

import java.net.InetSocketAddress;

/**
 * 服务端抽象接口 —— 定义服务实例的生命周期方法
 * <p>
 * 与 {@link Client} 对称，Client 代表一条到远程的连接，
 * Server 代表一个绑定到本地端口的服务端实例，接受来自多个 Client 的连接。
 * </p>
 * <p>
 * 实现类由 {@link Transporter#bind(URL, MessageHandler)} 创建并返回。
 * </p>
 */
public interface Server {

    /**
     * 启动服务端，绑定端口并开始接受连接
     *
     * @throws TransportException 绑定失败时抛出
     */
    void start() throws TransportException;

    /**
     * 关闭服务端，释放端口和所有连接资源
     * <p>
     * 优雅关闭：先停止接受新连接，等待已有连接处理完毕（或超时），然后释放资源。
     * </p>
     */
    void close();

    /**
     * 服务端是否处于活跃状态（已绑定端口且可接受连接）
     *
     * @return true 表示服务端正在运行
     */
    boolean isActive();

    /**
     * 获取当前活跃的连接数（已建立 TCP 连接的客户端数量）
     *
     * @return 活跃连接数
     */
    int getActiveConnectionCount();

    /**
     * 获取绑定的地址
     *
     * @return 绑定地址（host）
     */
    String getBindAddress();

    /**
     * 获取绑定的端口
     *
     * @return 绑定端口
     */
    int getBindPort();
}
