package com.proxy.remote.integration;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实际网络测试 — 通过代理连接百度，验证完整的 TCP 隧道能力
 * <p>
 * 流程：客户端 → proxy-remote → www.baidu.com:80
 * 发送 HTTP GET 请求，验证能收到百度的 HTML 响应。
 * </p>
 */
class BaiduProxyTest {

    private static final String HOST = "127.0.0.1";
    private static final long TIMEOUT = 10000;

    private ExchangeServer proxyServer;
    private ExchangeClient proxyClient;
    private ExecutorService bizExecutor;
    private DispatchInvoker dispatchInvoker;
    private EventLoopGroup outboundWorkerGroup;
    private int proxyPort;
    private ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry;

    @BeforeEach
    void setUp() throws Exception {
        proxyPort = findAvailablePort();
        ExtensionLoader.resetAll();

        bizExecutor = Executors.newFixedThreadPool(10);
        outboundWorkerGroup = new NioEventLoopGroup(2);
        OutboundConnector connector = new OutboundConnector(outboundWorkerGroup, 5000);
        dispatchInvoker = new DispatchInvoker(bizExecutor, connector, 5000);

        URL serverUrl = new URL("proxy", HOST, proxyPort);
        serverUrl.addParameter("bizThreads", 10);
        serverUrl.addParameter("workerThreads", 2);
        serverUrl.addParameter("bossThreads", 1);
        serverUrl.addParameter("maxStreams", 100);
        serverUrl.addParameter("readIdleTimeout", 60);
        serverUrl.addParameter("backlog", 128);
        serverUrl.addParameter("cipher", "none");

        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        proxyServer = exchanger.bind(serverUrl, dispatchInvoker);

        URL clientUrl = new URL("proxy", HOST, proxyPort);
        clientUrl.addParameter("cipher", "none");
        proxyClient = exchanger.connect(clientUrl);

        // 注入 streamRegistry 用于接收推送
        streamRegistry = new ConcurrentHashMap<>();
        proxyClient.setStreamRegistry(streamRegistry);
    }

    @AfterEach
    void tearDown() {
        if (proxyClient != null) proxyClient.close();
        if (proxyServer != null) proxyServer.close();
        if (dispatchInvoker != null) dispatchInvoker.shutdown();
        if (outboundWorkerGroup != null) outboundWorkerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (bizExecutor != null) bizExecutor.shutdownNow();
    }

    /**
     * 通过代理隧道向 www.baidu.com:80 发送 HTTP GET 请求
     * 验证能收到包含 "baidu" 的 HTML 响应
     */
    @Test
    void testProxyToBaidu() throws Exception {
        long streamId = 1;

        // 1. CONNECT 到百度 HTTP 端口
        ProxyMessage connectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host("www.baidu.com")
                .port(80)
                .streamId(streamId)
                .build();
        CompletableFuture<Response> connectFuture = proxyClient.request(connectMsg, TIMEOUT);
        Response connectResp = connectFuture.get(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(connectResp);
        assertTrue(connectResp.isSuccess(), "CONNECT to baidu should succeed");

        // 等待出站连接建立
        Thread.sleep(500);

        // 2. 发送 HTTP GET 请求（使用 EmbeddedChannel 捕获推送）
        String httpRequest = "GET / HTTP/1.1\r\n" +
                "Host: www.baidu.com\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
        EmbeddedChannel embeddedChannel = createPushCapture(streamId, pushQueue);

        ProxyMessage dataMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host("www.baidu.com")
                .port(80)
                .streamId(streamId)
                .data(httpRequest.getBytes(StandardCharsets.UTF_8))
                .build();
        // 发后即忘发送
        proxyClient.send(dataMsg);

        // 3. 等待百度响应通过 ExchangeHandler.handlePush() 路由写回
        byte[] pushed = pushQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(pushed, "Should receive baidu HTTP response via server push");
        String responseText = new String(pushed, StandardCharsets.UTF_8);
        assertTrue(responseText.contains("HTTP") || responseText.toLowerCase().contains("baidu"),
                "Response should look like an HTTP reply from baidu, got: "
                        + responseText.substring(0, Math.min(80, responseText.length())));

        // 4. 验证 session 仍然存活（连接成功）
        assertEquals(1, dispatchInvoker.getSessionManager().activeCount(),
                "Session should still be active after successful connection");

        System.out.println("=== Baidu proxy test passed! ===");
        System.out.println("Successfully connected to www.baidu.com:80 through proxy");
        System.out.println("HTTP request forwarded, session active.");

        // 5. DISCONNECT
        ProxyMessage disconnectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DISCONNECT)
                .host("www.baidu.com")
                .port(80)
                .streamId(streamId)
                .build();
        CompletableFuture<Response> disconnectFuture = proxyClient.request(disconnectMsg, TIMEOUT);
        Response disconnectResp = disconnectFuture.get(TIMEOUT, TimeUnit.MILLISECONDS);
        assertTrue(disconnectResp.isSuccess(), "DISCONNECT should succeed");

        assertEquals(0, dispatchInvoker.getSessionManager().activeCount(),
                "Session should be cleaned after DISCONNECT");

        embeddedChannel.close();
    }

    // ==================== 辅助方法 ====================

    private EmbeddedChannel createPushCapture(long streamId, BlockingQueue<byte[]> pushQueue) {
        // ExchangeHandler.handlePush 使用注册 context 的 writeAndFlush，属于 outbound 事件；
        // 捕获器必须位于该 context 的前面，不能通过 inbound channelRead 捕获。
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) msg;
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    buf.release();
                    pushQueue.offer(data);
                    promise.setSuccess();
                } else {
                    ctx.write(msg, promise);
                }
            }
        }, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
            }
        });
        streamRegistry.put(streamId, channel.pipeline().lastContext());
        return channel;
    }

    private int findAvailablePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Cannot find available port", e);
        }
    }
}
