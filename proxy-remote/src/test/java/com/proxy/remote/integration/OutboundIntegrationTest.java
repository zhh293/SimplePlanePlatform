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
import com.proxy.remote.outbound.OutboundSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.*;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Outbound 全链路集成测试
 * <p>
 * 验证完整的 CONNECT → DATA 透传 → DISCONNECT 链路，
 * 确保客户端流量能通过 proxy-remote 正确到达目标并返回响应。
 * </p>
 */
class OutboundIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final long TIMEOUT = 5000;

    private ExchangeServer proxyServer;
    private ExchangeClient proxyClient;
    private ExecutorService bizExecutor;
    private DispatchInvoker dispatchInvoker;
    private EventLoopGroup outboundWorkerGroup;
    private int proxyPort;
    private ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry;

    // 模拟的目标 EchoServer
    private EventLoopGroup echoServerBossGroup;
    private EventLoopGroup echoServerWorkerGroup;
    private Channel echoServerChannel;
    private int echoServerPort;

    @BeforeEach
    void setUp() throws Exception {
        // 启动模拟的 TCP Echo Server
        echoServerPort = findAvailablePort();
        startEchoServer(echoServerPort);

        // 启动 Proxy 服务端
        proxyPort = findAvailablePort();
        ExtensionLoader.resetAll();

        bizExecutor = Executors.newFixedThreadPool(10);
        outboundWorkerGroup = new NioEventLoopGroup(2);
        OutboundConnector connector = new OutboundConnector(outboundWorkerGroup, 3000);
        dispatchInvoker = new DispatchInvoker(bizExecutor, connector, 3000);

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

        // 创建客户端连接到 Proxy
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
        if (echoServerChannel != null) echoServerChannel.close();
        if (echoServerBossGroup != null) echoServerBossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (echoServerWorkerGroup != null) echoServerWorkerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    /**
     * 测试用例 1：CONNECT → DATA → 验证 Echo 回包
     */
    @Test
    void testConnectAndDataForward() throws Exception {
        long streamId = 1;

        // 1. CONNECT 到 echo server
        ProxyMessage connectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host(HOST)
                .port(echoServerPort)
                .streamId(streamId)
                .build();
        CompletableFuture<Response> connectFuture = proxyClient.request(connectMsg, TIMEOUT);
        Response connectResp = connectFuture.get(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(connectResp);
        assertTrue(connectResp.isSuccess(), "CONNECT should succeed");

        // 等待出站连接建立
        Thread.sleep(200);

        // 使用 EmbeddedChannel 捕获 echo 回包
        BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
        EmbeddedChannel embeddedChannel = createPushCapture(streamId, pushQueue);

        // 2. 发送 DATA（发后即忘）
        byte[] payload = "Hello Echo Server!".getBytes();
        ProxyMessage dataMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host(HOST)
                .port(echoServerPort)
                .streamId(streamId)
                .data(payload)
                .build();
        proxyClient.send(dataMsg);

        // echo 回包通过 OutboundSession.writeBack 经 inboundCtx 推回客户端，
        // 客户端经 ExchangeHandler.handlePush() 路由到 streamRegistry 中的 ctx
        byte[] pushed = pushQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(pushed, "Should receive echo data via server push");
        assertArrayEquals(payload, pushed, "Echo data should match");

        embeddedChannel.close();
    }

    /**
     * 测试用例 2：CONNECT 到不存在的地址 → 验证 session 被清理
     */
    @Test
    void testConnectToUnreachableHost() throws Exception {
        long streamId = 2;

        // CONNECT 到一个不存在的端口
        ProxyMessage connectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host(HOST)
                .port(19999) // 没有监听
                .streamId(streamId)
                .build();
        CompletableFuture<Response> future = proxyClient.request(connectMsg, TIMEOUT);
        Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

        // CONNECT 本身立即返回 OK（异步建连）
        // Netty 4.2 下连接失败可能在等待 CONNECT 响应时同步返回；测试重点是失败连接不会留下 session。
        assertFalse(response.isSuccess(), "Unreachable outbound target should fail CONNECT");

        // 等待异步连接失败 → session 被清理
        Thread.sleep(500);

        // 直接验证服务端 session 已被清理（连接失败的副作用）
        assertNull(dispatchInvoker.getSessionManager().get(String.valueOf(streamId)),
                "Session should be cleaned up after outbound connect failure");
    }

    /**
     * 测试用例 3：正常连接后发送 DISCONNECT → 验证 session 清理
     */
    @Test
    void testDisconnectCleansUpSession() throws Exception {
        long streamId = 3;

        // CONNECT
        ProxyMessage connectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host(HOST)
                .port(echoServerPort)
                .streamId(streamId)
                .build();
        proxyClient.request(connectMsg, TIMEOUT).get(TIMEOUT, TimeUnit.MILLISECONDS);
        Thread.sleep(200);

        // DISCONNECT
        ProxyMessage disconnectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DISCONNECT)
                .host(HOST)
                .port(echoServerPort)
                .streamId(streamId)
                .build();
        CompletableFuture<Response> disconnectFuture = proxyClient.request(disconnectMsg, TIMEOUT);
        Response disconnectResp = disconnectFuture.get(TIMEOUT, TimeUnit.MILLISECONDS);
        assertTrue(disconnectResp.isSuccess(), "DISCONNECT should succeed");

        // DISCONNECT 后 session 应已被清理
        Thread.sleep(100);
        assertNull(dispatchInvoker.getSessionManager().get(String.valueOf(streamId)),
                "Session should be cleaned up after DISCONNECT");
    }

    /**
     * 测试用例 4：目标 EchoServer 主动关闭所有连接 → 验证 session 被清理
     */
    @Test
    void testTargetServerClose() throws Exception {
        long streamId = 4;

        // CONNECT
        ProxyMessage connectMsg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host(HOST)
                .port(echoServerPort)
                .streamId(streamId)
                .build();
        proxyClient.request(connectMsg, TIMEOUT).get(TIMEOUT, TimeUnit.MILLISECONDS);
        Thread.sleep(200);

        // 关闭 echo server 的 worker group → 强制断开所有已建立连接
        echoServerWorkerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        Thread.sleep(500);

        // 验证目标关闭后服务端 session 已进入 CLOSED 状态
        OutboundSession session =
                dispatchInvoker.getSessionManager().get(String.valueOf(streamId));
        assertNotNull(session, "Session entry should still exist before next DATA cleanup");
        assertEquals(OutboundSession.SessionState.CLOSED, session.getState(),
                "Session should be CLOSED after target server closes");
    }

    /**
     * 测试用例 5：并发 10 个 Stream 同时 CONNECT → 全部正常
     */
    @Test
    void testConcurrentConnections() throws Exception {
        int concurrency = 10;
        CountDownLatch latch = new CountDownLatch(concurrency);
        List<CompletableFuture<Response>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            long streamId = 100 + i;
            ProxyMessage connectMsg = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host(HOST)
                    .port(echoServerPort)
                    .streamId(streamId)
                    .build();
            CompletableFuture<Response> future = proxyClient.request(connectMsg, TIMEOUT);
            future.whenComplete((r, ex) -> latch.countDown());
            futures.add(future);
        }

        assertTrue(latch.await(TIMEOUT, TimeUnit.MILLISECONDS), "All CONNECTs should complete");

        for (CompletableFuture<Response> f : futures) {
            Response resp = f.get();
            assertTrue(resp.isSuccess(), "Each CONNECT should succeed");
        }

        // 验证 session 数量
        assertEquals(concurrency, dispatchInvoker.getSessionManager().activeCount());
    }

    /**
     * 测试用例 6：shutdown 清理所有 session
     */
    @Test
    void testShutdownCleansAllSessions() throws Exception {
        // 建立多个连接
        for (int i = 0; i < 5; i++) {
            ProxyMessage connectMsg = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host(HOST)
                    .port(echoServerPort)
                    .streamId(200 + i)
                    .build();
            proxyClient.request(connectMsg, TIMEOUT).get(TIMEOUT, TimeUnit.MILLISECONDS);
        }
        Thread.sleep(200);
        assertEquals(5, dispatchInvoker.getSessionManager().activeCount());

        // shutdown
        dispatchInvoker.shutdown();
        assertEquals(0, dispatchInvoker.getSessionManager().activeCount());
    }

    // ======================== Helper Methods ========================

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

    private void startEchoServer(int port) throws InterruptedException {
        echoServerBossGroup = new NioEventLoopGroup(1);
        echoServerWorkerGroup = new NioEventLoopGroup(2);

        ServerBootstrap b = new ServerBootstrap();
        b.group(echoServerBossGroup, echoServerWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                // Echo: 原样返回
                                ctx.writeAndFlush(msg.retain());
                            }
                        });
                    }
                });

        echoServerChannel = b.bind(HOST, port).sync().channel();
    }

    private int findAvailablePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Cannot find available port", e);
        }
    }
}
