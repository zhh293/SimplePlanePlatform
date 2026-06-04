package com.proxy.common.exchange;

import com.proxy.common.filter.Invoker;
import com.proxy.common.model.URL;
import com.proxy.common.spi.SPI;

/**
 * 请求-响应交换层 SPI 接口
 * <p>
 * Exchanger 包装 Transporter，对上层屏蔽底层细节。
 * connect() 内部：
 * <pre>
 * 1. 创建 ExchangeHandler（实现 MessageHandler，处理响应 → DefaultFuture.received）
 * 2. 调用 transporter.connect(url, handler) → 拿到底层 Client
 * 3. 包装成 ExchangeClient 返回给上层
 * </pre>
 * </p>
 * <p>
 * bind() 内部：
 * <pre>
 * 1. 创建 ServerExchangeHandler（将 Invoker 注入）
 * 2. 调用 transporter.bind(url, handler) → 拿到底层 Server
 * 3. 包装成 ExchangeServer 返回给上层
 * </pre>
 * </p>
 * <p>
 * 上层使用方式（ProxyBootstrap）：
 * <pre>
 * Exchanger exchanger = SPI.load("header");
 * for (i = 0; i < coreSize; i++) {
 *     ExchangeClient client = exchanger.connect(url);
 *     invokers.add(new ClientInvoker(client));
 * }
 * </pre>
 * </p>
 */
@SPI("header")
public interface Exchanger {

    /**
     * 创建一个到远程服务器的 ExchangeClient
     * <p>
     * 内部组装 ExchangeHandler + 调用 Transporter 建连 + 包装成 ExchangeClient。
     * 返回的 ExchangeClient 具备请求-响应能力（requestId + Future 映射）。
     * </p>
     *
     * @param url 远程服务器地址及参数
     * @return ExchangeClient 实例
     */
    ExchangeClient connect(URL url);

    /**
     * 创建一个到远程服务器的 ExchangeClient，并注册服务端推送消息的回调
     * <p>
     * 与 {@link #connect(URL)} 相同，但额外支持处理远程服务端主动推送的消息
     * （requestId=0 的 DATA，如目标网站返回的数据）。
     * </p>
     *
     * @param url              远程服务器地址及参数
     * @param pushCallback     服务端推送消息的回调处理器（可选），null 时等同于 connect(url)
     * @return ExchangeClient 实例
     */
    default ExchangeClient connect(URL url, Object pushCallback) {
        return connect(url);
    }

    /**
     * 绑定本地端口，创建并启动 ExchangeServer
     * <p>
     * 与 {@link #connect(URL)} 对称：connect 创建客户端，bind 创建服务端。
     * 内部组装 ServerExchangeHandler + 调用 Transporter.bind() + 包装成 ExchangeServer。
     * </p>
     * <p>
     * invoker 是已封装 Filter 链的最终调用器，Exchanger 不关心 Filter 编排细节，
     * 只负责将 invoker 注入到消息处理链路中。
     * </p>
     *
     * @param url     绑定地址及参数
     * @param invoker 已封装 Filter 链的调用器（由上层 ProxyRemoteServer 组装）
     * @return ExchangeServer 实例
     */
    default ExchangeServer bind(URL url, Invoker invoker) {
        throw new UnsupportedOperationException("bind() not supported by " + getClass().getName());
    }
}
