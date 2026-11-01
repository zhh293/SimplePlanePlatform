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
    private final java.util.concurrent.Semaphore udpPermits = new java.util.concurrent.Semaphore(1024);

    /** One UDP socket per association; enforce its resolved fixed peer in both directions. */
    public CompletableFuture<Channel> connectUdp(String host, int port, OutboundSession session) {
        CompletableFuture<Channel> result = new CompletableFuture<>();
        if (!udpPermits.tryAcquire()) {
            result.completeExceptionally(new IllegalStateException("UDP association limit reached"));
            return result;
        }
        ChannelFuture connecting;
        try {
            connecting = new Bootstrap().group(workerGroup)
                    .channelFactory(UdpOutboundChannel::create)
                    .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(65535))
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 65536))
                    .handler(new ChannelInitializer<io.netty.channel.socket.DatagramChannel>() {
                        @Override protected void initChannel(io.netty.channel.socket.DatagramChannel ch) {
                            ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(0, 0, 60));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, io.netty.channel.socket.DatagramPacket packet) {
                                    if (!packet.sender().equals(UdpOutboundChannel.peer(ctx.channel()))) return;
                                    byte[] bytes = new byte[packet.content().readableBytes()];
                                    packet.content().readBytes(bytes);
                                    session.writeBack(bytes);
                                }
                                @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                    if (event instanceof io.netty.handler.timeout.IdleStateEvent) session.closeFromTarget();
                                    else ctx.fireUserEventTriggered(event);
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    log.debug("UDP outbound failed: {}", session.getSessionKey(), cause);
                                    session.closeFromTarget();
                                }
                                @Override public void channelInactive(ChannelHandlerContext ctx) {
                                    session.closeFromTarget();
                                }
                            });
                        }
                    }).connect(host, port);
        } catch (RuntimeException error) {
            udpPermits.release();
            result.completeExceptionally(error);
            return result;
        }
        connecting.channel().closeFuture().addListener(f -> udpPermits.release());
        ChannelFutureListener inboundClosed = f -> result.cancel(false);
        session.getInboundCtx().channel().closeFuture().addListener(inboundClosed);
        result.whenComplete((channel, error) -> {
            session.getInboundCtx().channel().closeFuture().removeListener(inboundClosed);
            if (result.isCancelled()) {
                connecting.cancel(false);
                connecting.channel().close();
            }
        });
        connecting.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                f.channel().close();
                result.completeExceptionally(f.cause());
            } else {
                try {
                    UdpOutboundChannel.prepare(f.channel());
                } catch (java.io.IOException error) {
                    f.channel().close();
                    result.completeExceptionally(error);
                    return;
                }
                if (!session.getInboundCtx().channel().isActive()
                    || session.getState() == OutboundSession.SessionState.CLOSED
                    || !result.complete(f.channel())) {
                    f.channel().close();
                    result.completeExceptionally(new java.nio.channels.ClosedChannelException());
                }
            }
        });
        return result;
    }

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

        ChannelFuture connecting = bootstrap.connect(host, port);
        ChannelFutureListener cancelOnInboundClose = ignored -> future.cancel(false);
        session.getInboundCtx().channel().closeFuture().addListener(cancelOnInboundClose);
        future.whenComplete((channel, error) -> {
            session.getInboundCtx().channel().closeFuture().removeListener(cancelOnInboundClose);
            if (future.isCancelled()) {
                connecting.cancel(false);
                connecting.channel().close();
            }
        });
        connecting.addListener((ChannelFutureListener) cf -> {
            if (cf.isSuccess()) {
                log.debug("Outbound connected: target={}:{}, sessionKey={}", host, port, session.getSessionKey());
                if (session.getState() == OutboundSession.SessionState.CLOSED
                        || !session.getInboundCtx().channel().isActive()
                        || !future.complete(cf.channel())) {
                    cf.channel().close();
                    future.completeExceptionally(new java.nio.channels.ClosedChannelException());
                }
            } else {
                log.warn("Outbound connect failed: target={}:{}, sessionKey={}", host, port, session.getSessionKey(), cf.cause());
                future.completeExceptionally(cf.cause());
            }
        });

        return future;
    }
}
