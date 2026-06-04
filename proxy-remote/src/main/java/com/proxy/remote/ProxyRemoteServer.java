package com.proxy.remote;

import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Filter;
import com.proxy.common.filter.FilterChainBuilder;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.remote.config.RemoteConfig;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 远程服务端启动入口
 * <p>
 * 串联配置加载 → OutboundConnector 创建 → Filter 链组装 → Exchanger.bind() 完整启动流程。
 * </p>
 */
public class ProxyRemoteServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyRemoteServer.class);

    private ExchangeServer exchangeServer;
    private ExecutorService bizExecutor;
    private DispatchInvoker dispatchInvoker;
    private EventLoopGroup outboundWorkerGroup;

    /**
     * 启动服务端
     */
    public void start() {
        printBanner();

        // 1. 加载配置
        RemoteConfig config = new RemoteConfig();
        URL url = config.loadURL();

        // 2. 创建业务线程池
        int bizThreads = url.getParameter("bizThreads", 200);
        bizExecutor = Executors.newFixedThreadPool(bizThreads, r -> {
            Thread t = new Thread(r, "proxy-biz-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // 3. 创建 Outbound 组件
        int connectTimeoutMs = url.getParameter("outbound.connectTimeoutMs", 5000);
        long activeWaitTimeoutMs = url.getParameter("outbound.activeWaitTimeoutMs", 5000L);
        outboundWorkerGroup = new NioEventLoopGroup(0, r -> {
            Thread t = new Thread(r, "outbound-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        OutboundConnector connector = new OutboundConnector(outboundWorkerGroup, connectTimeoutMs);

        // 4. 创建 DispatchInvoker（Filter 链末端）
        dispatchInvoker = new DispatchInvoker(bizExecutor, connector, activeWaitTimeoutMs);

        // 5. 通过 SPI 加载 Filter 并构建链
        Invoker invokerChain = buildFilterChain(dispatchInvoker);

        // 6. 通过 SPI 加载 Exchanger，bind 启动服务
        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        exchangeServer = exchanger.bind(url, invokerChain);

        log.info("=== ProxyRemoteServer started successfully ===");
        log.info("Listening on {}:{}, bizThreads={}, connectTimeout={}ms",
                url.getHost(), url.getPort(), bizThreads, connectTimeoutMs);
    }

    /**
     * 关闭服务端
     */
    public void shutdown() {
        log.info("Shutting down ProxyRemoteServer...");

        // 关闭所有出站会话
        if (dispatchInvoker != null) {
            dispatchInvoker.shutdown();
        }

        if (exchangeServer != null) {
            exchangeServer.close();
        }

        // 关闭 Outbound Worker 线程组
        if (outboundWorkerGroup != null) {
            outboundWorkerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }

        if (bizExecutor != null) {
            bizExecutor.shutdown();
            try {
                if (!bizExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    bizExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                bizExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("ProxyRemoteServer shut down successfully");
    }

    /**
     * 构建 Filter 链
     */
    private Invoker buildFilterChain(Invoker dispatchInvoker) {
        try {
            FilterChainBuilder chainBuilder = ExtensionLoader.getLoader(FilterChainBuilder.class)
                    .getDefaultExtension();
            List<Filter> filters = ExtensionLoader.getLoader(Filter.class)
                    .getActivateExtensions("server");

            if (filters.isEmpty()) {
                log.info("No server-side filters activated, using DispatchInvoker directly");
                return dispatchInvoker;
            }

            log.info("Building server filter chain with {} filters: {}", filters.size(), filters);
            return chainBuilder.build(dispatchInvoker, filters);
        } catch (Exception e) {
            log.warn("Failed to build filter chain, using DispatchInvoker directly", e);
            return dispatchInvoker;
        }
    }

    private void printBanner() {
        log.info("====================================");
        log.info("      Proxy Remote Server");
        log.info("      Netty-Proxy v1.0.0");
        log.info("====================================");
    }

    /**
     * 主启动入口
     */
    public static void main(String[] args) {
        ProxyRemoteServer server = new ProxyRemoteServer();

        // 注册 ShutdownHook 优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Received shutdown signal...");
            server.shutdown();
        }, "shutdown-hook"));

        try {
            server.start();

            // 阻塞主线程，等待关闭信号
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("ProxyRemoteServer failed to start", e);
            System.exit(1);
        }
    }
}
