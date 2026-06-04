package com.proxy.common.exchange;

import com.proxy.common.transport.Server;

/**
 * 交换层服务端抽象 —— 包装底层 {@link Server}
 * <p>
 * 与 {@link ExchangeClient} 对称：
 * <ul>
 *   <li>{@link ExchangeClient} 包装 {@link com.proxy.common.transport.Client}，提供请求-响应能力</li>
 *   <li>{@code ExchangeServer} 包装 {@link Server}，提供服务端生命周期管理</li>
 * </ul>
 * </p>
 * <p>
 * 由 {@link Exchanger#bind(com.proxy.common.model.URL, com.proxy.common.filter.Invoker)} 创建并返回。
 * </p>
 */
public interface ExchangeServer {

    /**
     * 关闭服务端（委托给底层 Server）
     */
    void close();

    /**
     * 服务端是否活跃
     *
     * @return true 表示服务端正在运行
     */
    boolean isActive();

    /**
     * 获取底层传输层 Server 实例
     *
     * @return 底层 Server
     */
    Server getServer();
}
