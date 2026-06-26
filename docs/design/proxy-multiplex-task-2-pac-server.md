# Task-2：PAC 脚本生成 + HTTP 服务 + SystemProxyManager PAC 模式

> **所属 SDD**：[proxy-multiplex-design.md](./proxy-multiplex-design.md)
> **任务编号**：Task-2 / 3
> **预计产出**：PAC 脚本自动生成与 HTTP 分发、SystemProxyManager 支持 PAC 模式设置系统代理
> **前置依赖**：Task-1（多端口监听已就绪，`ProxyConfig` 已含 `localPorts` 和 `PacServerConfig`）
> **后续依赖**：Task-3（集成测试与全链路测试）

---

## 1. 任务目标

实现三个功能模块：

1. **PacScriptGenerator**：根据监听端口列表生成 PAC 脚本字符串，纯工具类。
2. **PacServerHandler**：Netty HTTP Handler，响应浏览器的 PAC 脚本请求。
3. **SystemProxyManager PAC 模式**：在 macOS / Windows / Linux 上通过系统命令设置 Auto Proxy URL。

并将三者集成到 `ProxyLocalServer` 的启动和关闭流程中。

## 2. 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `proxy-local/src/main/java/com/proxy/local/pac/PacScriptGenerator.java` | **新增** | PAC 脚本生成工具类 |
| `proxy-local/src/main/java/com/proxy/local/pac/PacServerHandler.java` | **新增** | PAC HTTP 请求处理器 |
| `proxy-local/src/main/java/com/proxy/local/systemproxy/SystemProxyManager.java` | **修改** | 接口新增 PAC 方法 |
| `proxy-local/src/main/java/com/proxy/local/systemproxy/MacSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `proxy-local/src/main/java/com/proxy/local/systemproxy/WindowsSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `proxy-local/src/main/java/com/proxy/local/systemproxy/LinuxSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `proxy-local/src/main/java/com/proxy/local/systemproxy/NoopSystemProxyManager.java` | **修改** | 实现 PAC 方法（空实现） |
| `proxy-local/src/main/java/com/proxy/local/ProxyLocalServer.java` | **修改** | 启动/关闭 PAC 服务 + 调用 enablePac |
| `proxy-local/src/test/java/com/proxy/local/pac/PacScriptGeneratorTest.java` | **新增** | PAC 脚本生成单元测试 |
| `proxy-local/src/test/java/com/proxy/local/pac/PacServerHandlerTest.java` | **新增** | PAC HTTP 服务集成测试 |

## 3. 实现思路

### 3.1 PacScriptGenerator

**位置**：`proxy-local/src/main/java/com/proxy/local/pac/PacScriptGenerator.java`

**职责**：根据端口列表生成 PAC 脚本。纯工具类，无外部依赖。

#### 3.1.1 生成的 PAC 脚本格式

```javascript
function FindProxyForURL(url, host) {
    var ports = [1080, 1081, 1082, 1083, 1084, 1085];
    var hash = 0;
    for (var i = 0; i < host.length; i++) {
        hash = ((hash << 5) - hash) + host.charCodeAt(i);
        hash = hash & hash;
    }
    var port = ports[Math.abs(hash) % ports.length];
    return "PROXY 127.0.0.1:" + port;
}
```

#### 3.1.2 类结构

```java
public class PacScriptGenerator {

    private static final String PAC_TEMPLATE =
        "function FindProxyForURL(url, host) {\n" +
        "    var ports = %s;\n" +
        "    var hash = 0;\n" +
        "    for (var i = 0; i < host.length; i++) {\n" +
        "        hash = ((hash << 5) - hash) + host.charCodeAt(i);\n" +
        "        hash = hash & hash;\n" +
        "    }\n" +
        "    var port = ports[Math.abs(hash) %% ports.length];\n" +
        "    return \"PROXY %s:\" + port;\n" +
        "}\n";

    /**
     * 生成 PAC 脚本。
     *
     * @param ports  监听端口列表
     * @param proxyHost  代理主机地址（通常为 127.0.0.1）
     * @return PAC 脚本字符串
     */
    public static String generate(List<Integer> ports, String proxyHost) {
        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("ports must not be empty");
        }
        // 构造 JS 数组格式: [1080, 1081, 1082]
        String portArray = ports.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", ", "[", "]"));
        return String.format(PAC_TEMPLATE, portArray, proxyHost);
    }

    /**
     * 生成 PAC 脚本的 HTTP Content-Type 头值。
     */
    public static String getContentType() {
        return "application/x-ns-proxy-autoconfig; charset=utf-8";
    }
}
```

**设计要点**：

1. **域名哈希**：使用 DJB2 变体哈希（`hash = hash * 33 + char`），分布均匀且实现简单。`hash & hash` 是 JavaScript 的 32 位整数转换惯用写法。
2. **同一域名同一端口**：哈希是确定性的，同一域名始终映射到同一端口，保证 TCP 连接可复用（keep-alive）。
3. **PAC 模板中的 `%%`**：`String.format` 中 `%%` 转义为 `%`，因为 JS 中 `%` 是取模运算符。

#### 3.1.3 哈希分布验证

以 6 个端口为例，常见域名分布预期：

| 域名 | hash % 6 | 端口 |
|------|----------|------|
| github.com | — | 1080~1085 之一 |
| assets-cdn.github.com | — | 1080~1085 之一 |
| avas.githubusercontent.com | — | 1080~1085 之一 |
| www.google.com | — | 1080~1085 之一 |

同一页面的不同域名会分散到不同端口，不会集中排队。测试中需要验证分布均匀性。

### 3.2 PacServerHandler

**位置**：`proxy-local/src/main/java/com/proxy/local/pac/PacServerHandler.java`

**职责**：处理 HTTP GET 请求，返回 PAC 脚本。

```java
public class PacServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger log = LoggerFactory.getLogger(PacServerHandler.class);

    private final String pacScript;
    private final byte[] pacBytes;

    private static final String PAC_PATH = "/proxy.pac";
    private static final HttpResponseStatus NOT_FOUND = new HttpResponseStatus(404, "Not Found");

    public PacServerHandler(String pacScript) {
        this.pacScript = pacScript;
        this.pacBytes = pacScript.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (!(msg instanceof HttpRequest)) {
            return;
        }

        HttpRequest request = (HttpRequest) msg;
        String uri = request.uri();

        if (!PAC_PATH.equals(uri)) {
            // 非 /proxy.pac 路径，返回 404
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, NOT_FOUND);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response);
            return;
        }

        if (request.method() != HttpMethod.GET) {
            // 非 GET 方法，返回 405
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response);
            return;
        }

        // 返回 PAC 脚本
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(pacBytes));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
            PacScriptGenerator.getContentType());
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, pacBytes.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=300");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response);
        log.debug("Served PAC script to {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("PAC server error", cause);
        ctx.close();
    }
}
```

**设计要点**：

1. **只处理 `/proxy.pac`**：其他路径返回 404，防止被当作通用 HTTP 服务。
2. **Cache-Control: max-age=300**：让浏览器缓存 PAC 脚本 5 分钟，减少重复请求。5 分钟足够短，如果端口配置变更重启服务后能较快生效。
3. **Connection: close**：PAC 请求是一次性的，不需要 keep-alive，返回后关闭连接。
4. **预编码**：PAC 脚本在构造时一次性编码为 byte[]，每次请求直接返回，零额外开销。

### 3.3 ProxyLocalServer 集成 PAC 服务

在 `ProxyLocalServer` 中新增 PAC 服务的启动和关闭逻辑：

```java
public class ProxyLocalServer {
    // ... 现有字段 ...

    private Channel pacServerChannel;
    private String pacScriptContent;

    public void start() throws InterruptedException {
        List<Integer> ports = config.getEffectiveLocalPorts();
        // ... 绑定代理端口（Task-1 已实现）...

        // 启动 PAC 服务
        if (config.getPacServer() != null && config.getPacServer().isEnabled()) {
            startPacServer(ports);
        }

        enableSystemProxy();
    }

    private void startPacServer(List<Integer> proxyPorts) throws InterruptedException {
        ProxyConfig.PacServerConfig pacConfig = config.getPacServer();

        // 生成 PAC 脚本
        String proxyHost = config.getSystemProxy() != null
            ? config.getSystemProxy().getHost() : "127.0.0.1";
        pacScriptContent = PacScriptGenerator.generate(proxyPorts, proxyHost);

        // 启动 HTTP 服务
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("codec", new HttpServerCodec());
                 ch.pipeline().addLast("pac-handler",
                     new PacServerHandler(pacScriptContent));
             }
         })
         .option(ChannelOption.SO_BACKLOG, 32);

        pacServerChannel = b.bind(pacConfig.getListenHost(), pacConfig.getPort())
            .sync().channel();

        log.info("PAC server started at http://{}:{}/proxy.pac",
            pacConfig.getListenHost(), pacConfig.getPort());
    }

    private void enableSystemProxy() {
        if (!config.getSystemProxy().isEnabled()) {
            return;
        }

        if ("pac".equals(config.getSystemProxy().getMode())
                && pacServerChannel != null) {
            // PAC 模式
            String pacUrl = String.format("http://%s:%d/proxy.pac",
                config.getPacServer().getListenHost(),
                config.getPacServer().getPort());
            systemProxyManager.enablePac(pacUrl);
            log.info("System proxy set to PAC mode: {}", pacUrl);
        } else {
            // 旧模式：直接设置代理地址
            int port = config.getEffectiveLocalPorts().get(0);
            systemProxyManager.enable(config.getSystemProxy().getHost(), port);
            log.info("System proxy set to direct mode: {}:{}",
                config.getSystemProxy().getHost(), port);
        }
    }

    public void shutdown() {
        // 1. 还原系统代理
        systemProxyManager.disable();

        // 2. 关闭 PAC 服务
        if (pacServerChannel != null) {
            pacServerChannel.close().awaitUninterruptibly();
            log.info("PAC server stopped");
        }

        // 3. 关闭代理监听（Task-1 已实现）
        // ...

        // 4. 关闭 EventLoopGroup
        // ...
    }
}
```

### 3.4 SystemProxyManager 接口改造

#### 3.4.1 接口新增方法

```java
public interface SystemProxyManager {
    // 现有
    void enable(String host, int port);
    void disable();
    boolean isEnabled();

    // 新增
    void enablePac(String pacUrl);
    void disablePac();
}
```

#### 3.4.2 MacSystemProxyManager 实现

```java
@Override
public void enablePac(String pacUrl) {
    for (String iface : activeInterfaces) {
        execute("networksetup", "-setautoproxyurl", iface, pacUrl);
        execute("networksetup", "-setautoproxystate", iface, "on");
    }
    pacEnabled = true;
    log.info("macOS auto proxy (PAC) enabled: {} on {} interfaces", pacUrl, activeInterfaces.size());
}

@Override
public void disablePac() {
    if (!pacEnabled) return;
    for (String iface : activeInterfaces) {
        execute("networksetup", "-setautoproxystate", iface, "off");
    }
    pacEnabled = false;
    log.info("macOS auto proxy (PAC) disabled");
}
```

**macOS networksetup 命令说明**：
- `networksetup -setautoproxyurl <interface> <url>`：设置 PAC URL
- `networksetup -setautoproxystate <interface> on|off`：启用/禁用 PAC
- 设置 PAC 时不需要先禁用 HTTP/HTTPS 代理，但建议在 `enable()` 时禁用旧的模式，在 `disable()` 时统一还原

#### 3.4.3 WindowsSystemProxyManager 实现

```java
@Override
public void enablePac(String pacUrl) {
    // 设置 AutoConfigURL
    execute("reg", "add", HKCU_INTERNET_SETTINGS,
        "/v", "AutoConfigURL", "/t", "REG_SZ", "/d", pacUrl, "/f");
    // 不需要设置 ProxyEnable（PAC 和手动代理是独立的）
    // 但如果之前启用了手动代理，需要禁用以避免冲突
    execute("reg", "add", HKCU_INTERNET_SETTINGS,
        "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f");
    notifySettingsChanged();
    pacEnabled = true;
    log.info("Windows auto proxy (PAC) enabled: {}", pacUrl);
}

@Override
public void disablePac() {
    if (!pacEnabled) return;
    execute("reg", "delete", HKCU_INTERNET_SETTINGS,
        "/v", "AutoConfigURL", "/f");
    notifySettingsChanged();
    pacEnabled = false;
    log.info("Windows auto proxy (PAC) disabled");
}
```

**Windows 注册表说明**：
- `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings\AutoConfigURL`：PAC 脚本 URL
- 设置 PAC 时将 `ProxyEnable` 设为 0（禁用手动代理），避免冲突
- 删除 PAC 时移除 `AutoConfigURL` 键

#### 3.4.4 LinuxSystemProxyManager 实现

```java
@Override
public void enablePac(String pacUrl) {
    execute("gsettings", "set", "org.gnome.system.proxy",
        "autoconfig-url", pacUrl);
    execute("gsettings", "set", "org.gnome.system.proxy",
        "mode", "auto");
    pacEnabled = true;
    log.info("Linux (GNOME) auto proxy (PAC) enabled: {}", pacUrl);
}

@Override
public void disablePac() {
    if (!pacEnabled) return;
    execute("gsettings", "set", "org.gnome.system.proxy",
        "mode", "none");
    pacEnabled = false;
    log.info("Linux (GNOME) auto proxy (PAC) disabled");
}
```

#### 3.4.5 NoopSystemProxyManager 实现

```java
@Override
public void enablePac(String pacUrl) {
    log.info("PAC mode not supported on this platform, PAC URL: {}", pacUrl);
}

@Override
public void disablePac() {
    // noop
}
```

## 4. 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| PAC 服务端口被占用 | 启动失败，打印错误日志，不设置系统代理（避免设置无效的 PAC URL） |
| PAC 服务启动失败但代理端口已启动 | 代理功能正常可用，用户需手动配置代理地址 |
| `pacServer.enabled = false` | 不启动 PAC 服务，系统代理回退到 `direct` 模式 |
| `systemProxy.mode = pac` 但 `pacServer.enabled = false` | 警告日志，回退到 `direct` 模式 |
| 浏览器请求非 `/proxy.pac` 路径 | 返回 404 |
| 浏览器发送非 GET 请求 | 返回 405 Method Not Allowed |
| 部分代理端口绑定失败 | PAC 脚本只包含成功绑定的端口列表 |

## 5. 测试方案

### 5.1 单元测试

#### 5.1.1 PacScriptGenerator 测试

```java
public class PacScriptGeneratorTest {

    @Test
    void testBasicGeneration() {
        String pac = PacScriptGenerator.generate(
            List.of(1080, 1081, 1082), "127.0.0.1");
        assertTrue(pac.contains("function FindProxyForURL"));
        assertTrue(pac.contains("[1080, 1081, 1082]"));
        assertTrue(pac.contains("127.0.0.1"));
    }

    @Test
    void testSinglePort() {
        String pac = PacScriptGenerator.generate(
            List.of(1080), "127.0.0.1");
        assertTrue(pac.contains("[1080]"));
    }

    @Test
    void testCustomHost() {
        String pac = PacScriptGenerator.generate(
            List.of(1080), "192.168.1.100");
        assertTrue(pac.contains("192.168.1.100"));
    }

    @Test
    void testEmptyPortsThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            PacScriptGenerator.generate(Collections.emptyList(), "127.0.0.1"));
    }

    @Test
    void testNullPortsThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            PacScriptGenerator.generate(null, "127.0.0.1"));
    }

    @Test
    void testContentType() {
        String ct = PacScriptGenerator.getContentType();
        assertTrue(ct.contains("x-ns-proxy-autoconfig"));
    }

    @Test
    void testPacScriptContainsValidJs() {
        // 生成的脚本应该是有效的 JavaScript（能被 PAC 引擎解析）
        String pac = PacScriptGenerator.generate(
            List.of(1080, 1081, 1082, 1083, 1084, 1085), "127.0.0.1");
        // 验证关键语法元素
        assertTrue(pac.contains("var ports"));
        assertTrue(pac.contains("for (var i"));
        assertTrue(pac.contains("charCodeAt"));
        assertTrue(pac.contains("Math.abs(hash)"));
        assertTrue(pac.contains("ports.length"));
        assertTrue(pac.contains("return \"PROXY"));
    }

    @Test
    void testHashDeterministic() {
        // 同样的端口列表生成的 PAC 脚本应该完全一致
        String pac1 = PacScriptGenerator.generate(
            List.of(1080, 1081), "127.0.0.1");
        String pac2 = PacScriptGenerator.generate(
            List.of(1080, 1081), "127.0.0.1");
        assertEquals(pac1, pac2);
    }
}
```

#### 5.1.2 哈希分布均匀性测试

```java
@Test
void testHashDistribution() {
    // 模拟 PAC 脚本的哈希逻辑，验证分布在 6 个端口上足够均匀
    int[] ports = {1080, 1081, 1082, 1083, 1084, 1085};
    int[] counts = new int[6];

    // 使用常见的网站域名测试
    String[] domains = {
        "github.com", "www.google.com", "www.youtube.com",
        "assets-cdn.github.com", "avatars.githubusercontent.com",
        "fonts.googleapis.com", "fonts.gstatic.com",
        "ajax.googleapis.com", "ssl.gstatic.com",
        "www.gstatic.com", "apis.google.com", "accounts.google.com",
        "cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "registry.npmjs.org", "objects.githubusercontent.com",
        "raw.githubusercontent.com", "api.github.com",
        "developer.mozilla.org", "stackoverflow.com",
        "i.stack.imgur.com", "www.gravatar.com",
        "en.wikipedia.org", "upload.wikimedia.org"
    };

    for (String domain : domains) {
        int hash = 0;
        for (int i = 0; i < domain.length(); i++) {
            hash = ((hash << 5) - hash) + domain.charAt(i);
            hash = hash & hash; // 32-bit conversion
        }
        int portIndex = Math.abs(hash) % 6;
        counts[portIndex]++;
    }

    // 每个端口至少应该有 1 个域名（25 个域名 / 6 个端口 ≈ 4 每个）
    for (int i = 0; i < 6; i++) {
        assertTrue(counts[i] >= 1, "Port index " + i + " has " + counts[i] + " domains, expected at least 1");
    }

    // 最多的端口不应该超过总数的 50%（避免严重倾斜）
    int maxCount = Arrays.stream(counts).max().getAsInt();
    assertTrue(maxCount < domains.length / 2,
        "Max count " + maxCount + " is too high, distribution is skewed");
}
```

### 5.2 集成测试

#### 5.2.1 PAC HTTP 服务测试

```java
public class PacServerHandlerTest {

    private EmbeddedChannel channel;
    private String testPacScript;

    @BeforeEach
    void setUp() {
        testPacScript = PacScriptGenerator.generate(
            List.of(1080, 1081, 1082), "127.0.0.1");
        channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new PacServerHandler(testPacScript));
    }

    @Test
    void testGetProxyPac() {
        // 发送 GET /proxy.pac
        HttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/proxy.pac");
        channel.writeInbound(request);

        // 读取响应
        HttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get(HttpHeaderNames.CONTENT_TYPE)
            .contains("x-ns-proxy-autoconfig"));

        HttpContent content = channel.readOutbound();
        ByteBuf buf = content.content();
        String body = buf.toString(StandardCharsets.UTF_8);
        assertEquals(testPacScript, body);
        buf.release();
    }

    @Test
    void testGetWrongPath() {
        HttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/other");
        channel.writeInbound(request);

        HttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
    }

    @Test
    void testPostMethod() {
        HttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/proxy.pac");
        channel.writeInbound(request);

        HttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }
}
```

#### 5.2.2 完整 PAC 服务启动测试

```java
public class PacServerIntegrationTest {

    private ProxyLocalServer server;
    private int pacPort = 18080; // 使用非默认端口避免冲突

    @BeforeEach
    void setUp() throws Exception {
        ProxyConfig config = createTestConfig();
        server = new ProxyLocalServer(config);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    @Test
    void testPacScriptAccessibleViaHttp() throws Exception {
        URL url = new URL("http://127.0.0.1:" + pacPort + "/proxy.pac");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("x-ns-proxy-autoconfig"));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String pac = reader.lines().collect(Collectors.joining("\n"));
            assertTrue(pac.contains("function FindProxyForURL"));
            assertTrue(pac.contains("1080"));
        }
    }

    @Test
    void testWrongPathReturns404() throws Exception {
        URL url = new URL("http://127.0.0.1:" + pacPort + "/wrong");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        assertEquals(404, conn.getResponseCode());
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
mvn test -pl proxy-local -Dtest=PacScriptGeneratorTest
mvn test -pl proxy-local -Dtest=PacServerHandlerTest
```

**集成测试**：
```bash
mvn test -pl proxy-local -Dtest=PacServerIntegrationTest
```

**手动验证**：
```bash
# 1. 启动 proxy-local（配置 pacServer.enabled: true）
# 2. 浏览器访问 http://127.0.0.1:8080/proxy.pac，应看到 PAC 脚本内容
# 3. 检查系统代理是否已设置为 PAC 模式：
#    macOS: networksetup -getautoproxyurl Wi-Fi
#    Windows: reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL
```

## 6. 完成标准

- [ ] `PacScriptGenerator` 实现完成，生成有效的 PAC 脚本
- [ ] `PacServerHandler` 实现完成，正确处理 GET /proxy.pac 请求
- [ ] `SystemProxyManager` 接口新增 `enablePac` / `disablePac` 方法
- [ ] macOS / Windows / Linux 三个实现类的 PAC 模式实现完成
- [ ] `ProxyLocalServer` 集成 PAC 服务启动/关闭逻辑
- [ ] `ProxyLocalServer` 集成系统代理 PAC 模式设置逻辑
- [ ] `PacScriptGeneratorTest` 所有测试用例通过
- [ ] `PacServerHandlerTest` 所有测试用例通过
- [ ] `PacServerIntegrationTest` 所有测试用例通过
- [ ] 手动验证：浏览器能通过 HTTP 获取 PAC 脚本
- [ ] `mvn compile -pl proxy-local -am` 编译通过

## 7. 不做什么

本任务不涉及以下内容，由 Task-3 负责：

- 不做端到端全链路测试（浏览器 → PAC → 代理 → 目标网站）
- 不做 Stalled 时间性能对比测试
- 不做不同操作系统的 SystemProxyManager 实际行为验证（需要真实环境）
- 不修改代理转发核心逻辑
