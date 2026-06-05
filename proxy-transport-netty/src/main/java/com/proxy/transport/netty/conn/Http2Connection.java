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
import io.netty.handler.codec.http2.Http2FrameCodec;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单条 HTTP/2 连接（支持自动重连）。
 * <p>
 * 设计动机：在 HTTP/2 多路复用下，一条 TCP 连接即可承载成百上千个并发 Stream，
 * 因此"连接池 + maxConnections + 等待队列"是多余的，反而其阻塞式 acquire 会卡住
 * Netty IO 线程。本类用"单连接 + 非阻塞建流"取代之前的 ConnectionPool。
 * </p>
 * <p>
 * 重连机制：
 * <ul>
 *   <li>当 TCP 连接断开且非主动关闭时，自动调度重连任务；</li>
 *   <li>重连采用指数退避策略（1s → 2s → 4s → ... → 60s），避免频繁重连；</li>
 *   <li>重连成功后重置退避计数，连接恢复可用。</li>
 * </ul>
 * </p>
 */
public class Http2Connection {

    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    // 重连相关常量
    private static final long RECONNECT_INITIAL_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 60000;
    private static final double RECONNECT_BACKOFF_MULTIPLIER = 2.0;

    private final URL url;
    private final Http2ClientConfig config;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;
    private final MessageHandler messageHandler;
    private final List<StreamPipelineCustomizer> pipelineCustomizers;

    /** 标记是否用户主动销毁（destroy），主动销毁后不再重连 */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    /** 标记当前是否正在重连中，防止并发重连 */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    /** 用于调度重连和健康检查任务（独立于 IO 线程） */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "http2-reconnect-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile SslContext sslContext;
    private volatile Channel parentChannel;
    private volatile ScheduledFuture<?> healthCheckFuture;

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
        this.reconnectAttempts.set(0);
        log.info("HTTP/2 connection established to {}:{}", url.getHost(), url.getPort());

        // 注册连接关闭监听器 —— 触发自动重连
        parentChannel.closeFuture().addListener(cf -> onConnectionLost());

        // 启动连接级健康检查
        startHealthCheck();
    }

    /**
     * 连接断开时的回调。
     * 如果非主动销毁，则触发自动重连。
     */
    private void onConnectionLost() {
        if (destroyed.get()) {
            log.info("Connection lost but already destroyed, skip reconnect");
            return;
        }
        log.warn("HTTP/2 connection to {}:{} lost, scheduling reconnect...", url.getHost(), url.getPort());
        scheduleReconnect();
    }

    /**
     * 调度重连任务（指数退避）。
     */
    private void scheduleReconnect() {
        if (destroyed.get()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            log.debug("Reconnect already in progress, skip");
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        long delay = calculateBackoffDelay(attempt);
        log.info("Scheduling reconnect attempt #{} in {}ms", attempt, delay);

        scheduler.schedule(this::doReconnect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行重连。
     */
    private void doReconnect() {
        if (destroyed.get()) {
            reconnecting.set(false);
            return;
        }

        try {
            log.info("Attempting reconnect #{} to {}:{}...", reconnectAttempts.get(), url.getHost(), url.getPort());
            ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).sync();
            if (future.isSuccess()) {
                this.parentChannel = future.channel();
                this.reconnectAttempts.set(0);
                this.reconnecting.set(false);
                log.info("Reconnect SUCCESS to {}:{}", url.getHost(), url.getPort());

                // 重新注册关闭监听器
                parentChannel.closeFuture().addListener(cf -> onConnectionLost());

                // 重启健康检查
                startHealthCheck();
            } else {
                reconnecting.set(false);
                log.warn("Reconnect failed: {}", future.cause() != null ? future.cause().getMessage() : "unknown");
                scheduleReconnect();
            }
        } catch (Exception e) {
            reconnecting.set(false);
            log.warn("Reconnect attempt #{} failed: {}", reconnectAttempts.get(), e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 计算指数退避延迟。
     */
    private long calculateBackoffDelay(int attempt) {
        long delay = (long) (RECONNECT_INITIAL_DELAY_MS * Math.pow(RECONNECT_BACKOFF_MULTIPLIER, attempt - 1));
        return Math.min(delay, RECONNECT_MAX_DELAY_MS);
    }

    /**
     * 启动连接级健康检查。
     * 每隔 30 秒检测 parentChannel 是否还活着，如果发现断开则主动触发重连。
     * 这用于检测"半关闭"状态（如休眠唤醒后 TCP 连接已死但 Netty 还未感知）。
     */
    private void startHealthCheck() {
        stopHealthCheck();
        healthCheckFuture = scheduler.scheduleAtFixedRate(() -> {
            if (destroyed.get()) {
                stopHealthCheck();
                return;
            }
            Channel ch = parentChannel;
            if (ch == null || !ch.isActive()) {
                log.warn("Health check: connection inactive, triggering reconnect");
                scheduleReconnect();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * 停止健康检查。
     */
    private void stopHealthCheck() {
        ScheduledFuture<?> f = healthCheckFuture;
        if (f != null && !f.isCancelled()) {
            f.cancel(false);
            healthCheckFuture = null;
        }
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

        if (destroyed.get() || parent == null || !parent.isActive()) {
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
                        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forClient()
                                .initialSettings(Http2Settings.defaultSettings()
                                        .maxConcurrentStreams(config.getMaxStreamsPerConnection())
                                        .initialWindowSize(1024 * 1024))  // 1MB stream window
                                .build();
                        ch.pipeline().addLast("http2-codec", frameCodec);
                        // 入站（服务端推送）Stream 也使用同一套 pipeline
                        ch.pipeline().addLast("http2-multiplex",
                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel streamCh) {
                                        installStreamPipeline(streamCh);
                                    }
                                }));
                        // 连接建立后增大 connection-level flow control window
                        ch.pipeline().addLast("window-update", new io.netty.channel.ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) throws Exception {
                                if (evt instanceof io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                                    Http2FrameCodec codec = ctx.pipeline().get(Http2FrameCodec.class);
                                    if (codec != null) {
                                        io.netty.handler.codec.http2.Http2Connection conn = codec.connection();
                                        int increment = 16 * 1024 * 1024 - 65535;
                                        try {
                                            conn.local().flowController().incrementWindowSize(
                                                    conn.connectionStream(), increment);
                                            ctx.flush();
                                            log.info("Increased connection-level window by {} (total ~16MB)", increment);
                                        } catch (Exception e) {
                                            log.warn("Failed to increment connection window: {}", e.getMessage());
                                        }
                                    }
                                    ctx.pipeline().remove(this);
                                }
                                super.userEventTriggered(ctx, evt);
                            }
                        });
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
        return !destroyed.get() && ch != null && ch.isActive();
    }

    /**
     * 是否正在重连中。
     * 上层可根据此判断暂时不可用（但会自动恢复），区别于已销毁（永久不可用）。
     */
    public boolean isReconnecting() {
        return reconnecting.get();
    }

    public int getActiveStreamCount() {
        return activeStreams.get();
    }

    public String getStats() {
        return String.format("Http2Connection[active=%s, reconnecting=%s, attempts=%d, streams=%d]",
                isActive(), isReconnecting(), reconnectAttempts.get(), activeStreams.get());
    }

    /**
     * 主动销毁连接（用户关闭客户端时调用）。
     * 销毁后不再自动重连。
     */
    public void close() {
        if (destroyed.compareAndSet(false, true)) {
            log.info("Destroying HTTP/2 connection to {}:{}", url.getHost(), url.getPort());
            stopHealthCheck();
            scheduler.shutdownNow();
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
            log.info("HTTP/2 connection destroyed");
        }
    }

    public boolean isClosed() {
        return destroyed.get();
    }
}
