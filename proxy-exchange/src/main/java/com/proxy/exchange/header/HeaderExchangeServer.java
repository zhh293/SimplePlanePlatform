package com.proxy.exchange.header;

import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.transport.Server;

/**
 * 交换层服务端包装类
 * <p>
 * 持有底层 {@link Server} 实例，委托生命周期方法。
 * 与 {@link HeaderExchangeClient} 对称。
 * </p>
 */
public class HeaderExchangeServer implements ExchangeServer {

    private final Server server;

    public HeaderExchangeServer(Server server) {
        this.server = server;
    }

    @Override
    public void close() {
        server.close();
    }

    @Override
    public boolean isActive() {
        return server.isActive();
    }

    @Override
    public Server getServer() {
        return server;
    }
}
