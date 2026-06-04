package com.proxy.exchange.header;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.Transporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exchanger 默认实现 —— 包装 Transporter，对上层屏蔽底层细节
 * <p>
 * connect() 内部流程：
 * <pre>
 * 1. 创建 ExchangeHandler（实现 MessageHandler）
 *    - IO 线程收到响应时，根据 requestId 调用 DefaultFuture.received() 唤醒业务线程
 * 2. 通过 SPI 加载 Transporter，调用 transporter.connect(url, handler)
 *    - Transporter 建连时把 handler 设到 Pipeline 上
 *    - 返回底层 Client
 * 3. 包装成 HeaderExchangeClient 返回
 *    - HeaderExchangeClient 提供 request(message, timeout) 方法
 *    - 内部做 requestId 生成 + Future 映射 + 调用 client.send()
 * </pre>
 * </p>
 * <p>
 * bind() 内部流程：
 * <pre>
 * 1. 创建 ServerExchangeHandler（持有 Invoker 引用，实现 InvokerAware）
 * 2. 通过 SPI 加载 Transporter，调用 transporter.bind(url, handler)
 *    - Transporter 从 handler 中提取 Invoker，注入 ServerChannelHandler
 *    - 返回底层 Server
 * 3. 包装成 HeaderExchangeServer 返回
 * </pre>
 * </p>
 */
public class HeaderExchanger implements Exchanger {

    private static final Logger log = LoggerFactory.getLogger(HeaderExchanger.class);

    @Override
    public ExchangeClient connect(URL url) {
        // 1. 创建 ExchangeHandler（响应处理器）
        ExchangeHandler handler = new ExchangeHandler();

        // 2. 通过 SPI 加载 Transporter，建连时把 handler 塞进去
        Transporter transporter = ExtensionLoader.getLoader(Transporter.class).getDefaultExtension();
        Client client = transporter.connect(url, handler);

        // 3. 包装成 ExchangeClient 返回（传入 handler，以支持数据面推送回调注册）
        HeaderExchangeClient exchangeClient = new HeaderExchangeClient(client, url, handler);
        log.info("HeaderExchanger created ExchangeClient to {}:{}", url.getHost(), url.getPort());
        return exchangeClient;
    }

    @Override
    public ExchangeServer bind(URL url, Invoker invoker) {
        // 1. 创建 ServerExchangeHandler（持有 Invoker，实现 InvokerAware）
        ServerExchangeHandler handler = new ServerExchangeHandler(invoker);

        // 2. 通过 SPI 加载 Transporter，绑定端口
        Transporter transporter = ExtensionLoader.getLoader(Transporter.class).getDefaultExtension();
        Server server = transporter.bind(url, handler);

        // 3. 包装成 HeaderExchangeServer 返回
        HeaderExchangeServer exchangeServer = new HeaderExchangeServer(server);
        log.info("HeaderExchanger bound ExchangeServer on {}:{}", url.getHost(), url.getPort());
        return exchangeServer;
    }
}
