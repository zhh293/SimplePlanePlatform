package com.proxy.common.exchange;

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
}
