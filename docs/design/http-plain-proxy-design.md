# HTTP 普通代理（非 CONNECT 隧道）支持 —— 开发设计文档

## 1. 背景与问题

当前 proxy-local 仅支持两种代理协议入口：

- **HTTP CONNECT 隧道**：浏览器发送 `CONNECT host:port HTTP/1.1`，代理回复 `200 Connection Established` 后建立透明隧道，后续字节原样透传。适用于 HTTPS 流量。
- **SOCKS5 代理**：通过 SOCKS5 握手协商后建立隧道，同样适用于 HTTPS 流量。

但当浏览器访问**纯 HTTP**（非 HTTPS）网站时，不会发送 CONNECT 请求，而是直接发送完整的 HTTP 请求报文，例如：

```
GET http://example.com/page HTTP/1.1
Host: example.com
Accept: */*

```

当前 `HttpConnectHandler.decode()` 在判断到请求方法不是 CONNECT 时，直接返回 `400 Bad Request` 并关闭连接：

```java
if (parts.length < 3 || !"CONNECT".equalsIgnoreCase(parts[0])) {
    log.warn("Non-CONNECT HTTP request not supported: {}", requestLine);
    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
    ctx.close();
    return;
}
```

这导致所有纯 HTTP 流量无法通过代理，需要新增支持。

## 2. HTTP 普通代理 vs CONNECT 隧道的核心区别

| 对比维度 | CONNECT 隧道 | HTTP 普通代理 |
|---------|-------------|-------------|
| 适用场景 | HTTPS（TLS 加密流量） | HTTP（明文流量） |
| 握手阶段 | 浏览器发 CONNECT → 代理回 200 → 进入隧道 | 无握手，第一条数据就是真正的 HTTP 请求 |
| 代理角色 | 字节透传，不解析内容 | 需解析第一条请求获取目标地址，后续透传 |
| 请求格式 | `CONNECT host:port HTTP/1.1` | `GET http://host/path HTTP/1.1` （绝对 URL） |
| 目标地址来源 | 从 CONNECT 请求行直接获取 | 从请求行的绝对 URL 或 Host 头中解析 |
| 连接复用 | 隧道内可承载任意数据 | 同一 TCP 连接上的 keep-alive 请求，目标地址相同 |
| 代理响应 | 回复 `200 Connection Established` | 不回复，直接转发目标服务器的原始响应 |

## 3. 整体交互流程

```
┌────────┐           ┌────────────┐         ┌─────────────┐         ┌──────────────┐
│ Browser│           │ proxy-local│         │proxy-remote │         │example.com:80│
└───┬────┘           └─────┬──────┘         └──────┬──────┘         └──────┬───────┘
    │  TCP connect (明文)   │                       │                       │
    │─────────────────────→│                       │                       │
    │                      │                       │                       │
    │ GET http://example.com/page HTTP/1.1         │                       │
    │ Host: example.com                            │                       │
    │─────────────────────→│                       │                       │
    │                      │                       │                       │
    │              ┌───────┴───────┐                │                       │
    │              │ HttpRequest-  │                │                       │
    │              │ Parser 解析:  │                │                       │
    │              │ host=example  │                │                       │
    │              │ .com, port=80 │                │                       │
    │              │ 重写URL:      │                │                       │
    │              │ GET /page ... │                │                       │
    │              └───────┬───────┘                │                       │
    │                      │                       │                       │
    │                      │  ProxyMessage(CONNECT) │                       │
    │                      │──────────────────────→│                       │
    │                      │                       │  TCP connect          │
    │                      │                       │──────────────────────→│
    │                      │                       │  TCP connected        │
    │                      │                       │←──────────────────────│
    │                      │  CONNECT_RESPONSE      │                       │
    │                      │←──────────────────────│                       │
    │                      │                       │                       │
    │              ┌───────┴───────┐                │                       │
    │              │  ★ 不回 200！ │                │                       │
    │              │  直接转发重写  │                │                       │
    │              │  后的首条请求  │                │                       │
    │              └───────┬───────┘                │                       │
    │                      │                       │                       │
    │                      │  DATA(重写后的HTTP请求) │                       │
    │                      │──────────────────────→│  GET /page HTTP/1.1   │
    │                      │                       │──────────────────────→│
    │                      │                       │                       │
    │                      │                       │  HTTP/1.1 200 OK      │
    │                      │  DATA(原始HTTP响应)    │  <html>...</html>     │
    │  HTTP/1.1 200 OK     │←──────────────────────│←──────────────────────│
    │  <html>...</html>    │                       │                       │
    │←─────────────────────│                       │                       │
    │                      │                       │                       │
    │  ═══════ 后续 keep-alive 请求直接透传 ═══════                         │
    │                      │                       │                       │
    │ GET /other HTTP/1.1  │  DATA (透传)          │  GET /other HTTP/1.1  │
    │─────────────────────→│──────────────────────→│──────────────────────→│
    │                      │                       │                       │
    │ HTTP/1.1 200 OK      │  DATA (透传)          │  HTTP/1.1 200 OK      │
    │←─────────────────────│←──────────────────────│←──────────────────────│
```

### 关键点说明

1. **无 `200 Connection Established` 回复**：与 CONNECT 隧道不同，HTTP 普通代理不需要回复 200，浏览器等待的是目标服务器的真实 HTTP 响应。
2. **首条请求不可丢失**：第一条 HTTP 请求既是"告诉代理目标地址"的信息来源，也是需要准确传达给目标服务器的业务数据。代理从中解析出目标地址后，必须将其（经 URL 重写后）完整转发。
3. **URL 重写**：浏览器发给代理的请求使用绝对 URL（`GET http://example.com/page`），但目标服务器只接受相对路径（`GET /page`），代理必须完成这个转换。
4. **后续请求透传**：首条请求处理完毕后，同一连接上的后续 keep-alive 请求目标地址相同，直接透传字节即可。

## 4. 新增/修改的类

### 4.1 新增：HttpRequestParser（工具类）

**位置**：`proxy-local/src/main/java/com/proxy/local/handler/HttpRequestParser.java`

**职责**：从原始 HTTP 请求字节中解析目标地址，并将绝对 URL 重写为相对路径。职责单一，只做解析和重写，不做网络操作。

```java
/**
 * HTTP 请求解析与重写工具。
 * <p>
 * 从浏览器发送的 HTTP 代理请求中解析目标 host:port，
 * 并将绝对 URL 重写为目标服务器可接受的相对路径格式。
 * </p>
 */
public class HttpRequestParser {

    /**
     * 解析结果
     */
    public static class ParseResult {
        private final String host;      // 目标主机
        private final int port;         // 目标端口（默认 80）
        private final byte[] rewritten; // 重写后的完整 HTTP 请求字节
    }

    /**
     * 解析并重写 HTTP 请求。
     *
     * 输入示例：
     *   GET http://example.com/page?q=1 HTTP/1.1\r\n
     *   Host: example.com\r\n
     *   Accept: text/html\r\n
     *   \r\n
     *
     * 重写后输出：
     *   GET /page?q=1 HTTP/1.1\r\n
     *   Host: example.com\r\n
     *   Accept: text/html\r\n
     *   \r\n
     *
     * @param rawRequest 原始 HTTP 请求的完整字节
     * @return 解析结果，包含 host、port 和重写后的字节
     * @throws IllegalArgumentException 请求格式无法解析时抛出
     */
    public static ParseResult parse(byte[] rawRequest) { ... }
}
```

**解析逻辑**：

1. 从请求行中提取 URL 部分（`http://example.com:8080/path?q=1`）
2. 解析出 scheme、host、port（无端口则默认 80）、path+query（`/path?q=1`）
3. 将请求行中的绝对 URL 替换为相对路径（`GET /path?q=1 HTTP/1.1`）
4. 保留所有原始 header 和 body 不变
5. 返回包含 host、port、重写后字节的 ParseResult

**异常处理**：

- URL 不含 `http://` 前缀 → 尝试从 Host 头解析
- 无法解析出目标地址 → 抛出 IllegalArgumentException，由调用方返回 400

### 4.2 新增：HttpProxyRelayHandler

**位置**：`proxy-local/src/main/java/com/proxy/local/handler/HttpProxyRelayHandler.java`

**职责**：HTTP 普通代理模式下的数据中继处理器。与 CONNECT 隧道的 `RelayHandler` 类似，但需要处理首条请求的特殊转发。

```java
/**
 * HTTP 普通代理中继处理器。
 * <p>
 * 与 CONNECT 隧道的 {@link RelayHandler} 不同：
 * <ul>
 *   <li>构造时接收已解析并重写的首条 HTTP 请求字节，建连成功后立即转发</li>
 *   <li>不发送 "200 Connection Established"，目标服务器的响应直接透传给浏览器</li>
 *   <li>后续 keep-alive 请求直接透传，不再解析</li>
 * </ul>
 */
public class HttpProxyRelayHandler extends ChannelInboundHandlerAdapter {

    private final Invoker invoker;
    private final String targetHost;
    private final int targetPort;
    private final long streamId;
    private final byte[] initialRequest;  // 重写后的首条请求

    /**
     * @param initialRequest 经 HttpRequestParser 重写后的首条 HTTP 请求字节，
     *                       建连成功后必须首先发送此数据，否则目标服务器不会返回响应
     */
    public HttpProxyRelayHandler(Invoker invoker, String targetHost, int targetPort,
                                  long streamId, byte[] initialRequest) { ... }
}
```

**生命周期**：

1. **构造**：接收 invoker、目标地址、streamId 和重写后的首条请求字节
2. **handlerAdded / channelActive**：通过 invoker 将 `initialRequest` 作为第一个 DATA 帧发送到远程
3. **channelRead**：后续浏览器发来的数据直接作为 DATA 帧透传（与 RelayHandler 逻辑一致）
4. **channelInactive**：发送 DISCONNECT 通知，注销 streamId（与 RelayHandler 逻辑一致）

### 4.3 修改：HttpConnectHandler

**位置**：`proxy-local/src/main/java/com/proxy/local/handler/HttpConnectHandler.java`

**改动范围**：仅修改 `decode()` 方法中非 CONNECT 的分支（第 149~155 行），不影响现有 CONNECT 逻辑。

**当前代码**（将被替换）：

```java
if (parts.length < 3 || !"CONNECT".equalsIgnoreCase(parts[0])) {
    log.warn("Non-CONNECT HTTP request not supported: {}", requestLine);
    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
    ctx.close();
    return;
}
```

**修改后逻辑**：

```java
if (parts.length < 3) {
    // 请求行格式非法
    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
    ctx.close();
    return;
}

if (!"CONNECT".equalsIgnoreCase(parts[0])) {
    // ====== HTTP 普通代理模式 ======
    handlePlainHttpProxy(ctx, headerBytes);
    return;
}

// ====== 以下为原有 CONNECT 隧道逻辑（不变）======
```

**新增 `handlePlainHttpProxy` 方法**：

```java
/**
 * 处理 HTTP 普通代理请求（GET/POST/PUT 等非 CONNECT 方法）。
 * <p>
 * 核心流程：
 * 1. 用 HttpRequestParser 从请求中解析目标 host:port，并重写绝对 URL
 * 2. 通过路由规则判断走代理还是直连
 * 3. 建连成功后，将重写后的首条请求立即转发（不回 200）
 * 4. 切换到 HttpProxyRelayHandler 或 DirectRelayHandler 继续透传
 * </p>
 */
private void handlePlainHttpProxy(ChannelHandlerContext ctx, byte[] rawRequest) {
    // 1. 解析并重写
    HttpRequestParser.ParseResult result;
    try {
        result = HttpRequestParser.parse(rawRequest);
    } catch (IllegalArgumentException e) {
        log.warn("Failed to parse HTTP proxy request: {}", e.getMessage());
        ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
        ctx.close();
        return;
    }

    String host = result.getHost();
    int port = result.getPort();
    byte[] rewritten = result.getRewritten();

    log.info("HTTP plain proxy request: {}:{} from {}", host, port, ctx.channel().remoteAddress());

    // 2. 路由判断
    if (routeRule != null && !routeRule.shouldProxy(host)) {
        // 直连模式 —— 与 CONNECT 直连逻辑类似，但首条数据需立即发送
        handleDirectPlainProxy(ctx, host, port, rewritten);
    } else {
        // 代理模式 —— 通过 HTTP/2 隧道转发
        handleRemotePlainProxy(ctx, host, port, rewritten);
    }
}
```

### 4.4 直连模式的处理

直连模式下需要对 `DirectRelayHandler` 做微调：建连成功后需要把首条请求字节立即写入 outbound channel。

方案有两个选择：

- **方案 A**：在 `DirectRelayHandler` 中新增一个接受 `initialData` 的构造函数或方法，建连成功后自动发送
- **方案 B**：在 `HttpConnectHandler.handleDirectPlainProxy()` 的连接成功回调中，手动将首条数据写入

推荐**方案 B**，不修改 DirectRelayHandler 已有逻辑，改动最小：

```java
private void handleDirectPlainProxy(ChannelHandlerContext ctx, String host, int port, byte[] rewritten) {
    DirectRelayHandler directHandler = new DirectRelayHandler(host, port);
    directHandler.connect(ctx).addListener(future -> {
        if (future.isSuccess()) {
            // 不回 200！直接切换到 relay 模式
            ctx.pipeline().addLast("direct-relay", directHandler);
            ctx.pipeline().remove(HttpConnectHandler.this);
            // 首条请求立即发送给目标服务器
            ctx.fireChannelRead(Unpooled.wrappedBuffer(rewritten));
            log.info("HTTP plain proxy direct tunnel established: {}:{}", host, port);
        } else {
            log.debug("HTTP plain proxy direct connection failed for {}:{}", host, port);
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
            ctx.close();
        }
    });
}
```

### 4.5 代理模式的处理

```java
private void handleRemotePlainProxy(ChannelHandlerContext ctx, String host, int port, byte[] rewritten) {
    final long streamId = streamRegistry.nextStreamId();
    streamRegistry.register(streamId, ctx);

    // 先通过 CONNECT 在远端建立到目标的 TCP 连接
    Invocation connectInv = new Invocation(host, port, null, ProxyMessage.MessageType.CONNECT);
    connectInv.setAttachment("streamId", streamId);

    invoker.invoke(connectInv).whenComplete((response, throwable) -> {
        if (throwable != null) {
            log.error("HTTP plain proxy CONNECT failed for {}:{}", host, port, throwable);
            streamRegistry.unregister(streamId);
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
            ctx.close();
            return;
        }

        if (response != null && response.isSuccess()) {
            // 不回 200！切换到 HttpProxyRelayHandler
            ctx.pipeline().addLast("http-proxy-relay",
                    new HttpProxyRelayHandler(invoker, host, port, streamId, rewritten));
            ctx.pipeline().remove(HttpConnectHandler.this);

            log.info("HTTP plain proxy tunnel established: {}:{}, streamId={}", host, port, streamId);
        } else {
            String errMsg = response != null ? response.getErrorMessage() : "unknown error";
            log.warn("HTTP plain proxy CONNECT rejected for {}:{}: {}", host, port, errMsg);
            streamRegistry.unregister(streamId);
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
            ctx.close();
        }
    });
}
```

## 5. Pipeline 变化对比

### CONNECT 隧道模式（现有，不变）

```
ProtocolDetector
    ↓ 检测到 HTTP 方法首字节
HttpConnectHandler
    ↓ 解析 CONNECT，建隧道成功，回复 200
RelayHandler（透传所有字节）
```

### HTTP 普通代理模式（新增）

```
ProtocolDetector
    ↓ 检测到 HTTP 方法首字节（如 'G' for GET）
HttpConnectHandler
    ↓ 解析到非 CONNECT 方法，走 handlePlainHttpProxy 分支
    ↓ HttpRequestParser 解析目标地址并重写 URL
    ↓ 建连成功，不回 200，立即发送重写后的首条请求
HttpProxyRelayHandler 或 DirectRelayHandler（后续字节透传）
```

## 6. URL 重写规则详解

### 输入输出示例

| 浏览器发给代理的请求行 | 重写后发给目标服务器的请求行 |
|---|---|
| `GET http://example.com/ HTTP/1.1` | `GET / HTTP/1.1` |
| `GET http://example.com/page?q=hello HTTP/1.1` | `GET /page?q=hello HTTP/1.1` |
| `POST http://example.com:8080/api/data HTTP/1.1` | `POST /api/data HTTP/1.1` |
| `GET http://example.com HTTP/1.1` | `GET / HTTP/1.1` |

### 重写规则

1. 从请求行第二个字段提取绝对 URL
2. 去掉 `http://host[:port]` 前缀，保留从路径开始的部分
3. 如果 URL 中没有路径部分（如 `http://example.com`），重写为 `/`
4. query string 和 fragment 保持不变
5. 请求头和请求体完全不动，原样保留

### 端口解析规则

- `http://example.com/path` → host=example.com, port=80
- `http://example.com:8080/path` → host=example.com, port=8080
- 无法从 URL 解析时，fallback 到 Host 头

## 7. 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `HttpRequestParser.java` | **新增** | HTTP 请求解析与 URL 重写工具类 |
| `HttpProxyRelayHandler.java` | **新增** | HTTP 普通代理专用中继处理器，处理首条请求转发 |
| `HttpConnectHandler.java` | **修改** | 非 CONNECT 分支改为调用 handlePlainHttpProxy |
| `ProtocolDetector.java` | 不变 | 已支持 GET/POST 等首字节检测，无需修改 |
| `RelayHandler.java` | 不变 | CONNECT 隧道模式继续使用 |
| `DirectRelayHandler.java` | 不变 | 直连模式继续使用，首条数据通过 fireChannelRead 注入 |
| `RouteRule.java` | 不变 | 路由判断逻辑通用，HTTP 普通代理同样适用 |

## 8. 风险与注意事项

### 8.1 首条请求数据不可丢失

这是本方案最关键的点。HTTP 普通代理的第一条请求既包含目标地址信息，也是需要传达给服务器的业务数据。处理流程中必须确保：

- 解析完目标地址后，重写后的请求字节被完整保留
- 建连成功后，首条请求作为第一个 DATA 帧发送
- 建连失败时，返回 `502 Bad Gateway`，浏览器会自行重试

### 8.2 ByteBuf 生命周期管理

`HttpConnectHandler` 继承 `ByteToMessageDecoder`，在 `decode()` 中通过 `in.readBytes(headerBytes)` 读取数据后，原始 ByteBuf 的引用计数由 decoder 管理。新建的 `byte[]` 需要在传递给 `HttpProxyRelayHandler` 后正确封装为 ByteBuf 并确保释放。

### 8.3 带 Body 的请求（POST/PUT）

当前 `decode()` 只读取到 `\r\n\r\n` 为止（即 header 部分）。对于 POST/PUT 等带 body 的请求，body 数据会在后续的 `channelRead` 中到达。由于切换到 `HttpProxyRelayHandler` 后就是纯透传模式，body 数据会自然地通过 `channelRead` → DATA 帧转发出去，不需要特殊处理。

但需要注意：`HttpRequestParser` 只需要处理 header 部分就够了，body 部分不经过解析器。

### 8.4 Proxy-Connection 头处理

部分浏览器会在发给代理的请求中添加 `Proxy-Connection: keep-alive` 头。这个头是浏览器和代理之间的约定，不应转发给目标服务器。`HttpRequestParser` 在重写时应移除此头（如果存在的话），避免目标服务器收到不认识的头产生困惑。

### 8.5 ProtocolDetector 兼容性

`ProtocolDetector.isHttpMethod()` 已经支持 GET(G)、POST(P)、PUT(P)、DELETE(D)、HEAD(H)、OPTIONS(O)、PATCH(P、已被 P 覆盖)、CONNECT(C) 的首字节检测，以及 TRACE(T)。HTTP 普通代理的请求（GET/POST 等）首字节与 CONNECT 共用同一个 `isHttpMethod` 判断，所以 `ProtocolDetector` 不需要任何修改。
