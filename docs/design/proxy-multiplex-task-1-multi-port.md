# Task-1：多端口监听 —— ProxyLocalServer + ProxyConfig 改造

> **所属 SDD**：[proxy-multiplex-design.md](./proxy-multiplex-design.md)
> **任务编号**：Task-1 / 3
> **预计产出**：proxy-local 支持同时监听多个端口，每个端口的代理行为完全一致
> **前置依赖**：无
> **后续依赖**：Task-2（PAC 脚本 + SystemProxyManager）依赖本任务的多端口能力

---

## 1. 任务目标

修改 `ProxyLocalServer` 使其支持同时监听多个端口，修改 `ProxyConfig` 增加多端口配置项。所有端口共享同一个 `Invoker`、`RouteRule`、`EventLoopGroup`，每个端口的 pipeline 完全一致。向后兼容：未配置多端口时回退到单端口模式。

## 2. 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `proxy-local/src/main/java/com/proxy/local/config/ProxyConfig.java` | **修改** | 新增 `localPorts` 字段、`PacServerConfig` 内部类、`SystemProxy.mode` 字段 |
| `proxy-local/src/main/java/com/proxy/local/ProxyLocalServer.java` | **修改** | `start()` 多端口绑定、`shutdown()` 多端口关闭 |
| `proxy-local/src/main/resources/proxy.yml` | **修改** | 新增 `localPorts`、`pacServer` 配置项示例 |
| `proxy-local/src/test/java/com/proxy/local/config/ProxyConfigTest.java` | **新增** | 配置解析单元测试 |

## 3. 实现思路

### 3.1 ProxyConfig 改动

#### 3.1.1 新增字段

```java
public class ProxyConfig {
    // 现有
    private int localPort = 1080;

    // 新增：多端口监听
    private List<Integer> localPorts;  // null 表示未配置，回退到 localPort

    // 新增：PAC 服务配置
    private PacServerConfig pacServer;

    // 修改：SystemProxy 新增 mode 字段
    public static class SystemProxy {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private String mode = "direct";  // "pac" 或 "direct"，默认 "direct" 向后兼容
        // getters/setters ...
    }

    // 新增
    public static class PacServerConfig {
        private boolean enabled = false;
        private int port = 8080;
        private String listenHost = "127.0.0.1";
        // getters/setters ...
    }

    /**
     * 获取实际需要监听的端口列表。
     * 优先使用 localPorts，未配置则回退到 localPort。
     */
    public List<Integer> getEffectiveLocalPorts() {
        if (localPorts != null && !localPorts.isEmpty()) {
            return localPorts;
        }
        return Collections.singletonList(localPort);
    }

    /**
     * 是否启用了多端口模式。
     */
    public boolean isMultiPortMode() {
        return localPorts != null && localPorts.size() > 1;
    }
}
```

#### 3.1.2 向后兼容策略

`localPorts` 默认为 `null`。`getEffectiveLocalPorts()` 方法在有 `localPorts` 时返回多端口列表，否则回退到 `localPort` 单元素列表。这样现有配置（只配了 `localPort: 1080`）不需要任何修改，行为完全不变。

### 3.2 ProxyLocalServer 改动

#### 3.2.1 start() 方法改造

现有 `start()` 方法只绑定一个端口。改造为遍历端口列表逐个绑定：

```java
public void start() throws InterruptedException {
    // bossGroup 和 workerGroup 在构造函数中已创建，所有端口共享

    List<Integer> ports = config.getEffectiveLocalPorts();
    List<Channel> serverChannels = new ArrayList<>();

    for (int port : ports) {
        try {
            Channel channel = bindProxyPort(port);
            serverChannels.add(channel);
            log.info("Proxy listener started on port {}", port);
        } catch (Exception e) {
            log.error("Failed to bind proxy port {}: {}", port, e.getMessage());
            // 某个端口绑定失败不影响其他端口
        }
    }

    if (serverChannels.isEmpty()) {
        throw new IllegalStateException("No proxy listener could be started");
    }

    // PAC 服务启动由 Task-2 实现，此处预留
    // if (config.getPacServer() != null && config.getPacServer().isEnabled()) {
    //     startPacServer();
    // }

    enableSystemProxy();
}

private Channel bindProxyPort(int port) throws InterruptedException {
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
     .channel(NioServerSocketChannel.class)
     .childHandler(new ChannelInitializer<SocketChannel>() {
         @Override
         protected void initChannel(SocketChannel ch) {
             ch.pipeline().addLast("protocol-detector",
                 new ProtocolDetector(clusterInvoker, config.isHttpProxyEnabled(), routeRule));
         }
     })
     .option(ChannelOption.SO_BACKLOG, 128)
     .childOption(ChannelOption.TCP_NODELAY, true);

    return b.bind(port).sync().channel();
}
```

**设计要点**：

1. **共享 EventLoopGroup**：所有端口共用同一个 `bossGroup`（1 个线程）和 `workerGroup`（默认 CPU 核数 × 2 个线程）。不会因为端口增加而增加线程数。
2. **容错**：单个端口绑定失败不阻止其他端口启动。所有端口都失败才抛异常。
3. **Pipeline 一致**：每个端口的 childHandler 完全相同，都添加 `ProtocolDetector`，传入相同的 `clusterInvoker` 和 `routeRule`。

#### 3.2.2 shutdown() 方法改造

```java
public void shutdown() {
    // 1. 还原系统代理
    disableSystemProxy();

    // 2. 关闭所有代理监听 Channel
    for (Channel ch : serverChannels) {
        if (ch != null) {
            ch.close().awaitUninterruptibly();
        }
    }

    // 3. 关闭 PAC 服务 Channel（如果存在，由 Task-2 添加）
    // if (pacServerChannel != null) {
    //     pacServerChannel.close().awaitUninterruptibly();
    // }

    // 4. 关闭 EventLoopGroup
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();

    // 5. 关闭 Invoker 链（不变）
    // ...
}
```

#### 3.2.3 字段变更

```java
public class ProxyLocalServer {
    // 现有
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    // 修改：单 Channel → Channel 列表
    private List<Channel> serverChannels = new ArrayList<>();

    // 预留：PAC 服务 Channel（Task-2 使用）
    // private Channel pacServerChannel;
}
```

### 3.3 proxy.yml 配置示例

```yaml
# ===== 多端口监听 =====
# 启用多端口模式，配合 PAC 脚本绕过浏览器 6 连接限制
localPorts: [1080, 1081, 1082, 1083, 1084, 1085]

# 向后兼容：未配置 localPorts 时使用此单项
# localPort: 1080

# ===== PAC 服务（Task-2 实现）=====
pacServer:
  enabled: true
  port: 8080
  listenHost: 127.0.0.1

# ===== 系统代理 =====
systemProxy:
  enabled: true
  mode: pac          # "pac" = 使用 PAC 脚本, "direct" = 直接设置代理地址（旧模式）
  host: 127.0.0.1

# ===== 以下配置不变 =====
remoteServers:
  - host: 54.234.196.30
    port: 9090
    # ...
cluster: failover
loadBalance: roundrobin
# ...
```

## 4. 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| `localPorts` 未配置（null） | 回退到 `localPort` 单端口模式，行为与当前完全一致 |
| `localPorts` 只配了一个端口 | 正常工作，等价于单端口模式 |
| `localPorts` 含重复端口 | 绑定时第二个会失败（端口已被占用），跳过并告警 |
| `localPorts` 含已被占用的端口 | 跳过该端口，告警，其他端口正常启动 |
| 所有端口都绑定失败 | 抛出 `IllegalStateException`，进程退出 |
| `localPorts` 和 `localPort` 同时配置 | `localPorts` 优先，`localPort` 被忽略 |

## 5. 测试方案

### 5.1 单元测试

#### 5.1.1 ProxyConfig 解析测试

```java
public class ProxyConfigTest {

    @Test
    void testSinglePortFallback() {
        // 只配 localPort，未配 localPorts
        ProxyConfig config = loadConfig("proxy-single-port.yml");
        assertNull(config.getLocalPorts());
        assertEquals(1080, config.getLocalPort());
        assertEquals(List.of(1080), config.getEffectiveLocalPorts());
        assertFalse(config.isMultiPortMode());
    }

    @Test
    void testMultiPortMode() {
        ProxyConfig config = loadConfig("proxy-multi-port.yml");
        assertEquals(List.of(1080, 1081, 1082, 1083, 1084, 1085),
                     config.getEffectiveLocalPorts());
        assertTrue(config.isMultiPortMode());
    }

    @Test
    void testSystemProxyModeDefault() {
        ProxyConfig config = new ProxyConfig();
        assertEquals("direct", config.getSystemProxy().getMode());
    }

    @Test
    void testPacServerConfigDefault() {
        ProxyConfig config = new ProxyConfig();
        assertNotNull(config.getPacServer());
        assertFalse(config.getPacServer().isEnabled());
        assertEquals(8080, config.getPacServer().getPort());
    }

    @Test
    void testLocalPortsPriority() {
        // 同时配了 localPorts 和 localPort，localPorts 优先
        ProxyConfig config = loadConfig("proxy-both-ports.yml");
        assertEquals(List.of(1080, 1081), config.getEffectiveLocalPorts());
    }

    @Test
    void testEmptyLocalPortsFallback() {
        // localPorts 配了空列表
        ProxyConfig config = new ProxyConfig();
        config.setLocalPorts(Collections.emptyList());
        config.setLocalPort(1080);
        assertEquals(List.of(1080), config.getEffectiveLocalPorts());
    }
}
```

#### 5.1.2 测试资源文件

需要创建多个 YAML 测试配置文件：

**`src/test/resources/proxy-single-port.yml`**：
```yaml
localPort: 1080
systemProxy:
  enabled: false
  host: 127.0.0.1
remoteServers:
  - host: 127.0.0.1
    port: 9090
```

**`src/test/resources/proxy-multi-port.yml`**：
```yaml
localPorts: [1080, 1081, 1082, 1083, 1084, 1085]
pacServer:
  enabled: true
  port: 8080
systemProxy:
  enabled: true
  mode: pac
  host: 127.0.0.1
remoteServers:
  - host: 127.0.0.1
    port: 9090
```

### 5.2 集成测试

#### 5.2.1 多端口启动与连接验证

```java
public class MultiPortIntegrationTest {

    private ProxyLocalServer server;

    @BeforeEach
    void setUp() throws Exception {
        ProxyConfig config = loadConfig("proxy-multi-port-test.yml");
        // config 中的 remoteServers 指向本地 mock server
        server = new ProxyLocalServer(config);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    @Test
    void testAllPortsAcceptConnections() throws Exception {
        for (int port : List.of(1080, 1081, 1082, 1083, 1084, 1085)) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected(), "Port " + port + " should accept connections");
            }
        }
    }

    @Test
    void testEachPortHandlesSocks5() throws Exception {
        for (int port : List.of(1080, 1081, 1082)) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // SOCKS5 握手
                out.write(new byte[]{0x05, 0x01, 0x00});
                out.flush();

                byte[] response = new byte[2];
                int read = in.read(response);
                assertEquals(2, read);
                assertEquals(0x05, response[0]);
                assertEquals(0x00, response[1]);
            }
        }
    }

    @Test
    void testEachPortHandlesHttpConnect() throws Exception {
        for (int port : List.of(1083, 1084, 1085)) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // 发送 CONNECT 请求
                out.write("CONNECT 127.0.0.1:9999 HTTP/1.1\r\nHost: 127.0.0.1:9999\r\n\r\n".getBytes());
                out.flush();

                // 读取响应（至少能收到响应头，不一定是 200，但说明 HttpConnectHandler 在工作）
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);
                assertTrue(read > 0, "Port " + port + " should respond to HTTP CONNECT");
                String response = new String(buffer, 0, read);
                assertTrue(response.startsWith("HTTP/"));
            }
        }
    }
}
```

### 5.3 验证方法

**编译验证**：
```bash
mvn compile -pl proxy-local -am
```

**单元测试**：
```bash
mvn test -pl proxy-local -Dtest=ProxyConfigTest
```

**集成测试**：
```bash
mvn test -pl proxy-local -Dtest=MultiPortIntegrationTest
```

## 6. 完成标准

- [ ] `ProxyConfig` 新增 `localPorts`、`PacServerConfig`、`SystemProxy.mode` 字段，含完整 getter/setter
- [ ] `getEffectiveLocalPorts()` 和 `isMultiPortMode()` 方法实现正确，向后兼容
- [ ] `ProxyLocalServer.start()` 支持多端口绑定，共享 EventLoopGroup
- [ ] 单个端口绑定失败不影响其他端口
- [ ] `shutdown()` 正确关闭所有监听 Channel
- [ ] `proxy.yml` 更新配置示例
- [ ] `ProxyConfigTest` 所有测试用例通过
- [ ] `MultiPortIntegrationTest` 所有测试用例通过
- [ ] `mvn compile -pl proxy-local -am` 编译通过

## 7. 不做什么

本任务不涉及以下内容，由后续任务完成：

- 不实现 PAC 脚本生成和 HTTP 服务（Task-2）
- 不修改 SystemProxyManager 的 PAC 模式（Task-2）
- 不修改 ProtocolDetector、HttpConnectHandler、Socks5InitHandler 等处理器——每个端口的 pipeline 与单端口模式完全一致
- 不修改 proxy-remote 任何代码——远端不感知本地监听了几個端口
- 不修改 Invoker / ClusterInvoker / RouteRule——多端口共享同一个调用链
