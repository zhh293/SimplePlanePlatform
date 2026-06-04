package com.proxy.remote.outbound;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 异步出站连接器
 * <p>
 * 根据目标地址使用 Netty Bootstrap 建立裸 TCP 连接。
 * 每次调用 connect() 都是全新连接（不做连接池），
 * 复用服务端 NettyServer 的 Worker EventLoopGroup 以避免额外线程开销。
 * </p>
 */
public class OutboundConnector {

    private static final Logger log = LoggerFactory.getLogger(OutboundConnector.class);

    private final EventLoopGroup workerGroup;
    private final int connectTimeoutMs;

    /**
     * @param workerGroup     复用自 NettyServer 的 worker 线程组
     * @param connectTimeoutMs 连接超时（毫秒）
     */
    public OutboundConnector(EventLoopGroup workerGroup, int connectTimeoutMs) {
        this.workerGroup = workerGroup;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * 异步连接目标服务器
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param session 出站会话（Pipeline 中的 OutboundHandler 需要它来回写数据）
     * @return CompletableFuture，成功时返回 Channel，失败时异常完成
     */
    public CompletableFuture<Channel> connect(String host, int port, OutboundSession session) {
        CompletableFuture<Channel> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // 极简 Pipeline：仅挂载 OutboundHandler
                        ch.pipeline().addLast("outboundHandler", new OutboundHandler(session));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) cf -> {
            if (cf.isSuccess()) {
                log.debug("Outbound connected: target={}:{}, streamId={}", host, port, session.getStreamId());
                future.complete(cf.channel());
            } else {
                log.warn("Outbound connect failed: target={}:{}, streamId={}", host, port, session.getStreamId(), cf.cause());
                future.completeExceptionally(cf.cause());
            }
        });

        return future;
    }
}
