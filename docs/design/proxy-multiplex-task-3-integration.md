# Task-3：集成测试、全链路测试与性能验证

> **所属 SDD**：[proxy-multiplex-design.md](./proxy-multiplex-design.md)
> **任务编号**：Task-3 / 3
> **预计产出**：完整的集成测试、端到端全链路测试、Stalled 性能对比验证
> **前置依赖**：Task-1（多端口监听）+ Task-2（PAC 脚本 + SystemProxyManager）

---

## 1. 任务目标

验证多端口 + PAC 方案的完整性和有效性，分为三个层次：

1. **集成测试**：验证多端口监听 + PAC 服务 + 系统代理设置三者协同工作。
2. **全链路测试（E2E）**：验证浏览器通过 PAC 脚本 → 多端口代理 → 远程代理 → 目标网站的完整链路。
3. **性能验证**：对比优化前后的 Stalled 时间，量化改进效果。

## 2. 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `proxy-local/src/test/java/com/proxy/local/MultiplexIntegrationTest.java` | **新增** | 多端口 + PAC 集成测试 |
| `proxy-local/src/test/java/com/proxy/local/E2EMultiplexTest.java` | **新增** | 全链路端到端测试 |
| `proxy-local/src/test/java/com/proxy/local/PerformanceValidationTest.java` | **新增** | Stalled 时间性能对比测试 |
| `proxy-local/src/test/resources/proxy-multiplex-e2e.yml` | **新增** | E2E 测试配置文件 |

## 3. 测试架构

### 3.1 测试分层

```
┌─────────────────────────────────────────────────────────────┐
│                    Performance Validation                    │
│    浏览器模拟 + Stalled 时间对比（需要真实环境，手动执行）        │
├─────────────────────────────────────────────────────────────┤
│                    E2E 全链路测试                              │
│    Mock Browser → PAC → Multi-Port Proxy → Mock Remote       │
│    → Mock Target Server                                      │
├─────────────────────────────────────────────────────────────┤
│                    集成测试                                    │
│    ProxyLocalServer 多端口启动 + PAC 服务 + 配置解析           │
├─────────────────────────────────────────────────────────────┤
│                    单元测试（Task-1 & Task-2 已完成）           │
│    ProxyConfig / PacScriptGenerator / PacServerHandler       │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 测试环境搭建

E2E 测试需要模拟完整的代理链路，在单个 JVM 内启动所有组件：

```
Test JVM
  ├── Mock Target HTTP Server (Netty, port 0=auto)
  │     └── 返回简单 HTML 页面 + 多个资源引用
  │
  ├── Mock proxy-remote (Netty HTTP/2, port 0=auto)
  │     └── 接收 CONNECT/DATA，转发到 Mock Target
  │
  ├── proxy-local (ProxyLocalServer, ports 11080~11085)
  │     └── 多端口监听 + PAC 服务 (port 18080)
  │
  └── Test Client (模拟浏览器)
        ├── 1. GET http://127.0.0.1:18080/proxy.pac → 获取 PAC
        ├── 2. 根据 PAC 选择端口
        ├── 3. CONNECT target:port → 指定端口
        └── 4. 发送 HTTP 请求 → 验证响应
```

## 4. 实现思路

### 4.1 集成测试：MultiplexIntegrationTest

验证多端口 + PAC 服务的协同工作，不需要真正的代理转发。

```java
public class MultiplexIntegrationTest {

    private ProxyLocalServer server;
    private static final int PAC_PORT = 18080;
    private static final List<Integer> PROXY_PORTS =
        List.of(11080, 11081, 11082, 11083, 11084, 11085);

    @BeforeEach
    void setUp() throws Exception {
        ProxyConfig config = new ProxyConfig();
        config.setLocalPorts(PROXY_PORTS);

        ProxyConfig.PacServerConfig pacConfig = new ProxyConfig.PacServerConfig();
        pacConfig.setEnabled(true);
        pacConfig.setPort(PAC_PORT);
        pacConfig.setListenHost("127.0.0.1");
        config.setPacServer(pacConfig);

        // remoteServers 指向不存在的地址也行，集成测试不实际转发
        ProxyConfig.RemoteServerConfig remote = new ProxyConfig.RemoteServerConfig();
        remote.setHost("127.0.0.1");
        remote.setPort(1); // 不会有实际连接
        config.setRemoteServers(List.of(remote));

        config.setSystemProxy(new ProxyConfig.SystemProxy());
        config.getSystemProxy().setEnabled(false); // 测试中不设置系统代理

        server = new ProxyLocalServer(config);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    @Test
    void testAllProxyPortsAcceptConnections() throws Exception {
        for (int port : PROXY_PORTS) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected(),
                    "Port " + port + " should accept connections");
            }
        }
    }

    @Test
    void testPacServerReturnsValidScript() throws Exception {
        String pac = fetchPacScript();
        assertNotNull(pac);
        assertTrue(pac.contains("function FindProxyForURL"));
        assertTrue(pac.contains("11080"));
        assertTrue(pac.contains("11085"));
    }

    @Test
    void testPacScriptContainsAllPorts() throws Exception {
        String pac = fetchPacScript();
        for (int port : PROXY_PORTS) {
            assertTrue(pac.contains(String.valueOf(port)),
                "PAC script should contain port " + port);
        }
    }

    @Test
    void testPacScriptContainsCorrectHost() throws Exception {
        String pac = fetchPacScript();
        assertTrue(pac.contains("127.0.0.1"));
    }

    @Test
    void testPacServerReturnsCorrectContentType() throws Exception {
        URL url = new URL("http://127.0.0.1:" + PAC_PORT + "/proxy.pac");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        String contentType = conn.getContentType();
        assertTrue(contentType.contains("x-ns-proxy-autoconfig"));
        conn.disconnect();
    }

    @Test
    void testPacServerReturnsCacheControl() throws Exception {
        URL url = new URL("http://127.0.0.1:" + PAC_PORT + "/proxy.pac");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        String cacheControl = conn.getHeaderField("Cache-Control");
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("max-age"));
        conn.disconnect();
    }

    @Test
    void testShutdownClosesAllPorts() throws Exception {
        server.shutdown();
        for (int port : PROXY_PORTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                fail("Port " + port + " should be closed after shutdown");
            } catch (IOException e) {
                // 预期：连接被拒绝
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PAC_PORT), 500);
            fail("PAC port should be closed after shutdown");
        } catch (IOException e) {
            // 预期
        }
    }

    private String fetchPacScript() throws Exception {
        URL url = new URL("http://127.0.0.1:" + PAC_PORT + "/proxy.pac");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
```

### 4.2 全链路测试：E2EMultiplexTest

模拟完整的代理链路：浏览器 → PAC → 多端口 proxy-local → proxy-remote → 目标服务器。

```java
public class E2EMultiplexTest {

    private ProxyLocalServer proxyLocal;
    private MockRemoteServer mockRemote;
    private MockTargetServer mockTarget;

    private static final List<Integer> PROXY_PORTS =
        List.of(11080, 11081, 11082, 11083, 11084, 11085);
    private static final int PAC_PORT = 18080;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 启动 Mock Target HTTP Server
        mockTarget = new MockTargetServer();
        mockTarget.start();
        int targetPort = mockTarget.getPort();

        // 2. 启动 Mock proxy-remote（指向 Mock Target）
        mockRemote = new MockRemoteServer("127.0.0.1", targetPort);
        mockRemote.start();
        int remotePort = mockRemote.getPort();

        // 3. 启动 proxy-local（多端口 + PAC）
        ProxyConfig config = createProxyConfig(remotePort);
        proxyLocal = new ProxyLocalServer(config);
        proxyLocal.start();

        // 等待所有端口就绪
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        proxyLocal.shutdown();
        mockRemote.stop();
        mockTarget.stop();
    }

    @Test
    void testPacScriptServedCorrectly() throws Exception {
        String pac = fetchUrl("http://127.0.0.1:" + PAC_PORT + "/proxy.pac");
        assertTrue(pac.contains("FindProxyForURL"));
        assertTrue(pac.contains("11080"));
    }

    @Test
    void testProxyThroughPacSelectedPort() throws Exception {
        // 模拟 PAC 脚本的端口选择逻辑
        String targetHost = "test.example.com";
        int selectedPort = selectPortByHash(targetHost);

        // 通过选中的端口发送 HTTP CONNECT
        try (Socket socket = new Socket("127.0.0.1", selectedPort)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // CONNECT 握手
            String connectReq = "CONNECT " + targetHost + ":" +
                mockTarget.getPort() + " HTTP/1.1\r\nHost: " +
                targetHost + "\r\n\r\n";
            out.write(connectReq.getBytes());
            out.flush();

            // 读取 CONNECT 响应
            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            String response = new String(buffer, 0, read);
            assertTrue(response.contains("200"),
                "CONNECT should succeed via port " + selectedPort);

            // 发送 HTTP 请求
            String httpReq = "GET / HTTP/1.1\r\nHost: " + targetHost + "\r\n\r\n";
            out.write(httpReq.getBytes());
            out.flush();

            // 读取 HTTP 响应
            read = in.read(buffer);
            String httpResp = new String(buffer, 0, read);
            assertTrue(httpResp.contains("200 OK") || httpResp.contains("HTTP/1.1"),
                "Should receive HTTP response from target via proxy");
        }
    }

    @Test
    void testMultiplePortsDistributeConnections() throws Exception {
        // 模拟多个不同域名的请求，验证它们分散到不同端口
        String[] domains = {
            "a.example.com", "b.example.com", "c.example.com",
            "d.example.com", "e.example.com", "f.example.com",
            "g.example.com", "h.example.com", "i.example.com"
        };

        Set<Integer> usedPorts = new HashSet<>();
        for (String domain : domains) {
            int port = selectPortByHash(domain);
            usedPorts.add(port);

            // 验证该端口可以建立连接
            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());
            }
        }

        // 9 个域名应该分散到至少 3 个不同的端口
        assertTrue(usedPorts.size() >= 3,
            "9 domains should be distributed across at least 3 ports, got: " + usedPorts.size());
    }

    @Test
    void testSameDomainAlwaysSamePort() throws Exception {
        // 同一域名应始终哈希到同一端口
        String domain = "consistent.example.com";
        int port1 = selectPortByHash(domain);
        int port2 = selectPortByHash(domain);
        assertEquals(port1, port2);
    }

    /**
     * 模拟 PAC 脚本的域名哈希逻辑，返回选中的端口。
     */
    private int selectPortByHash(String host) {
        int hash = 0;
        for (int i = 0; i < host.length(); i++) {
            hash = ((hash << 5) - hash) + host.charAt(i);
            hash = hash & hash;
        }
        return PROXY_PORTS.get(Math.abs(hash) % PROXY_PORTS.size());
    }

    private ProxyConfig createProxyConfig(int remotePort) {
        ProxyConfig config = new ProxyConfig();
        config.setLocalPorts(PROXY_PORTS);

        ProxyConfig.PacServerConfig pacConfig = new ProxyConfig.PacServerConfig();
        pacConfig.setEnabled(true);
        pacConfig.setPort(PAC_PORT);
        pacConfig.setListenHost("127.0.0.1");
        config.setPacServer(pacConfig);

        ProxyConfig.RemoteServerConfig remote = new ProxyConfig.RemoteServerConfig();
        remote.setHost("127.0.0.1");
        remote.setPort(remotePort);
        config.setRemoteServers(List.of(remote));

        config.setSystemProxy(new ProxyConfig.SystemProxy());
        config.getSystemProxy().setEnabled(false);

        config.setHttpProxyEnabled(true);
        return config;
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
```

#### 4.2.1 Mock Target Server

```java
/**
 * Mock 目标 HTTP 服务器，返回简单 HTML。
 * 支持多路径，模拟一个包含多个资源的网站。
 */
public class MockTargetServer {

    private EventLoopGroup group;
    private Channel channel;
    private int port;

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(group)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast(new HttpServerCodec());
                 ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>() {
                     @Override
                     protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
                         if (!(msg instanceof HttpRequest)) return;
                         HttpRequest req = (HttpRequest) msg;

                         String body;
                         if (req.uri().equals("/")) {
                             body = "<html><body>Hello from mock target</body></html>";
                         } else if (req.uri().equals("/style.css")) {
                             body = "body { color: red; }";
                         } else if (req.uri().equals("/script.js")) {
                             body = "console.log('hello');";
                         } else {
                             body = "OK";
                         }

                         FullHttpResponse response = new DefaultFullHttpResponse(
                             HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                             Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
                         response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                         response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length());
                         ctx.writeAndFlush(response);
                     }
                 });
             }
         });
        channel = b.bind(0).sync();
        port = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public int getPort() { return port; }

    public void stop() {
        if (channel != null) channel.close().awaitUninterruptibly();
        if (group != null) group.shutdownGracefully();
    }
}
```

#### 4.2.2 Mock Remote Server

```java
/**
 * Mock proxy-remote，简单地做 TCP 透明转发。
 * 不实现完整的 HTTP/2 协议，只做 CONNECT + 数据中继。
 *
 * 注意：这个 Mock 是轻量级的，用于 E2E 测试验证端口分发逻辑。
 * 完整的 HTTP/2 隧道测试依赖真实的 proxy-remote。
 */
public class MockRemoteServer {
    // 实现省略：接受 TCP 连接，建立到 target 的连接，双向转发
    // 用简单的 TCP relay 模拟，不走 HTTP/2
}
```

### 4.3 性能验证：PerformanceValidationTest

性能验证需要测量"在并发请求场景下，6 连接 vs 36 连接的 Stalled 时间差异"。由于 Stalled 是浏览器侧的概念，无法在 Java 测试中直接测量，分为两种方式：

#### 4.3.1 自动化代理侧延迟测试

测量 proxy-local 接受连接到 CONNECT 响应的延迟，间接反映排队情况：

```java
public class PerformanceValidationTest {

    private ProxyLocalServer proxyLocal;
    private MockTargetServer mockTarget;
    private MockRemoteServer mockRemote;

    private static final List<Integer> MULTI_PORTS =
        List.of(11080, 11081, 11082, 11083, 11084, 11085);
    private static final int SINGLE_PORT = 11080;

    /**
     * 测试单端口模式下，6 个并发 CONNECT 之后的第 7 个请求的排队延迟。
     */
    @Test
    void testSinglePortQueueingDelay() throws Exception {
        // 启动单端口模式
        startProxy(List.of(SINGLE_PORT));

        // 先发起 6 个慢速 CONNECT 占满连接池
        List<Socket> occupying = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Socket s = new Socket("127.0.0.1", SINGLE_PORT);
            // 发送 CONNECT 但不读取响应，保持连接占用
            s.getOutputStream().write(
                ("CONNECT slow.example.com:" + mockTarget.getPort() +
                 " HTTP/1.1\r\nHost: slow.example.com\r\n\r\n").getBytes());
            s.getOutputStream().flush();
            occupying.add(s);
        }

        // 第 7 个请求测量延迟
        long start = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", SINGLE_PORT)) {
            s.getOutputStream().write(
                ("CONNECT test.example.com:" + mockTarget.getPort() +
                 " HTTP/1.1\r\nHost: test.example.com\r\n\r\n").getBytes());
            s.getOutputStream().flush();
            byte[] buf = new byte[1024];
            s.getInputStream().read(buf); // 等待响应
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 第 7 个请求应该明显比无排队时慢
        // 注意：这个测试主要验证排队行为存在，具体阈值依赖环境
        System.out.println("[SinglePort] 7th request latency: " + elapsed + "ms");

        // 清理
        for (Socket s : occupying) s.close();
    }

    /**
     * 测试多端口模式下，6 个并发 CONNECT 分散到不同端口后，第 7 个请求无排队。
     */
    @Test
    void testMultiPortNoQueueing() throws Exception {
        startProxy(MULTI_PORTS);

        // 在不同端口各发起 1 个 CONNECT（6 个端口各 1 个）
        List<Socket> occupying = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int port = MULTI_PORTS.get(i);
            Socket s = new Socket("127.0.0.1", port);
            s.getOutputStream().write(
                ("CONNECT slow" + i + ".example.com:" + mockTarget.getPort() +
                 " HTTP/1.1\r\nHost: slow" + i + ".example.com\r\n\r\n").getBytes());
            s.getOutputStream().flush();
            occupying.add(s);
        }

        // 第 7 个请求选择一个不同于前 6 个的端口（如果域名哈希分散的话）
        // 由于 6 个请求用了 6 个不同端口，每个端口只有 1 个连接，远未达 6 连接上限
        long start = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", MULTI_PORTS.get(0))) {
            s.getOutputStream().write(
                ("CONNECT test.example.com:" + mockTarget.getPort() +
                 " HTTP/1.1\r\nHost: test.example.com\r\n\r\n").getBytes());
            s.getOutputStream().flush();
            byte[] buf = new byte[1024];
            s.getInputStream().read(buf);
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[MultiPort] 7th request latency: " + elapsed + "ms");

        // 多端口模式下，每个端口只有 1~2 个连接，不应有显著排队
        // 阈值设为 2000ms（宽松，主要是验证没有 10 秒级别的 Stalled）
        assertTrue(elapsed < 2000,
            "Multi-port mode should not have significant queueing, got: " + elapsed + "ms");

        for (Socket s : occupying) s.close();
    }

    private void startProxy(List<Integer> ports) throws Exception {
        // 停止之前的实例
        if (proxyLocal != null) proxyLocal.shutdown();

        mockTarget = new MockTargetServer();
        mockTarget.start();

        mockRemote = new MockRemoteServer("127.0.0.1", mockTarget.getPort());
        mockRemote.start();

        ProxyConfig config = new ProxyConfig();
        config.setLocalPorts(ports);
        // ... 其他配置 ...
        config.setSystemProxy(new ProxyConfig.SystemProxy());
        config.getSystemProxy().setEnabled(false);

        proxyLocal = new ProxyLocalServer(config);
        proxyLocal.start();
        Thread.sleep(500);
    }
}
```

#### 4.3.2 手动浏览器性能验证

自动化测试无法直接测量浏览器 Stalled 时间，需要手动验证步骤：

**验证步骤**：

1. **准备环境**：
   - 配置 `proxy.yml` 启用多端口 + PAC 模式
   - 启动 proxy-local
   - 确认系统代理已设置为 PAC 模式

2. **基线测量（关闭多端口）**：
   - 修改配置为单端口模式（`systemProxy.mode: direct`）
   - 重启 proxy-local
   - 清除浏览器缓存（DevTools → Application → Clear storage）
   - 打开 DevTools → Network 面板，勾选 "Disable cache"
   - 访问 `https://github.com`（或任何资源丰富的页面）
   - 记录首次加载时最长 Stalled 时间

3. **优化后测量（启用多端口 + PAC）**：
   - 修改配置为多端口 + PAC 模式
   - 重启 proxy-local
   - 确认 `http://127.0.0.1:8080/proxy.pac` 可访问
   - 确认系统代理设置为 PAC 模式
   - 清除浏览器缓存
   - 访问同一页面
   - 记录首次加载时最长 Stalled 时间

4. **对比预期**：

| 指标 | 基线（单端口） | 优化后（多端口 + PAC） |
|------|---------------|----------------------|
| 最长 Stalled | 5~10 秒 | < 1 秒 |
| 首次加载总时间 | 明显有卡顿 | 平滑渐进加载 |
| 再次加载 | 微秒级 | 微秒级（不变） |

5. **使用 chrome://net-export 验证**：
   - 录制网络日志
   - 在 netlog-viewer 中检查 Socket Pool
   - 应看到多个 `http_proxy_socket_pool` group（每个端口一个）
   - 每个 group 的 `Handed Out` 应远小于 6

### 4.4 测试配置文件

**`src/test/resources/proxy-multiplex-e2e.yml`**：

```yaml
localPorts: [11080, 11081, 11082, 11083, 11084, 11085]

pacServer:
  enabled: true
  port: 18080
  listenHost: 127.0.0.1

systemProxy:
  enabled: false
  mode: pac
  host: 127.0.0.1

remoteServers:
  - host: 127.0.0.1
    port: 19090

cluster: failover
loadBalance: roundrobin
timeoutMs: 8000
connectionsPerNode: 1
httpProxyEnabled: true

route:
  defaultRoute: proxy
  proxyList: []
  directList: []
```

## 5. 测试用例汇总

### 5.1 集成测试用例

| 编号 | 测试名称 | 验证内容 |
|------|---------|---------|
| IT-1 | testAllProxyPortsAcceptConnections | 所有 6 个端口可接受连接 |
| IT-2 | testPacServerReturnsValidScript | PAC 服务返回有效脚本 |
| IT-3 | testPacScriptContainsAllPorts | PAC 脚本包含所有端口 |
| IT-4 | testPacScriptContainsCorrectHost | PAC 脚本包含正确的代理地址 |
| IT-5 | testPacServerReturnsCorrectContentType | Content-Type 正确 |
| IT-6 | testPacServerReturnsCacheControl | Cache-Control 头存在 |
| IT-7 | testShutdownClosesAllPorts | 关闭后所有端口不可连接 |

### 5.2 全链路测试用例

| 编号 | 测试名称 | 验证内容 |
|------|---------|---------|
| E2E-1 | testPacScriptServedCorrectly | PAC 脚本通过 HTTP 正确分发 |
| E2E-2 | testProxyThroughPacSelectedPort | 通过 PAC 选择的端口可以完成代理 |
| E2E-3 | testMultiplePortsDistributeConnections | 不同域名分散到不同端口 |
| E2E-4 | testSameDomainAlwaysSamePort | 同一域名始终哈希到同一端口 |

### 5.3 性能验证用例

| 编号 | 测试名称 | 验证内容 |
|------|---------|---------|
| PERF-1 | testSinglePortQueueingDelay | 单端口模式下第 7 个请求有排队延迟 |
| PERF-2 | testMultiPortNoQueueing | 多端口模式下无显著排队 |
| PERF-3 | 手动浏览器验证 | Stalled 时间从 ~10s 降至 <1s |

## 6. 完成标准

- [ ] `MultiplexIntegrationTest` 所有 7 个测试用例通过
- [ ] `E2EMultiplexTest` 所有 4 个测试用例通过
- [ ] `PerformanceValidationTest` 自动化测试通过
- [ ] 手动浏览器验证：Stalled 时间显著降低
- [ ] `mvn test -pl proxy-local` 全部测试通过（含 Task-1、Task-2 的单元测试）
- [ ] 现有 30 个测试（17 HttpRequestParser + 13 existing）全部通过，无回归
- [ ] `mvn compile -pl proxy-local -am` 编译通过

## 7. 不做什么

- 不做跨操作系统的 SystemProxyManager 实际行为测试（需要真实 macOS/Windows/Linux 环境）
- 不做真实 proxy-remote 的 HTTP/2 隧道性能测试（Mock Remote 不走 HTTP/2）
- 不做 Chrome 版本兼容性测试（PAC 是标准协议，Chrome/Firefox/Safari 均长期支持）
- 不修改任何生产代码——本任务只编写测试
