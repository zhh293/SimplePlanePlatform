package com.proxy.transport.netty.pool;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.MessageHandler;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP/2 连接池
 * <p>
 * 管理到目标服务器的 HTTP/2 连接。利用 HTTP/2 多路复用特性，
 * 一条 TCP 连接可以承载多个并发 Stream，大幅减少连接数。
 * </p>
 * <p>
 * 当所有连接的 Stream 都满时，调用方会进入等待队列，
 * 直到有 Stream 释放或超时。
 * </p>
 * <p>
 * 架构：
 * <pre>
 * TCP Connection (Parent Channel)
 *   ├── SslHandler (可选)
 *   ├── Http2FrameCodec
 *   └── Http2MultiplexHandler
 *         ├── Stream 子 Channel 1
 *         │     ├── CipherDecodeHandler (入站解密, SPI)
 *         │     ├── CipherEncodeHandler (出站加密, SPI)
 *         │     ├── ProxyMessageDecoder (Codec, SPI)
 *         │     ├── ProxyMessageEncoder (Codec, SPI)
 *         │     ├── IdleStateHandler
 *         │     ├── HeartbeatHandler
 *         │     └── ClientMessageHandler
 *         ├── Stream 子 Channel 2
 *         │     └── ...
 *         └── ...
 * </pre>
 * </p>
 */
public class ConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

    private final URL url;
    private final PoolConfig config;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;
    private final List<Http2PooledConnection> connections;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 等待队列锁与条件变量
     */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition streamAvailable = lock.newCondition();

    /**
     * 当前等待中的线程数（用于监控）
     */
    private final AtomicInteger waitingCount = new AtomicInteger(0);

    private final MessageHandler messageHandler;
    private volatile SslContext sslContext;

    public ConnectionPool(URL url, MessageHandler handler) {
        this.url = url;
        this.messageHandler = handler;
        this.config = PoolConfig.fromUrl(url);
        this.connections = new CopyOnWriteArrayList<Http2PooledConnection>();
        this.workerGroup = new NioEventLoopGroup(
                url.getParameter("ioThreads", Runtime.getRuntime().availableProcessors()));

        if (config.isSslEnabled()) {
            initSslContext();
        }

        this.bootstrap = initBootstrap();
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
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (sslContext != null) {
                            ch.pipeline().addLast("ssl",
                                    sslContext.newHandler(ch.alloc(), url.getHost(), url.getPort()));
                        }

                        // HTTP/2 帧编解码器
                        ch.pipeline().addLast("http2-codec",
                                Http2FrameCodecBuilder.forClient()
                                        .initialSettings(Http2Settings.defaultSettings()
                                                .maxConcurrentStreams(config.getMaxStreamsPerConnection()))
                                        .build());

                        // HTTP/2 多路复用处理器 - 为每个 Stream 创建子 Channel
                        ch.pipeline().addLast("http2-multiplex",
                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel ch) throws Exception {
                                        // 服务端推送的 Stream（入站 Stream）
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
                                        ch.pipeline().addLast("handler",
                                                new ClientMessageHandler(messageHandler));
                                    }
                                }));
                    }
                });
        return b;
    }


    /**
     * 获取一个可用的 HTTP/2 连接（带等待队列）
     * <p>
     * 优先选择活跃 Stream 数最少的连接（负载均衡），
     * 如果所有连接都满了且无法创建新连接，则进入等待队列，
     * 直到有 Stream 释放或超时。
     * </p>
     *
     * @return 可用的连接
     * @throws TimeoutException 等待超时
     * @throws InterruptedException 等待被中断
     */
    public Http2PooledConnection acquireConnection() throws Exception {
        return acquireConnection(config.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * 获取一个可用的 HTTP/2 连接（带超时等待）
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return 可用的连接
     * @throws TimeoutException 等待超时
     * @throws InterruptedException 等待被中断
     */
    public Http2PooledConnection acquireConnection(long timeout, TimeUnit unit) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("Connection pool is closed");
        }

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        lock.lock();
        try {
            while (true) {
                if (closed.get()) {
                    throw new IllegalStateException("Connection pool is closed");
                }

                // 清理已断开的连接
                for (int i = connections.size() - 1; i >= 0; i--) {
                    if (!connections.get(i).isActive()) {
                        Http2PooledConnection removed = connections.remove(i);
                        log.info("Removed dead connection from pool: {}", removed);
                    }
                }

                // 找一个可用的连接（Stream 数未满），选最空闲的
                Http2PooledConnection best = null;
                for (Http2PooledConnection conn : connections) {
                    if (conn.isAvailable()) {
                        if (best == null || conn.getActiveStreamCount() < best.getActiveStreamCount()) {
                            best = conn;
                        }
                    }
                }

                if (best != null) {
                    return best;
                }

                // 没有可用连接，尝试创建新的
                if (connections.size() < config.getMaxConnections()) {
                    Http2PooledConnection newConn = createConnection();
                    connections.add(newConn);
                    return newConn;
                }

                // 连接数已满且所有 Stream 都满，进入等待队列
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException(
                            "Timed out waiting for available HTTP/2 stream. " +
                                    "Pool status: " + getStatsInternal() +
                                    ", waiting threads: " + waitingCount.get());
                }

                waitingCount.incrementAndGet();
                log.debug("All streams exhausted, entering wait queue. Waiting threads: {}",
                        waitingCount.get());
                try {
                    streamAvailable.await(remainingNanos, TimeUnit.NANOSECONDS);
                } finally {
                    waitingCount.decrementAndGet();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 通知等待队列有 Stream 可用
     * <p>
     * 当一个 Stream 关闭时调用，唤醒一个等待中的线程。
     * </p>
     */
    private void signalStreamAvailable() {
        lock.lock();
        try {
            streamAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建新的 HTTP/2 TCP 连接
     */
    private Http2PooledConnection createConnection() throws Exception {
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).sync();
        if (!future.isSuccess()) {
            throw new RuntimeException("Failed to connect to " + url.getHost() + ":" + url.getPort(),
                    future.cause());
        }

        Channel channel = future.channel();
        Http2PooledConnection conn = new Http2PooledConnection(channel, config.getMaxStreamsPerConnection());
        log.info("Created new HTTP/2 connection to {}:{}, pool size: {}",
                url.getHost(), url.getPort(), connections.size() + 1);
        return conn;
    }

    /**
     * 创建一个新的 HTTP/2 Stream 子 Channel
     * <p>
     * 通过 Http2StreamChannelBootstrap 创建出站 Stream。
     * 返回的子 Channel 可以直接用于发送/接收 ProxyMessage。
     * Stream 关闭时会自动通知等待队列。
     * </p>
     */
    public Channel openStream(Http2PooledConnection connection) throws Exception {
        Channel parentChannel = connection.getParentChannel();

        // 通过 Http2StreamChannelBootstrap 创建出站 Stream
        Http2StreamChannelBootstrap streamBootstrap =
                new Http2StreamChannelBootstrap(parentChannel);

        streamBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
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
            }
        });

        Channel streamChannel = streamBootstrap.open().sync().getNow();
        connection.incrementStreams();

        // 监听 Stream 关闭事件：减少计数 + 通知等待队列
        streamChannel.closeFuture().addListener(f -> {
            connection.decrementStreams();
            signalStreamAvailable();
            log.debug("HTTP/2 stream closed, active streams: {}, notified waiting threads",
                    connection.getActiveStreamCount());
        });

        log.debug("Opened HTTP/2 stream on connection {}, active streams: {}",
                parentChannel, connection.getActiveStreamCount());
        return streamChannel;
    }

    /**
     * 获取连接池状态信息（内部使用，不加锁）
     */
    private String getStatsInternal() {
        int totalStreams = 0;
        int activeConns = 0;
        for (Http2PooledConnection conn : connections) {
            if (conn.isActive()) {
                activeConns++;
                totalStreams += conn.getActiveStreamCount();
            }
        }
        return String.format("connections=%d/%d, totalStreams=%d",
                activeConns, config.getMaxConnections(), totalStreams);
    }

    /**
     * 获取连接池状态信息
     */
    public String getStats() {
        return "ConnectionPool[" + getStatsInternal() +
                ", waiting=" + waitingCount.get() + "]";
    }

    /**
     * 获取当前等待队列中的线程数
     */
    public int getWaitingCount() {
        return waitingCount.get();
    }

    /**
     * 关闭连接池
     * <p>
     * 关闭顺序：
     * 1. 唤醒所有等待中的线程（让它们收到 IllegalStateException）
     * 2. 关闭所有 HTTP/2 连接（触发所有 Stream 的 closeFuture）
     * 3. 优雅关闭 EventLoopGroup（等待 IO 任务完成）
     * </p>
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing HTTP/2 connection pool, active connections: {}", connections.size());

            // 1. 唤醒所有等待中的线程
            lock.lock();
            try {
                streamAvailable.signalAll();
            } finally {
                lock.unlock();
            }

            // 2. 关闭所有连接
            for (Http2PooledConnection conn : connections) {
                try {
                    conn.close();
                } catch (Exception e) {
                    log.warn("Error closing connection: {}", e.getMessage());
                }
            }
            connections.clear();

            // 3. 优雅关闭 EventLoopGroup，等待最多 5 秒
            try {
                workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for EventLoopGroup shutdown");
            }

            log.info("HTTP/2 connection pool closed");
        }
    }

    /**
     * 连接池是否已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 是否有可用连接（非阻塞检查）
     */
    public boolean hasAvailableConnection() {
        if (closed.get()) {
            return false;
        }
        for (Http2PooledConnection conn : connections) {
            if (conn.isAvailable()) {
                return true;
            }
        }
        return connections.size() < config.getMaxConnections();
    }

    /**
     * 根据 URL 配置创建并初始化 Cipher 实例
     */
    private Cipher createCipher() {
        String cipherName = url.getParameter("cipher", "aes-gcm");
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).getExtension(cipherName);
        String key = url.getParameter("cipherKey", "");
        if (!key.isEmpty()) {
            CipherConfig config = new CipherConfig(key.getBytes());
            cipher.init(config);
        } else {
            cipher.init(new CipherConfig());
        }
        return cipher;
    }
}
