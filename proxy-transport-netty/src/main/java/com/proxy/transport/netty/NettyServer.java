package com.proxy.transport.netty;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.TransportException;
import com.proxy.transport.netty.handler.CipherDecodeHandler;
import com.proxy.transport.netty.handler.CipherEncodeHandler;
import com.proxy.transport.netty.handler.HeartbeatHandler;
import com.proxy.transport.netty.handler.ProxyMessageDecoder;
import com.proxy.transport.netty.handler.ProxyMessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.proxy.transport.netty.handler.BackpressureHandler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Netty ServerBootstrap 的服务端实现
 * <p>
 * 支持 HTTP/2 多路复用，接收客户端连接和 Stream。
 * Pipeline 架构：
 * <pre>
 * 父 Channel: Http2FrameCodec → Http2MultiplexHandler
 * 子 Channel (每个 Stream):
 *   入站: CipherDecodeHandler → ProxyMessageDecoder → IdleStateHandler → HeartbeatHandler → ExchangeHandler
 *   出站: CipherEncodeHandler → ProxyMessageEncoder
 * </pre>
 * </p>
 */
public class NettyServer implements Server {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final URL url;
    /** 统一的 Stream Handler（ExchangeHandler），直接挂在每个 stream pipeline 末端 */
    private final ChannelHandler streamHandler;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(URL url, ChannelHandler streamHandler) {
        this.url = url;
        this.streamHandler = streamHandler;
    }

    @Override
    public void start() throws TransportException {
        int bossThreads = url.getParameter("bossThreads", 1);
        int workerThreads = url.getParameter("workerThreads", 0);
        int backlog = url.getParameter("backlog", 1024);
        int readIdleTimeout = url.getParameter("readIdleTimeout", 60);
        int maxStreams = url.getParameter("maxStreams", 100);
        String cipherName = url.getParameter("cipher", "aes-gcm");
        boolean backpressureEnabled = url.getParameter("backpressure", false);
        int backpressurePermits = url.getParameter("backpressurePermits", 64);

        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = workerThreads > 0
                ? new NioEventLoopGroup(workerThreads)
                : new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, backlog)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            // 父 Channel Pipeline: HTTP/2 帧编解码 + 多路复用
                            Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forServer()
                                    .initialSettings(Http2Settings.defaultSettings()
                                            .maxConcurrentStreams(maxStreams)
                                            .initialWindowSize(1024 * 1024))  // 1MB window (default 64KB is too small)
                                    .build();

                            // 子 Channel 初始化器（每个 HTTP/2 Stream）
                            Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(
                                    new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel streamCh) throws Exception {
                            ChannelPipeline pipeline = streamCh.pipeline();
                            // 根据 URL 配置加载 Cipher 实例
                            Cipher cipher = createCipher(cipherName);
                            // 入站方向（HEAD→TAIL 查找 InboundHandler）：解密 → 解码
                            // 出站方向（TAIL→HEAD 查找 OutboundHandler）：编码 → 加密
                            // 注册顺序与 Client 端一致：cipher-decode/encode → decoder/encoder → ...
                            pipeline.addLast("cipherDecode", new CipherDecodeHandler(cipher));
                            pipeline.addLast("cipherEncode", new CipherEncodeHandler(cipher));
                            // 背压处理器：位于解密之后、解码之前，拦截 Http2DataFrame
                            // 通过信用额度控制下游处理速率，额度耗尽时暂停窗口更新形成端到端背压
                            if (backpressureEnabled) {
                                pipeline.addLast("backpressure",
                                        new BackpressureHandler(backpressurePermits));
                            }
                            pipeline.addLast("decoder", new ProxyMessageDecoder());
                            pipeline.addLast("encoder", new ProxyMessageEncoder(true));
                            pipeline.addLast("idleState", new IdleStateHandler(
                                    readIdleTimeout, 0, 0, TimeUnit.SECONDS));
                            pipeline.addLast("heartbeat", new HeartbeatHandler());
                            pipeline.addLast("handler", streamHandler);
                        }
                                    });

                            ch.pipeline().addLast("frameCodec", frameCodec);
                            ch.pipeline().addLast("multiplexHandler", multiplexHandler);

                            // 连接建立后增大 connection-level flow control window（与客户端对齐）
                            // 默认 65535 bytes 太小，大流量场景下会阻塞所有 stream 的上行帧发送
                            ch.pipeline().addLast("window-update", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                    if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                                        Http2FrameCodec codec = ctx.pipeline().get(Http2FrameCodec.class);
                                        if (codec != null) {
                                            io.netty.handler.codec.http2.Http2Connection conn = codec.connection();
                                            int increment = 16 * 1024 * 1024 - 65535; // 增大到 ~16MB
                                            try {
                                                conn.local().flowController().incrementWindowSize(
                                                        conn.connectionStream(), increment);
                                                ctx.flush();
                                                log.info("Server: increased connection-level receive window by {} (total ~16MB)", increment);
                                            } catch (Exception e) {
                                                log.warn("Server: failed to increment connection window: {}", e.getMessage());
                                            }
                                        }
                                        ctx.pipeline().remove(this);
                                    }
                                    super.userEventTriggered(ctx, evt);
                                }
                            });

                            // 监控连接数
                            ch.closeFuture().addListener(f -> {
                                connectionCount.decrementAndGet();
                                log.debug("Client disconnected: {}, active connections: {}",
                                        ch.remoteAddress(), connectionCount.get());
                            });
                            connectionCount.incrementAndGet();
                            log.debug("Client connected: {}, active connections: {}",
                                    ch.remoteAddress(), connectionCount.get());
                        }
                    });

            ChannelFuture bindFuture = bootstrap.bind(url.getHost(), url.getPort()).sync();
            serverChannel = bindFuture.channel();
            active.set(true);

            log.info("NettyServer started on {}:{} (bossThreads={}, workerThreads={}, maxStreams={}, backlog={})",
                    url.getHost(), url.getPort(), bossThreads,
                    workerThreads > 0 ? workerThreads : "CPU×2", maxStreams, backlog);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted while binding to " + url.getAddress(), e);
        } catch (Exception e) {
            throw new TransportException("Failed to bind to " + url.getAddress(), e);
        }
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            log.info("Shutting down NettyServer on {}:{}", url.getHost(), url.getPort());

            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
            }

            log.info("NettyServer shut down successfully");
        }
    }

    @Override
    public boolean isActive() {
        return active.get() && serverChannel != null && serverChannel.isActive();
    }

    @Override
    public int getActiveConnectionCount() {
        return connectionCount.get();
    }

    @Override
    public String getBindAddress() {
        return url.getHost();
    }

    @Override
    public int getBindPort() {
        return url.getPort();
    }

    /**
     * 获取 Worker EventLoopGroup（供 OutboundConnector 复用）
     *
     * @return workerGroup
     */
    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    /**
     * 根据名称创建并初始化 Cipher 实例
     */
    private Cipher createCipher(String cipherName) {
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).getExtension(cipherName);
        String key = url.getParameter("cipherKey", "");
        if (!key.isEmpty()) {
            CipherConfig config = new CipherConfig(key.getBytes());
            cipher.init(config);
        } else {
            // NoneCipher 等不需要密钥的实现，也需要初始化
            cipher.init(new CipherConfig());
        }
        return cipher;
    }
}
