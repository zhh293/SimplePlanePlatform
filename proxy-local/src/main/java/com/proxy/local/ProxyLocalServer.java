package com.proxy.local;

import com.proxy.cluster.ClientInvoker;
import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Filter;
import com.proxy.common.filter.FilterChainBuilder;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.exchange.header.DefaultFuture;
import com.proxy.local.config.ProxyConfig;
import com.proxy.local.handler.HttpConnectHandler;
import com.proxy.local.handler.ProtocolDetector;
import com.proxy.local.handler.RelayHandler;
import com.proxy.local.handler.Socks5ConnectHandler;
import com.proxy.local.handler.Socks5InitHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地代理服务启动入口
 * <p>
 * 职责：
 * <ul>
 *   <li>加载配置</li>
 *   <li>组装调用链：Exchanger → ExchangeClient → ClientInvoker → FilterChain → ClusterInvoker</li>
 *   <li>启动本地 Netty 服务，监听 SOCKS5/HTTP CONNECT 代理请求</li>
 * </ul>
 * </p>
 */
public class ProxyLocalServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyLocalServer.class);

    private final ProxyConfig config;
    private final Invoker clusterInvoker;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ProxyLocalServer(ProxyConfig config) {
        this.config = config;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        // 组装调用链
        this.clusterInvoker = buildInvokerChain();
    }

    /**
     * 组装完整调用链
     * <p>
     * 流程：
     * 1. 通过 Exchanger SPI 为每个远程节点创建 ExchangeClient
     * 2. 包装为 ClientInvoker
     * 3. 通过 FilterChainBuilder SPI 构建 Filter 链
     * 4. 通过 ClusterInvoker SPI 包装集群容错
     * </p>
     */
    private Invoker buildInvokerChain() {
        // 1. 加载 SPI 组件
        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        FilterChainBuilder chainBuilder = ExtensionLoader.getLoader(FilterChainBuilder.class).getDefaultExtension();
        List<Filter> filters = ExtensionLoader.getLoader(Filter.class).getActivateExtensions("client");
        ClusterInvoker cluster = ExtensionLoader.getLoader(ClusterInvoker.class)
                .getExtension(config.getCluster());
        LoadBalance loadBalance = ExtensionLoader.getLoader(LoadBalance.class)
                .getExtension(config.getLoadBalance());

        log.info("SPI loaded - Cluster: {}, LoadBalance: {}, Filters: {}",
                config.getCluster(), config.getLoadBalance(), filters.size());

        // 2. 为每个远程节点创建 Invoker（每个节点可创建多条连接）
        List<Invoker> invokers = new ArrayList<>();
        for (ProxyConfig.RemoteServer server : config.getRemoteServers()) {
            for (int i = 0; i < config.getConnectionsPerNode(); i++) {
                URL url = new URL("proxy", server.getHost(), server.getPort());
                url.addParameter("ssl", String.valueOf(server.isSsl()));
                url.addParameter("cipher", server.getCipher());
                url.addParameter("cipherKey", server.getCipherKey());

                // Exchanger.connect() → 内部创建 Transporter 连接 → 包装为 ExchangeClient
                ExchangeClient exchangeClient = exchanger.connect(url);

                // ClientInvoker 适配 ExchangeClient → Invoker 接口
                ClientInvoker clientInvoker = new ClientInvoker(exchangeClient, config.getTimeoutMs());

                // FilterChain 包装
                Invoker chainedInvoker = chainBuilder.build(clientInvoker, filters);
                invokers.add(chainedInvoker);

                log.info("Created invoker for {} (connection {})", server, i + 1);
            }
        }

        // 3. ClusterInvoker 包装所有 Invoker
        cluster.setInvokers(invokers);
        cluster.setLoadBalance(loadBalance);

        log.info("Invoker chain assembled: {} invokers, cluster={}, loadBalance={}",
                invokers.size(), config.getCluster(), config.getLoadBalance());

        return cluster;
    }

    /**
     * 启动本地代理服务
     */
    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 256)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // 协议检测器：根据首字节判断 SOCKS5 还是 HTTP CONNECT
                        ch.pipeline().addLast("protocol-detector",
                                new ProtocolDetector(clusterInvoker, config.isHttpProxyEnabled()));
                    }
                });

        serverChannel = bootstrap.bind(config.getLocalPort()).sync().channel();
        log.info("=== Proxy Local Server started on port {} ===", config.getLocalPort());
        log.info("  SOCKS5 proxy: localhost:{}", config.getLocalPort());
        if (config.isHttpProxyEnabled()) {
            log.info("  HTTP  proxy:  localhost:{}", config.getLocalPort());
        }
    }

    /**
     * 阻塞等待服务关闭
     */
    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    /**
     * 优雅关闭
     * <p>
     * 关闭顺序（从外到内，先停止接收请求，再清理内部资源）：
     * 1. 关闭 Server Channel（停止接收新连接）
     * 2. 关闭 Server 的 EventLoopGroup（清理已有连接）
     * 3. 关闭 DefaultFuture 时间轮（停止超时调度，唤醒所有 pending Future）
     * 4. 关闭 ClusterInvoker 中的所有 ExchangeClient（底层连接池+EventLoop 一并释放）
     * </p>
     */
    public void shutdown() {
        log.info("Shutting down Proxy Local Server...");

        // 1. 关闭 Server Channel，停止接收新连接
        if (serverChannel != null) {
            serverChannel.close();
        }

        // 2. 关闭 Server 的 boss/worker EventLoopGroup
        try {
            bossGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during EventLoopGroup shutdown");
        }

        // 3. 关闭 DefaultFuture 时间轮，唤醒所有 pending Future
        DefaultFuture.shutdown();

        // 4. 关闭 ClusterInvoker 中的 Invoker（底层会关闭 ExchangeClient → ConnectionPool）
        if (clusterInvoker instanceof ClusterInvoker) {
            List<Invoker> invokers = ((ClusterInvoker) clusterInvoker).getAvailableInvokers();
            for (Invoker inv : invokers) {
                try {
                    if (inv instanceof AutoCloseable) {
                        ((AutoCloseable) inv).close();
                    }
                } catch (Exception e) {
                    log.warn("Error closing invoker: {}", e.getMessage());
                }
            }
        }

        log.info("Proxy Local Server stopped.");
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        // 加载配置
        ProxyConfig config;
        if (args.length > 0) {
            config = ProxyConfig.load(args[0]);
            log.info("Config loaded from: {}", args[0]);
        } else {
            config = ProxyConfig.loadFromClasspath("proxy.yml");
            log.info("Config loaded from classpath: proxy.yml");
        }

        // 创建并启动服务
        ProxyLocalServer server = new ProxyLocalServer(config);

        // 注册 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown-hook"));

        try {
            server.start();
            server.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Server interrupted", e);
        } catch (Exception e) {
            log.error("Server failed to start", e);
            server.shutdown();
        }
    }
}
