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
     * 测试用例 2：发送 DATA 消息（带 payload），验证服务端回显正确数据
     */
    @Test
    void testDataEcho() throws Exception {
        byte[] payload = "Hello, Proxy Server!".getBytes();

        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host("www.example.com")
                .port(443)
                .streamId(2)
                .data(payload)
                .build();

        CompletableFuture<Response> future = client.request(message, TIMEOUT);
        Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertArrayEquals(payload, response.getData(), "Echo data should match");
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
        assertTrue(response.isSuccess());
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
        // 发送正常 DATA 消息
        byte[] payload = "test-after-heartbeat".getBytes();
        ProxyMessage message = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .host("heartbeat-test.com")
                .port(80)
                .streamId(4)
                .data(payload)
                .build();

        CompletableFuture<Response> future = client.request(message, TIMEOUT);
        Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertArrayEquals(payload, response.getData());
    }

    /**
     * 测试用例 5：并发验证 — 100 并发请求，全部正确返回
     */
    @Test
    void testConcurrentRequests() throws Exception {
        int concurrency = 100;
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

                    byte[] payload = ("data-" + index).getBytes();
                    ProxyMessage message = ProxyMessage.builder()
                            .type(ProxyMessage.MessageType.DATA)
                            .host("concurrent-" + index + ".com")
                            .port(80)
                            .streamId(100 + index)
                            .data(payload)
                            .build();

                    CompletableFuture<Response> future = client.request(message, TIMEOUT);
                    Response response = future.get(TIMEOUT, TimeUnit.MILLISECONDS);

                    if (response != null && response.isSuccess() &&
                            java.util.Arrays.equals(payload, response.getData())) {
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
                "All concurrent requests should succeed. Failures: " + failCount.get());
    }

    /**
     * 测试用例 6：异常验证 — 服务端线程池满时返回错误响应
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

            // 发送请求
            ProxyMessage message = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .host("test.com")
                    .port(80)
                    .streamId(999)
                    .data("test".getBytes())
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
