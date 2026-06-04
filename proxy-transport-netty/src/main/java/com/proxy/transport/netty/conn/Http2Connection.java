package com.proxy.transport.netty.conn;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.MessageHandler;
import com.proxy.transport.netty.StreamPipelineCustomizer;
import com.proxy.transport.netty.handler.CipherDecodeHandler;
import com.proxy.transport.netty.handler.CipherEncodeHandler;
import com.proxy.transport.netty.handler.ClientMessageHandler;
import com.proxy.transport.netty.handler.HeartbeatHandler;
import com.proxy.transport.netty.handler.ProxyMessageDecoder;
import com.proxy.transport.netty.handler.ProxyMessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单条 HTTP/2 连接。
 * <p>
 * 设计动机：在 HTTP/2 多路复用下，一条 TCP 连接即可承载成百上千个并发 Stream，
 * 因此“连接池 + maxConnections + 等待队列”是多余的，反而其阻塞式 acquire 会卡住
 * Netty IO 线程。本类用“单连接 + 非阻塞建流”取代之前的 ConnectionPool。
 * </p>
 * <p>
 * 职责：
 * <ul>
 *   <li>建立并预热一条到目标服务器的 HTTP/2 TCP 连接（父 Channel）；</li>
 *   <li>在该连接上以<strong>非阻塞</strong>方式开启 Stream 子 Channel；</li>
 *   <li>为每个 Stream 安装统一的 pipeline（加解密 / 编解码 / 心跳 / 业务 handler）。</li>
 * </ul>
 * </p>
 * <pre>
 * TCP Connection (Parent Channel)
 *   ├── SslHandler (可选)
 *   ├── Http2FrameCodec
 *   └── Http2MultiplexHandler
 *         ├── Stream 子 Channel 1
 *         ├── Stream 子 Channel 2
 *         └── ...
 * </pre>
 */
public class Http2Connection {

    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final URL url;
    private final Http2ClientConfig config;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;
    private final MessageHandler messageHandler;
    private final List<StreamPipelineCustomizer> pipelineCustomizers;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    private volatile SslContext sslContext;
    private volatile Channel parentChannel;

    public Http2Connection(URL url, MessageHandler handler) {
        this.url = url;
        this.messageHandler = handler;
        this.config = Http2ClientConfig.fromUrl(url);
        this.workerGroup = new NioEventLoopGroup(
                url.getParameter("ioThreads", Runtime.getRuntime().availableProcessors()));
        this.pipelineCustomizers = loadPipelineCustomizers();

        if (config.isSslEnabled()) {
            initSslContext();
        }
        this.bootstrap = initBootstrap();
    }

    /**
     * 建立（预热）唯一的 HTTP/2 TCP 连接。
     * <p>
     * 仅在客户端初始化阶段（非 IO 线程）调用一次。这里允许 sync()，
     * 因为它发生在主线程预热阶段，不会阻塞 Netty IO 线程。
     * </p>
     */
    public synchronized void connect() throws InterruptedException {
        if (parentChannel != null && parentChannel.isActive()) {
            return;
        }
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).sync();
        if (!future.isSuccess()) {
            throw new RuntimeException("Failed to connect to " + url.getHost() + ":" + url.getPort(),
                    future.cause());
        }
        this.parentChannel = future.channel();
        log.info("HTTP/2 connection established to {}:{}", url.getHost(), url.getPort());
    }

    /**
     * 以<strong>非阻塞</strong>方式在当前 TCP 连接上开启一个新的 HTTP/2 Stream。
     * <p>
     * 关键：返回 Netty {@link Future}，<strong>绝不</strong>调用 {@code .sync()}，
     * 因此可安全地在 Netty IO 线程上调用。调用方通过监听器拿到 Stream Channel。
     * </p>
     *
     * @return 完成时携带 Stream 子 Channel 的 Future
     */
    public Future<Channel> openStream() {
        Channel parent = this.parentChannel;
        Promise<Channel> promise = workerGroup.next().newPromise();

        if (closed.get() || parent == null || !parent.isActive()) {
            promise.setFailure(new IllegalStateException("HTTP/2 connection is not available"));
            return promise;
        }

        Http2StreamChannelBootstrap streamBootstrap = new Http2StreamChannelBootstrap(parent);
        streamBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                installStreamPipeline(ch);
            }
        });

        streamBootstrap.open().addListener((GenericFutureListener<Future<Channel>>) f -> {
            if (f.isSuccess()) {
                Channel streamChannel = f.getNow();
                activeStreams.incrementAndGet();
                streamChannel.closeFuture().addListener(cf -> {
                    activeStreams.decrementAndGet();
                    log.debug("HTTP/2 stream closed, active streams: {}", activeStreams.get());
                });
                log.debug("Opened HTTP/2 stream, active streams: {}", activeStreams.get());
                promise.setSuccess(streamChannel);
            } else {
                promise.setFailure(f.cause());
            }
        });
        return promise;
    }

    private void installStreamPipeline(Channel ch) {
        Cipher cipher = createCipher();
        ch.pipeline().addLast("cipher-decode", new CipherDecodeHandler(cipher));
        ch.pipeline().addLast("cipher-encode", new CipherEncodeHandler(cipher));
        ch.pipeline().addLast("decoder", new ProxyMessageDecoder());
        ch.pipeline().addLast("encoder", new ProxyMessageEncoder());
        ch.pipeline().addLast("idle",
                new IdleStateHandler(
                        config.getReadIdleTimeoutSec(),
                        config.getHeartbeatIntervalSec(),
                        0, TimeUnit.SECONDS));
        ch.pipeline().addLast("heartbeat", new HeartbeatHandler());
        ch.pipeline().addLast("handler", new ClientMessageHandler(messageHandler));

        for (StreamPipelineCustomizer customizer : pipelineCustomizers) {
            customizer.customize(ch.pipeline());
        }
    }

    private List<StreamPipelineCustomizer> loadPipelineCustomizers() {
        try {
            List<StreamPipelineCustomizer> customizers =
                    ExtensionLoader.getLoader(StreamPipelineCustomizer.class).getActivateExtensions("");
            if (!customizers.isEmpty()) {
                log.info("Loaded {} StreamPipelineCustomizer(s)", customizers.size());
            }
            return customizers;
        } catch (Exception e) {
            log.debug("No StreamPipelineCustomizer found via SPI (this is normal for server-side)");
            return java.util.Collections.emptyList();
        }
    }

    private void initSslContext() {
        try {
            sslContext = SslContextBuilder.forClient()
                    .sslProvider(SslProvider.JDK)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
            log.info("SSL context initialized for HTTP/2 (ALPN: h2)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSL context", e);
        }
    }

    private Bootstrap initBootstrap() {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast("ssl",
                                    sslContext.newHandler(ch.alloc(), url.getHost(), url.getPort()));
                        }
                        ch.pipeline().addLast("http2-codec",
                                Http2FrameCodecBuilder.forClient()
                                        .initialSettings(Http2Settings.defaultSettings()
                                                .maxConcurrentStreams(config.getMaxStreamsPerConnection()))
                                        .build());
                        // 入站（服务端推送）Stream 也使用同一套 pipeline
                        ch.pipeline().addLast("http2-multiplex",
                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel ch) {
                                        installStreamPipeline(ch);
                                    }
                                }));
                    }
                });
        return b;
    }

    private Cipher createCipher() {
        String cipherName = url.getParameter("cipher", "aes-gcm");
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).getExtension(cipherName);
        String key = url.getParameter("cipherKey", "");
        if (!key.isEmpty()) {
            cipher.init(new CipherConfig(key.getBytes()));
        } else {
            cipher.init(new CipherConfig());
        }
        return cipher;
    }

    public boolean isActive() {
        Channel ch = this.parentChannel;
        return !closed.get() && ch != null && ch.isActive();
    }

    public int getActiveStreamCount() {
        return activeStreams.get();
    }

    public String getStats() {
        return String.format("Http2Connection[active=%s, streams=%d]", isActive(), activeStreams.get());
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing HTTP/2 connection to {}:{}", url.getHost(), url.getPort());
            Channel ch = this.parentChannel;
            if (ch != null && ch.isActive()) {
                try {
                    ch.close();
                } catch (Exception e) {
                    log.warn("Error closing parent channel: {}", e.getMessage());
                }
            }
            try {
                workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for EventLoopGroup shutdown");
            }
            log.info("HTTP/2 connection closed");
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
