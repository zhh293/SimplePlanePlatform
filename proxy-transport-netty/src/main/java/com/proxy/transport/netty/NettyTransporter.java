package com.proxy.transport.netty;

import com.proxy.common.filter.Invoker;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.InvokerAware;
import com.proxy.common.transport.MessageHandler;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.Transporter;
import com.proxy.common.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty Transporter 实现 —— 工厂类
 * <p>
 * 职责单一：
 * <ul>
 *   <li>connect()：创建并返回 NettyClient（客户端连接）</li>
 *   <li>bind()：创建并启动 NettyServer（服务端监听）</li>
 * </ul>
 * 将上层传入的 MessageHandler/Invoker 透传给对应的 Netty 实现类。
 * </p>
 */
public class NettyTransporter implements Transporter {

    private static final Logger log = LoggerFactory.getLogger(NettyTransporter.class);

    @Override
    public Client connect(URL url, MessageHandler handler) throws TransportException {
        try {
            NettyClient client = new NettyClient(url, handler);
            log.info("Created NettyClient to {}:{}", url.getHost(), url.getPort());
            return client;
        } catch (Exception e) {
            throw new TransportException("Failed to create NettyClient to " +
                    url.getHost() + ":" + url.getPort(), e);
        }
    }

    @Override
    public Server bind(URL url, MessageHandler handler) throws TransportException {
        try {
            // 从 MessageHandler 中获取 Invoker（ServerExchangeHandler 持有 Invoker 引用）
            Invoker invoker = extractInvoker(handler);
            NettyServer server = new NettyServer(url, invoker);
            server.start();
            log.info("NettyServer started on {}:{}", url.getHost(), url.getPort());
            return server;
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Failed to start NettyServer on " +
                    url.getHost() + ":" + url.getPort(), e);
        }
    }

    /**
     * 从 MessageHandler 中提取 Invoker
     * <p>
     * 由于"内聚一层"方案中 ServerChannelHandler 需要直接持有 Invoker，
     * 而 Transporter.bind() 签名只接受 MessageHandler，
     * 因此要求传入的 handler 实现 InvokerAware 接口以暴露 Invoker。
     * </p>
     */
    private Invoker extractInvoker(MessageHandler handler) {
        if (handler instanceof InvokerAware) {
            return ((InvokerAware) handler).getInvoker();
        }
        throw new IllegalArgumentException(
                "MessageHandler must implement InvokerAware to provide Invoker for server-side binding. " +
                "Actual type: " + handler.getClass().getName());
    }
}
