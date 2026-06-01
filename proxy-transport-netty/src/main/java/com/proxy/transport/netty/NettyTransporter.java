package com.proxy.transport.netty;

import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.common.transport.Transporter;
import com.proxy.common.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty Transporter 实现 —— 工厂类
 * <p>
 * 职责单一：每次调用 connect() 创建并返回一个 NettyClient 实例。
 * 将上层传入的 MessageHandler 透传给 NettyClient，在建连时设置到 Pipeline 上。
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
}
