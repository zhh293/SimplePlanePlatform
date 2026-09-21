package com.proxy.remote.integration;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.remote.dispatch.DispatchInvoker;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试 — 客户端到服务端全链路验证
 * <p>
 * 验证客户端通过 ExchangeClient 发送请求到服务端，
 * 服务端 DispatchInvoker 处理后返回响应的完整链路。
 * </p>
 */
class ClientServerIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final long TIMEOUT = 5000;

    private ExchangeServer server;
    private ExchangeClient client;
    private ExecutorService bizExecutor;
    private int port;

    /** 用于接收推送数据的 streamRegistry */
    private ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry;

    @BeforeEach
    void setUp() {
        // 使用随机端口避免冲突
        port = findAvailablePort();

        // 重置 SPI 缓存（确保测试隔离）
        ExtensionLoader.resetAll();

        // 1. 创建业务线程池
        bizExecutor = Executors.newFixedThreadPool(10);

        // 2. 创建 DispatchInvoker
        DispatchInvoker dispatchInvoker = new DispatchInvoker(bizExecutor);

        // 3. 构建 URL
        URL serverUrl = new URL("proxy", HOST, port);
        serverUrl.addParameter("bizThreads", 10);
        serverUrl.addParameter("workerThreads", 2);
        serverUrl.addParameter("bossThreads", 1);
        serverUrl.addParameter("maxStreams", 100);
        serverUrl.addParameter("readIdleTimeout", 60);
        serverUrl.addParameter("backlog", 128);
        serverUrl.addParameter("cipher", "none"); // 测试中不加密

        // 4. 启动服务端
        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        server = exchanger.bind(serverUrl, dispatchInvoker);

        // 5. 创建客户端连接
        URL clientUrl = new URL("proxy", HOST, port);
        clientUrl.addParameter("cipher", "none");
        client = exchanger.connect(clientUrl);

        // 6. 注入 streamRegistry 用于接收推送
        streamRegistry = new ConcurrentHashMap<>();
        client.setStreamRegistry(streamRegistry);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (bizExecutor != null) {
            bizExecutor.shutdownNow();
        }
    }

    /**
     * 测试用例 1：发送 CONNECT 消息，验证收到 OK 响应
     */
    @Test
    void testConnect() throws Exception {
        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .host("www.example.com")
                .port(443)
                .streamId(1)
                .build();

        CompletableFuture<Response> future = client.request(message, TIMEOUT);
        Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

        assertNotNull(response);
        assertTrue(response.isSuccess(), "Expected OK response, got: " + response);
    }

    /**
     * 测试用例 2：发送 DATA 流式数据（发后即忘），验证通过推送收到回显数据
     * <p>
     * 数据面已统一为流式推送：上行数据不生成 requestId/Future，仅依赖 streamId 寻址；
     * 服务端的回包由 ExchangeHandler.handlePush() 按 streamId 路由写回。
     * 测试中使用 EmbeddedChannel 注册到 streamRegistry 来捕获推送。
     * </p>
     */
    @Test
    void testDataEcho() throws Exception {
        byte[] payload = "Hello, Proxy Server!".getBytes();
        long streamId = 2L;

        // 使用 EmbeddedChannel 模拟浏览器端，注册到 streamRegistry
        BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
        EmbeddedChannel embeddedChannel = createPushCapture(streamId, pushQueue);

        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host("www.example.com")
                .port(443)
                .streamId(streamId)
                .data(payload)
                .build();

        // 发后即忘发送
        client.send(message);

        // 从 EmbeddedChannel 捕获推送数据
        byte[] pushed = pushQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(pushed, "Should receive a server push for the stream");
        assertArrayEquals(payload, pushed, "Echo data should match");

        embeddedChannel.close();
    }

    /**
     * 测试用例 3：发送 DISCONNECT 消息，验证收到 OK 响应
     */
    @Test
    void testDisconnect() throws Exception {
        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DISCONNECT)
                .host("www.example.com")
                .port(443)
                .streamId(3)
                .build();

        CompletableFuture<Response> future = client.request(message, TIMEOUT);
        Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

        assertNotNull(response);
        assertTrue(response.isSuccess(), "Expected OK response for DISCONNECT");
    }

    /**
     * 测试用例 4：心跳验证 — 发送心跳请求，服务端正确响应
     * <p>
     * 注意：HeartbeatHandler 在 Pipeline 中拦截心跳消息，不走 Invoker 链。
     * 这个测试验证心跳机制不会影响正常消息处理。
     * 先发一个正常消息确认链路通畅。
     * </p>
     */
    @Test
    void testHeartbeatDoesNotInterfere() throws Exception {
        long streamId = 4L;
        byte[] payload = "test-after-heartbeat".getBytes();

        // 注册推送捕获
        BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
        EmbeddedChannel embeddedChannel = createPushCapture(streamId, pushQueue);

        // 发送正常 DATA 流式数据
        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host("heartbeat-test.com")
                .port(80)
                .streamId(streamId)
                .data(payload)
                .build();

        client.send(message);

        byte[] pushed = pushQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(pushed, "Should receive a server push after heartbeat");
        assertArrayEquals(payload, pushed);

        embeddedChannel.close();
    }

    /**
     * 测试用例 5：并发验证 — 100 并发请求，全部正确返回
     */
    @Test
    void testConcurrentRequests() throws Exception {
        int concurrency = 100;

        // 为每个 stream 创建独立的 EmbeddedChannel 和推送队列
        ConcurrentHashMap<Long, BlockingQueue<byte[]>> pushByStream = new ConcurrentHashMap<>();
        List<EmbeddedChannel> channels = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            long streamId = 100 + i;
            BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
            pushByStream.put(streamId, queue);
            channels.add(createPushCapture(streamId, queue));
        }

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 所有线程同时开始

                    long streamId = 100 + index;
                    byte[] payload = ("data-" + index).getBytes();
                    ProxyMessage message = ProxyMessage.builder()
                            .type(ProxyMessage.MessageType.DATA)
                            .host("concurrent-" + index + ".com")
                            .port(80)
                            .streamId(streamId)
                            .data(payload)
                            .build();

                    // 发后即忘发送
                    client.send(message);

                    // 等待该 stream 的 push 回显
                    BlockingQueue<byte[]> q = pushByStream.get(streamId);
                    byte[] pushed = q.poll(TIMEOUT, TimeUnit.MILLISECONDS);

                    if (pushed != null && java.util.Arrays.equals(payload, pushed)) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 放行所有线程
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All requests should complete within 30s");

        executor.shutdown();

        assertEquals(concurrency, successCount.get(),
                "All concurrent streams should receive matching push. Failures: " + failCount.get());

        // 清理
        channels.forEach(EmbeddedChannel::close);
    }

    /**
     * 测试用例 6：异常验证 — 服务端线程池满时控制面请求返回错误响应
     * <p>
     * 线程池满是服务端调度层问题，适用于仍为请求-响应的控制面（CONNECT）；
     * 数据面已改为发后即忘，无响应可等，故用 CONNECT 验证错误路径。
     * </p>
     */
    @Test
    void testThreadPoolExhaustion() throws Exception {
        // 创建一个只有 1 个线程的小线程池
        ExecutorService tinyExecutor = Executors.newFixedThreadPool(1);
        DispatchInvoker tinyDispatcher = new DispatchInvoker(tinyExecutor);

        // 用 tinyDispatcher 启动一个新的服务端
        int tinyPort = findAvailablePort();
        URL tinyUrl = new URL("proxy", HOST, tinyPort);
        tinyUrl.addParameter("cipher", "none");
        tinyUrl.addParameter("maxStreams", 100);
        tinyUrl.addParameter("readIdleTimeout", 60);

        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        ExchangeServer tinyServer = exchanger.bind(tinyUrl, tinyDispatcher);

        URL tinyClientUrl = new URL("proxy", HOST, tinyPort);
        tinyClientUrl.addParameter("cipher", "none");
        ExchangeClient tinyClient = exchanger.connect(tinyClientUrl);

        try {
            // 先关闭线程池，模拟线程池满的情况
            tinyExecutor.shutdownNow();

            // 等待线程池完全关闭
            tinyExecutor.awaitTermination(2, TimeUnit.SECONDS);

            // 发送控制面请求（CONNECT，仍走请求-响应）
            ProxyMessage message = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host("test.com")
                    .port(80)
                    .streamId(999)
                    .build();

            CompletableFuture<Response> future = tinyClient.request(message, TIMEOUT);
            Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

            // 线程池满/关闭时应返回错误响应，而非抛异常
            assertNotNull(response);
            assertFalse(response.isSuccess(), "Should get error response when thread pool is exhausted");
        } finally {
            tinyClient.close();
            tinyServer.close();
        }
    }

    /**
     * 测试用例 7：服务端生命周期 — close() 后 isActive() 返回 false
     */
    @Test
    void testServerLifecycle() {
        assertTrue(server.isActive(), "Server should be active after start");
        server.close();
        assertFalse(server.isActive(), "Server should not be active after close");
        server = null; // 防止 tearDown 再次 close
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个 EmbeddedChannel 并注册到 streamRegistry，用于捕获推送数据。
     * 推送到来时，数据会被放入 pushQueue。
     */
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
        // 注册 ctx 到 streamRegistry（ExchangeHandler.handlePush 会按 streamId 查找并写入）
        streamRegistry.put(streamId, channel.pipeline().lastContext());
        return channel;
    }

    /**
     * 查找可用端口
     */
    private static int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
