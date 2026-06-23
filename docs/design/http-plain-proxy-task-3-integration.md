# Task-3：HttpConnectHandler 改造与全链路集成测试

> **所属 SDD**：[http-plain-proxy-design.md](./http-plain-proxy-design.md)
> **任务编号**：Task-3 / 3
> **预计产出**：`HttpConnectHandler.java` 修改 + 全链路验证通过
> **前置依赖**：Task-1（HttpRequestParser）、Task-2（HttpProxyRelayHandler）
> **后续依赖**：无（本任务是最后一个）

---

## 1. 任务目标

改造 `HttpConnectHandler`，在其 `decode()` 方法的非 CONNECT 分支中接入 HTTP 普通代理逻辑，调用 Task-1 的 `HttpRequestParser` 解析请求，调用 Task-2 的 `HttpProxyRelayHandler` 做中继。改造完成后进行完整的全链路测试，验证 HTTP 普通代理从浏览器到目标服务器的全流程。

## 2. 文件位置

```
proxy-local/src/main/java/com/proxy/local/handler/HttpConnectHandler.java
```

## 3. 改造范围

**原则**：只改非 CONNECT 分支，现有 CONNECT 隧道逻辑一行不动。

### 3.1 当前代码（第 149~155 行）

```java
if (parts.length < 3 || !"CONNECT".equalsIgnoreCase(parts[0])) {
    // 不是 CONNECT 方法，暂不支持普通 HTTP 代理
    log.warn("Non-CONNECT HTTP request not supported: {}", requestLine);
    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
    ctx.close();
    return;
}
```

### 3.2 修改后逻辑

```java
// 请求行格式校验
if (parts.length < 3) {
    log.warn("Malformed HTTP request line: {}", requestLine);
    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
    ctx.close();
    return;
}

// 非 CONNECT → HTTP 普通代理模式
if (!"CONNECT".equalsIgnoreCase(parts[0])) {
    handlePlainHttpProxy(ctx, headerBytes);
    return;
}

// ====== 以下为原有 CONNECT 隧道逻辑（完全不变）======
```

### 3.3 新增方法

在 `HttpConnectHandler` 类中新增三个 private 方法：

```java
/**
 * 处理 HTTP 普通代理请求（GET/POST/PUT 等非 CONNECT 方法）。
 * <p>
 * 核心流程：
 * 1. 用 HttpRequestParser 解析目标 host:port 并重写 URL
 * 2. 路由判断：走代理还是直连
 * 3. 建连成功后立即转发重写后的首条请求（不回 200）
 * 4. 切换到 HttpProxyRelayHandler 或 DirectRelayHandler
 * </p>
 */
private void handlePlainHttpProxy(ChannelHandlerContext ctx, byte[] rawRequest) { ... }

/**
 * HTTP 普通代理 —— 直连模式。
 * 建连成功后立即通过 fireChannelRead 将首条请求注入 DirectRelayHandler。
 */
private void handleDirectPlainProxy(ChannelHandlerContext ctx,
                                     String host, int port, byte[] rewritten) { ... }

/**
 * HTTP 普通代理 —— 远程代理模式。
 * 先通过 CONNECT 在远端建立 TCP 连接，成功后切换到 HttpProxyRelayHandler
 * 由其在 handlerAdded 中发送首条请求。
 */
private void handleRemotePlainProxy(ChannelHandlerContext ctx,
                                     String host, int port, byte[] rewritten) { ... }
```

## 4. 实现思路

### 4.1 handlePlainHttpProxy 流程

```
handlePlainHttpProxy(ctx, rawRequest)
  │
  ├─ 1. HttpRequestParser.parse(rawRequest)
  │     成功 → 得到 ParseResult(host, port, rewrittenBytes)
  │     失败 → log.warn + 回复 400 Bad Request + close
  │
  ├─ 2. log.info("HTTP plain proxy request: {}:{}", host, port)
  │
  └─ 3. 路由判断
        ├─ routeRule != null && !routeRule.shouldProxy(host)
        │   → handleDirectPlainProxy(ctx, host, port, rewrittenBytes)
        │
        └─ else
            → handleRemotePlainProxy(ctx, host, port, rewrittenBytes)
```

### 4.2 handleDirectPlainProxy 流程

```
handleDirectPlainProxy(ctx, host, port, rewritten)
  │
  ├─ 1. new DirectRelayHandler(host, port)
  │
  ├─ 2. directHandler.connect(ctx).addListener(future -> {
  │       if (success) {
  │         // ★ 不回 200
  │         pipeline.addLast("direct-relay", directHandler)
  │         pipeline.remove(HttpConnectHandler.this)
  │         // ★ 首条请求通过 fireChannelRead 注入
  │         ctx.fireChannelRead(Unpooled.wrappedBuffer(rewritten))
  │       } else {
  │         // 回复 502 + close
  │       }
  │     })
```

**关键点**：

- `ctx.fireChannelRead(Unpooled.wrappedBuffer(rewritten))`：此时 pipeline 中 `HttpConnectHandler` 已被 remove，`DirectRelayHandler` 是当前 handler。`fireChannelRead` 会触发 `DirectRelayHandler.channelRead()`，由其将数据写入 outboundChannel 发给目标服务器。
- `Unpooled.wrappedBuffer(rewritten)` 创建的 ByteBuf 不涉及额外内存拷贝，`DirectRelayHandler.channelRead()` 会通过 `outboundChannel.writeAndFlush(msg)` 将其写出并释放。

### 4.3 handleRemotePlainProxy 流程

```
handleRemotePlainProxy(ctx, host, port, rewritten)
  │
  ├─ 1. streamId = streamRegistry.nextStreamId()
  │     streamRegistry.register(streamId, ctx)
  │
  ├─ 2. 构建 CONNECT Invocation，通过 invoker 发送
  │
  └─ 3. whenComplete:
        ├─ 异常 → unregister + 502 + close
        ├─ 成功 →
        │   // ★ 不回 200
        │   pipeline.addLast("http-proxy-relay",
        │       new HttpProxyRelayHandler(invoker, host, port, streamId, rewritten))
        │   pipeline.remove(HttpConnectHandler.this)
        │
        └─ 失败响应 → unregister + 502 + close
```

**关键点**：

- `HttpProxyRelayHandler` 在 `handlerAdded` 中会自动发送 `rewritten` 数据作为第一个 DATA 帧。
- 后续浏览器发来的 keep-alive 请求数据通过 `channelRead` 透传。
- 远端返回的数据通过 ExchangeHandler 的推送路径按 streamId 写回浏览器。

### 4.4 import 变更

新增：

```java
import com.proxy.local.handler.HttpRequestParser;
import io.netty.buffer.Unpooled;  // 已有
```

## 5. 不改什么

以下代码完全不动，明确列出以便 code review：

| 代码区域 | 行号范围 | 说明 |
|---------|---------|------|
| 类声明、常量 | 93~111 | CONNECT_RESPONSE、BAD_GATEWAY 等不变 |
| 构造函数 | 113~116 | 不变 |
| decode() 前半段 | 119~147 | header 大小检查、\r\n\r\n 搜索、header 读取不变 |
| CONNECT 分支 | ~155 之后 | host:port 解析、路由判断、直连/代理逻辑全部不变 |
| findHeaderEnd() | 248~260 | 不变 |
| exceptionCaught() | 262~266 | 不变 |

## 6. 测试方案

### 6.1 编译验证

```bash
cd /Users/zhanghonghao/Desktop/SimplePlanePlatform
mvn compile -pl proxy-local
```

### 6.2 回归测试：CONNECT 隧道不受影响

改造后首先确认现有功能正常。

**测试步骤**：

1. 启动 proxy-remote（远程服务器）
2. 启动 proxy-local（本地代理）
3. 配置浏览器使用 HTTP 代理（指向 proxy-local 监听端口）
4. 浏览器访问 `https://www.google.com`

**预期结果**：

- proxy-local 日志出现 `HTTP CONNECT request: www.google.com:443`
- 页面正常加载
- 与改造前行为完全一致

**关注点**：

- 确认走的是 CONNECT 分支，不是新增的 handlePlainHttpProxy 分支
- 日志中不应出现 `HTTP plain proxy request`

### 6.3 功能测试：HTTP 普通代理（代理模式）

**前提**：路由规则中目标域名走代理（非直连）。

#### TC-1：curl 基础测试

```bash
# 通过代理访问 HTTP 网站
curl -x http://127.0.0.1:<proxy-port> http://httpbin.org/get
```

**预期结果**：

- proxy-local 日志出现 `HTTP plain proxy request: httpbin.org:80`
- curl 收到 httpbin.org 返回的 JSON 响应
- 响应中不包含 `200 Connection Established`（不应该出现代理握手响应）
- 响应直接是目标服务器的原始 HTTP 响应

**验证要点**：

- `HttpRequestParser` 正确解析出 host=httpbin.org, port=80
- URL 从 `http://httpbin.org/get` 重写为 `/get`
- 首条请求通过 HttpProxyRelayHandler 成功发送
- 目标服务器的响应原样返回给 curl

#### TC-2：带 query string

```bash
curl -x http://127.0.0.1:<proxy-port> "http://httpbin.org/get?name=test&lang=zh"
```

**预期**：响应 JSON 中 `args` 字段包含 `{"name": "test", "lang": "zh"}`

#### TC-3：POST 请求（带 body）

```bash
curl -x http://127.0.0.1:<proxy-port> -X POST \
     -H "Content-Type: application/json" \
     -d '{"key":"value"}' \
     http://httpbin.org/post
```

**预期**：

- 响应 JSON 中 `json` 字段包含 `{"key": "value"}`
- 验证 POST body 没有丢失（body 通过后续 channelRead 透传）

#### TC-4：自定义端口

```bash
curl -x http://127.0.0.1:<proxy-port> http://httpbin.org:80/get
```

**预期**：正常返回，端口解析正确

#### TC-5：Proxy-Connection 头不转发

```bash
curl -x http://127.0.0.1:<proxy-port> \
     -H "Proxy-Connection: keep-alive" \
     http://httpbin.org/headers
```

**预期**：响应 JSON 的 `headers` 中**不包含** `Proxy-Connection`

### 6.4 功能测试：HTTP 普通代理（直连模式）

**前提**：路由规则中目标域名走直连。

#### TC-6：直连 GET 请求

1. 确保路由规则中 `httpbin.org` 走直连（添加到 directList）
2. 执行：

```bash
curl -x http://127.0.0.1:<proxy-port> http://httpbin.org/get
```

**预期**：

- proxy-local 日志出现 `Route DIRECT` 和 `HTTP plain proxy direct tunnel established`
- 响应正常返回
- 不经过 proxy-remote

#### TC-7：直连 POST 请求

```bash
curl -x http://127.0.0.1:<proxy-port> -X POST \
     -d "data=hello" \
     http://httpbin.org/post
```

**预期**：响应中包含 `data=hello`

### 6.5 异常测试

#### TC-8：目标服务器不可达

```bash
curl -x http://127.0.0.1:<proxy-port> http://nonexistent.invalid/path
```

**预期**：

- curl 收到 `502 Bad Gateway`
- proxy-local 日志记录连接失败
- 连接被正常关闭，无资源泄漏

#### TC-9：畸形请求

```bash
echo -ne "INVALID\r\n\r\n" | nc 127.0.0.1 <proxy-port>
```

**预期**：

- 收到 `400 Bad Request`
- 连接被关闭

#### TC-10：HTTPS 走 CONNECT 不受影响

```bash
curl -x http://127.0.0.1:<proxy-port> https://httpbin.org/get
```

**预期**：

- 走 CONNECT 隧道（因为是 HTTPS）
- 日志出现 `HTTP CONNECT request: httpbin.org:443`
- 响应正常

### 6.6 浏览器全链路测试

#### TC-11：浏览器访问 HTTP 网站

1. 配置系统/浏览器 HTTP 代理指向 proxy-local
2. 浏览器访问 `http://httpbin.org/`

**预期**：

- 页面正常渲染
- proxy-local 日志出现 `HTTP plain proxy request: httpbin.org:80`
- 页面中的子资源（CSS、JS、图片）也能正常加载

#### TC-12：混合访问（HTTP + HTTPS）

1. 浏览器访问 `http://httpbin.org/`（HTTP → 走普通代理）
2. 同一浏览器访问 `https://www.google.com`（HTTPS → 走 CONNECT 隧道）

**预期**：

- 两种模式互不干扰
- HTTP 走 handlePlainHttpProxy 分支
- HTTPS 走原有 CONNECT 分支

## 7. 全链路数据流验证

用 TC-1 的 curl 命令为例，端到端验证每一跳的数据：

```
步骤 1: curl 发送
  → "GET http://httpbin.org/get HTTP/1.1\r\nHost: httpbin.org\r\n...\r\n\r\n"
  → TCP 连接到 proxy-local

步骤 2: proxy-local HttpConnectHandler.decode()
  → 检测到 GET（非 CONNECT）
  → 调用 handlePlainHttpProxy()
  → HttpRequestParser.parse() 返回:
    host=httpbin.org, port=80
    rewritten="GET /get HTTP/1.1\r\nHost: httpbin.org\r\n...\r\n\r\n"
  → 日志: "HTTP plain proxy request: httpbin.org:80"

步骤 3: handleRemotePlainProxy()
  → streamId = nextStreamId()
  → 发送 CONNECT Invocation 到 proxy-remote
  → proxy-remote 与 httpbin.org:80 建立 TCP 连接
  → CONNECT_RESPONSE 返回成功

步骤 4: pipeline 切换
  → addLast(HttpProxyRelayHandler)
  → remove(HttpConnectHandler)
  → 日志: "HTTP plain proxy tunnel established"

步骤 5: HttpProxyRelayHandler.handlerAdded()
  → sendData(rewritten)  // 首条请求作为 DATA 帧发出
  → initialRequest = null

步骤 6: proxy-remote 收到 DATA
  → forward 到 httpbin.org outbound TCP 连接
  → httpbin.org 收到 "GET /get HTTP/1.1\r\n..."

步骤 7: httpbin.org 返回响应
  → proxy-remote 通过 push(streamId) 推送回 proxy-local
  → ExchangeHandler.handlePush() 按 streamId 查找浏览器 ctx
  → 写回 curl

步骤 8: curl 打印响应
  → HTTP/1.1 200 OK
  → { "args": {}, "headers": {...}, "origin": "...", "url": "http://httpbin.org/get" }
```

## 8. 日志检查清单

测试通过后，检查 proxy-local 日志中以下关键行是否出现：

| 日志内容 | 来源 | 含义 |
|---------|------|------|
| `HTTP plain proxy request: xxx:80` | handlePlainHttpProxy | 进入了 HTTP 普通代理分支 |
| `Route PROXY: xxx:80` 或 `Route DIRECT: xxx:80` | handlePlainHttpProxy | 路由判断正确 |
| `HTTP plain proxy tunnel established: xxx:80, streamId=xxx` | handleRemotePlainProxy | 代理模式建连成功 |
| `HTTP plain proxy direct tunnel established: xxx:80` | handleDirectPlainProxy | 直连模式建连成功 |

同时确认**不出现**：

| 不应出现的日志 | 含义 |
|-------------|------|
| `Non-CONNECT HTTP request not supported` | 旧的拒绝逻辑（应已被替换） |
| ByteBuf LEAK | 内存泄漏 |

## 9. 完成标准

- [ ] `HttpConnectHandler.java` 修改完成，非 CONNECT 分支调用 handlePlainHttpProxy
- [ ] 原有 CONNECT 逻辑一行不动
- [ ] `mvn compile -pl proxy-local` 编译通过
- [ ] TC-1 ~ TC-5：代理模式功能测试全部通过
- [ ] TC-6 ~ TC-7：直连模式功能测试通过
- [ ] TC-8 ~ TC-9：异常场景测试通过
- [ ] TC-10：HTTPS 回归测试通过（CONNECT 隧道不受影响）
- [ ] TC-11 ~ TC-12：浏览器全链路测试通过
- [ ] 日志检查清单通过
- [ ] 代码包含完整 Javadoc 注释

## 10. 不做什么

- 不修改 `HttpRequestParser.java`（Task-1 已完成）
- 不修改 `HttpProxyRelayHandler.java`（Task-2 已完成）
- 不修改 `RelayHandler.java`、`DirectRelayHandler.java`、`ProtocolDetector.java`、`RouteRule.java`
- 不修改 proxy-remote 侧代码（远端无需任何改动，CONNECT 和 DATA 的处理逻辑通用）
