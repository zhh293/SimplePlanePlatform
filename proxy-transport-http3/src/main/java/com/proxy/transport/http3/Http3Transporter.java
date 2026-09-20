package com.proxy.transport.http3;

import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.TransportException;
import com.proxy.common.transport.Transporter;
import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SPI entry point for HTTP/3 over QUIC. */
public final class Http3Transporter implements Transporter {
    private static final Logger log = LoggerFactory.getLogger(Http3Transporter.class);

    @Override
    public Client connect(URL url, MessageHandler handler) throws TransportException {
        if (!(handler instanceof ChannelHandler)) {
            throw new TransportException("MessageHandler must also implement ChannelHandler for HTTP/3 client pipeline");
        }
        try {
            Http3Client client = new Http3Client(url, handler);
            log.info("Created Http3Client to UDP {}:{}", url.getHost(), url.getPort());
            return client;
        } catch (Exception e) {
            throw new TransportException("Failed to create Http3Client to " + url.getAddress(), e);
        }
    }

    @Override
    public Server bind(URL url, MessageHandler handler) throws TransportException {
        if (!(handler instanceof ChannelHandler)) {
            throw new TransportException("MessageHandler must also implement ChannelHandler for HTTP/3 server pipeline");
        }
        Http3Server server = new Http3Server(url, (ChannelHandler) handler);
        server.start();
        return server;
    }
}
