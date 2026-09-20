# QUIC / HTTP/3 SPI 最小改造设计（V1）

> 状态：评审优化稿（待实施）
> 阶段目标：保持现有 Invoker、Exchange 和 ProxyMessage 协议不变，仅替换 Client/Server 传输实现
> 闭环范围：Java `proxy-local → HTTP/3 → proxy-remote → target` 及完整回程
> 核心原则：先用最小改造验证 QUIC 是否解决当前跨 Stream 队头阻塞，再决定是否演进协议

---

## 1. 结论

本阶段不重构整个代理架构，也不引入新的 `TunnelSession` 抽象。

沿用现有调用链：

```text
Invoker
  → ClientInvoker
  → HeaderExchangeClient
  → Client
  → Transport Pipeline
  → Remote
```

通过 SPI 增加一套 HTTP/3 实现：

```text
Transporter
  ├─ NettyTransporter / NettyClient / NettyServer     现有 HTTP/2
  └─ Http3Transporter / Http3Client / Http3Server     新增 HTTP/3
```

上层继续传递现有 `ProxyMessage`：

```text
Invocation
  → ProxyMessage
  → HTTP/3 DATA Frame
  → ProxyMessage
  → Invocation
```

本阶段明确不做：

- 不改成标准 HTTP/3 CONNECT 隧道协议；
- 不移除 `ProxyMessage`；
- 不重构 Cluster、Exchange、Filter、Dispatch 和 Outbound；
- 不实现 Android/Rust HTTP/3；
- 不实现 HTTP/3 失败后自动回退 HTTP/2；
- 不启用 0-RTT、Connection Migration、MASQUE；
- 不重做认证、配置中心和监控平台。

HTTP/2 与 HTTP/3 通过配置手动切换。HTTP/3 出现问题时，将配置改回 `http2` 并重启，即可完成回滚。

---

## 2. 为什么只改 Client/Server 可行

当前架构已经把传输层抽象为：

```java
public interface Transporter {
    Client connect(URL url, MessageHandler handler);
    Server bind(URL url, MessageHandler handler);
}
```

`ClientInvoker` 负责把 `Invocation` 转换为 `ProxyMessage`，`HeaderExchangeClient` 负责 requestId、Future 和推送回调，最终只调用：

```java
client.send(proxyMessage);
```

当前代码的接收方向不是普通 POJO 回调。`ExchangeHandler` 同时实现了
`MessageHandler` 和 Netty `ChannelHandler`，但 `onMessage()` 只是兼容接口的空实现；
真实业务入口是它挂在每条 Stream Pipeline 末端后执行的：

```java
ExchangeHandler.channelRead0(ChannelHandlerContext ctx, ProxyMessage message)
```

这里的 `ChannelHandlerContext` 还会作为 `inboundCtx` 进入服务端 Invocation，供 CONNECT
响应、目标站点回包和异常关闭沿原 Stream 写回。因此 V1 不修改 Exchange 接口，但必须承认
当前传输层与 Netty Handler 存在这一处既有耦合，HTTP/3 Client/Server 都要延续相同接入方式。

因此 HTTP/3 实现必须满足以下契约，上层才不需要感知底层协议：

1. `send(ProxyMessage)` 能把消息可靠、有序地写入对应逻辑 Stream；
2. 收到完整消息后，通过当前 Stream Pipeline 进入现有 `ExchangeHandler.channelRead0()`；
3. 同一个 `streamId` 始终映射到同一个 HTTP/3 Stream；
4. CONNECT/DATA/DISCONNECT、requestId 和 push 语义保持不变；
5. `closeStream()`、`isAvailable()`、`getActiveStreamCount()` 行为与 HTTP/2 一致。

`Http3Transporter.connect()/bind()` 应与现有 `NettyTransporter` 一样，在启动时校验传入的
`MessageHandler` 同时实现 `ChannelHandler`；不满足时立即报错，禁止退化为调用空的
`MessageHandler.onMessage()`。

所以真正需要替换的是 HTTP/2 Frame Pipeline，不是 Invoker 和 Exchange 模型。

---

## 3. 当前链路与改造后链路

### 3.1 当前 HTTP/2 链路

```text
SOCKS5 / HTTP CONNECT / TUN
        │
        ▼
proxy-local Handler
        │ Invocation
        ▼
ClusterInvoker
        ▼
ClientInvoker
        │ ProxyMessage
        ▼
HeaderExchangeClient
        ▼
NettyClient
        ▼
HTTP/2 Stream ── 单条 TCP ── HTTP/2 Stream
                                   ▼
                              NettyServer
                                   ▼
                           ExchangeHandler
                                   ▼
                           DispatchInvoker
                                   ▼
                           OutboundConnector
                                   ▼
                               目标站点
```

### 3.2 V1 HTTP/3 链路

```text
SOCKS5 / HTTP CONNECT / TUN
        │
        ▼
proxy-local Handler
        │ Invocation
        ▼
ClusterInvoker                         不变
        ▼
ClientInvoker                          不变
        │ ProxyMessage
        ▼
HeaderExchangeClient                   不变
        ▼
Http3Client                            新增
        ▼
HTTP/3 Request Stream ─ QUIC/UDP ─ HTTP/3 Request Stream
                                          ▼
                                     Http3Server       新增
                                          ▼
                                  ExchangeHandler      不变
                                          ▼
                                  DispatchInvoker      不变
                                          ▼
                                  OutboundConnector    不变
                                          ▼
                                      目标站点
```

回程沿相同 HTTP/3 Stream 反向返回：

```text
目标站点
  → OutboundHandler
  → ExchangeHandler push
  → Http3Server Stream
  → Http3Client Stream
  → ExchangeHandler.handlePush()
  → StreamChannelRegistry
  → 浏览器 / TUN
```

至此形成 V1 要求的完整双向闭环。

---

## 4. 改造范围

### 4.1 保持不变

以下模块和语义本阶段不修改：

- `Invocation`、`Response`；
- `ProxyMessage`、`ProxyCodec`；
- `ClientInvoker`；
- `HeaderExchangeClient`、`DefaultFuture`；
- `ExchangeHandler`；
- Cluster 容错和负载均衡主体；
- Filter 链；
- `DispatchInvoker`；
- `SessionManager`、`OutboundSession`；
- `OutboundConnector`、`OutboundHandler`；
- SOCKS5、HTTP CONNECT、TUN 入口；
- FakeDNS 和路由规则。

### 4.2 少量修改

- `HeaderExchanger`：不再永远加载默认 Transporter，改为按 URL 参数选择；
- `ProxyLocalServer`：把远端节点的 `transport` 配置写入 URL；
- `RemoteConfig`：读取服务端 `transport` 和 HTTP/3 参数；
- 根 `pom.xml`：增加 HTTP/3 模块和统一 Netty 版本；
- Docker、启动脚本、桌面资源收集：补充 UDP、证书和 QUIC native 库。

### 4.3 新增模块

```text
proxy-transport-http3/
├─ pom.xml
├─ src/main/resources/META-INF/proxy/
│  └─ com.proxy.common.transport.Transporter
└─ src/main/java/com/proxy/transport/http3/
   ├─ Http3Transporter.java
   ├─ Http3Client.java
   ├─ Http3Server.java
   ├─ Http3Connection.java
   ├─ Http3StreamState.java
   └─ handler/
      ├─ Http3ProxyMessageEncoder.java
      ├─ Http3ProxyMessageDecoder.java
      ├─ Http3CipherEncodeHandler.java
      ├─ Http3CipherDecodeHandler.java
      └─ Http3StreamHandler.java
```

---

## 5. SPI 改造

### 5.1 注册

HTTP/2 保留现有注册，同时增加更清晰的别名：

```properties
netty=com.proxy.transport.netty.NettyTransporter
http2=com.proxy.transport.netty.NettyTransporter
```

HTTP/3 模块注册：

```properties
http3=com.proxy.transport.http3.Http3Transporter
```

### 5.2 Transporter 选择

当前 `HeaderExchanger` 使用：

```java
Transporter transporter = ExtensionLoader
        .getLoader(Transporter.class)
        .getDefaultExtension();
```

修改为：

```java
private Transporter getTransporter(URL url) {
    String name = url.getParameter("transport", "http2");
    return ExtensionLoader
            .getLoader(Transporter.class)
            .getExtension(name);
}
```

`connect()` 和 `bind()` 都调用该方法，确保客户端和服务端使用相同选择逻辑。

### 5.3 默认行为

- 未配置 `transport` 时默认 `http2`；
- 旧名称 `netty` 继续可用；
- 本阶段不提供 `auto`；
- 配置了未知名称时启动失败，禁止静默回退；
- 客户端与服务端协议不一致时快速报错并输出明确日志。

---

## 6. 配置设计

### 6.1 proxy-local

```yaml
localPort: 1080

remoteServers:
  - host: proxy.example.com
    port: 9090
    transport: http3
    ssl: true
    cipher: none
    cipherKey: ""

    http3:
      serverName: proxy.example.com
      caFile: ""
      certificatePin: ""
      handshakeTimeoutMs: 5000
      idleTimeoutMs: 60000
      maxStreams: 1000
      initialConnectionWindow: 16777216
      initialStreamWindow: 1048576
      maxProxyMessageBytes: 8388608
      streamPendingHardLimit: 4194304
      connectionPendingHardLimit: 67108864
      writeBufferLowWaterMark: 262144
      writeBufferHighWaterMark: 1048576

# V1 约束：必须为 1
connectionsPerNode: 1
```

### 6.2 proxy-remote

```yaml
host: 0.0.0.0
port: 9090
transport: http3

cipher: none
cipherKey: ""

http3:
  certificateFile: /etc/simpleplane/tls/fullchain.pem
  privateKeyFile: /etc/simpleplane/tls/privkey.pem
  idleTimeoutMs: 60000
  maxConnections: 10000
  maxStreamsPerConnection: 1000
  initialConnectionWindow: 16777216
  initialStreamWindow: 1048576
  maxProxyMessageBytes: 8388608
  streamPendingHardLimit: 4194304
  connectionPendingHardLimit: 67108864
  writeBufferLowWaterMark: 262144
  writeBufferHighWaterMark: 1048576
```

### 6.3 V1 配置约束

为避免把 Cluster 会话绑定重构带入本阶段，V1 强制：

- `remoteServers` 只能配置一个 HTTP/3 节点；
- `connectionsPerNode` 必须为 `1`；
- 活动会话不执行跨节点 failover；
- HTTP/3 Client 断开时，其上所有代理会话关闭；
- 重新建立 QUIC Connection 只服务新会话，不迁移旧会话。

配置校验不满足时直接拒绝启动，并提示这是 V1 限制。

这是阶段性边界，不是最终架构限制。验证 QUIC 收益后，再单独设计多节点会话粘滞。

---

## 7. HTTP/3 承载协议

### 7.1 为什么继续使用 ProxyMessage

本阶段的目的只有一个：验证把 HTTP/2/TCP 换成 HTTP/3/QUIC 后，跨 Stream 队头阻塞是否显著改善。

继续使用 `ProxyMessage` 可以保持以下语义全部不变：

- CONNECT 请求和响应；
- DATA 推送；
- DISCONNECT；
- HEARTBEAT；
- requestId/Future；
- streamId 路由；
- Cipher SPI；
- Java/Rust 已有协议测试向量。

因此 V1 不使用标准 HTTP/3 CONNECT，而是复刻现有 HTTP/2 `/proxy` 长流模型。

### 7.2 Stream 建立

每个应用 `streamId` 对应一个 HTTP/3 request stream。

第一次向该 Stream 发送消息时，客户端先发送：

```text
:method = POST
:scheme = https
:authority = <proxy-server-host>:<proxy-server-port>
:path = /proxy
content-type = application/octet-stream
x-plane-protocol = proxy-message-v1
```

然后在该 request stream 上持续发送 HTTP/3 DATA Frame。

服务端收到合法 Headers 后返回：

```text
:status = 200
content-type = application/octet-stream
x-plane-protocol = proxy-message-v1
```

响应 DATA Frame 用于把 `ProxyMessage` 推回客户端。

### 7.3 ProxyMessage 映射

发送侧默认采用“一条消息一次 write”的简单策略：

```text
一个 ProxyMessage
  → ProxyCodec.encode()
  → 可选 Cipher.encrypt()
  → 写入 HTTP/3 request stream
```

但“一条消息对应一个 HTTP/3 DATA Frame”不是协议契约。接收侧必须把 DATA 当作有序字节流，
继续根据 `ProxyCodec` 的固定头和 data length 做累积解码，同时允许：

- 一条 `ProxyMessage` 跨多个 DATA Frame；
- 一个 DATA Frame 包含多条 `ProxyMessage`；
- Cipher 密文块跨 Frame，或同一 Frame 中存在多个完整密文块。

接收方向为：

```text
HTTP/3 DATA Frame(s)
  → 字节累积与长度校验
  → 可选 Cipher 分块解密
  → ProxyCodec 循环解码
  → ExchangeHandler
```

发送端可以在当前 Netty 版本中尽量保持一条消息一个 DATA Frame，以减少初版变量；接收端不得依赖
该行为。QUIC packet、HTTP/3 DATA Frame、Cipher block 和 ProxyMessage 是四层不同边界，任何两层
都不建立一一对应关系。

### 7.4 Stream 生命周期

| ProxyMessage | HTTP/3 Stream 动作 |
|---|---|
| CONNECT | 首条 DATA，Stream 保持打开 |
| CONNECT response | 响应方向 DATA |
| DATA | 同一 Stream 上继续发送 DATA |
| DISCONNECT | 发送消息，响应完成后关闭 Stream |
| HEARTBEAT | 保持现有业务语义 |
| Stream 局部异常 | 仅关闭当前 Stream，精准失败该 Stream 的请求/会话 |
| QUIC Connection 异常 | 关闭其上全部 Stream，通知 `MessageHandler.onDisconnected` |

V1 不新增半关闭语义，完全沿用现有 DISCONNECT 行为。

特别约束：单 Stream 解码失败、pending 溢出或 RESET 不得调用当前全局
`MessageHandler.onError()`，因为客户端 `ExchangeHandler.onError()` 会执行 `DefaultFuture.failAll()`，
从而误伤同一 QUIC Connection 上的其他健康 Stream。只有确定为 Connection 级故障时才允许全局失败。

---

## 8. Http3Client 设计

### 8.1 接口实现

```java
public final class Http3Client implements Client {
    private final URL url;
    private final MessageHandler messageHandler;
    private final Http3Connection connection;

    private final ConcurrentHashMap<Long, Http3StreamState> streams =
            new ConcurrentHashMap<>();

    @Override
    public void send(ProxyMessage message);

    @Override
    public void closeStream(long streamId);

    @Override
    public boolean isAvailable();

    @Override
    public int getActiveStreamCount();

    @Override
    public void close();
}
```

### 8.2 send 流程

```text
send(message)
  → 检查 QUIC Connection 可用
  → streams.computeIfAbsent(message.streamId, openHttp3Stream)
  → Stream 未建立：消息进入有界 pending queue
  → request stream 创建成功并写出请求 Headers
  → 收到合法 200 response Headers：标记 Stream READY，按顺序 flush pending
  → Stream READY 且可写：writeAndFlush(message)
  → Stream READY 但不可写：进入该 Stream 的有界 pending queue
```

服务端必须在校验请求 Headers 后立即返回 200，不等待首条 CONNECT DATA，避免双方互等。等待 200
会给新会话增加一个 RTT，但能在发送业务数据前确认协议和路径有效，V1 接受该取舍。后续只有基准证明
该 RTT 成为问题时，才考虑乐观发送首条 DATA。

### 8.3 StreamState

```java
final class Http3StreamState {
    volatile Channel channel;
    final Deque<ProxyMessage> pending = new ArrayDeque<>();
    StreamPhase phase; // OPENING / WAITING_RESPONSE / READY / FAILED / CLOSED
    long pendingBytes;
}
```

限制：

- `pending` 最大消息数；
- `pendingBytes` 最大字节数；
- 同一 Connection 还要维护聚合 `connectionPendingBytes` 上限；
- 超限时只失败并关闭当前 Stream，不调用全局 `onError()`；
- Stream 建立成功后按入队顺序发送；
- Stream 关闭后从 `streams` 删除；
- QUIC Connection 关闭后清理全部 StreamState。

`ArrayDeque`、`phase`、`pendingBytes` 和 Connection 聚合计数必须全部在所属 QUIC EventLoop 上串行修改；
外部线程调用 `send()` 时先投递到 EventLoop。若实现选择加锁而不是线程封闭，则建流回调、发送、关闭、
writability 回调和超时回调必须使用同一把状态锁，不能只保护入队路径。

### 8.4 连接重建

V1 可以复用现有指数退避思想：

- QUIC Connection 断开后通知 `messageHandler.onDisconnected()`；
- 已有 Stream 全部失败并清理；
- 后台指数退避重建 Connection；
- `isAvailable()` 在重建期间返回 false；
- 重建成功只接受新代理会话；
- 旧会话不重放、不迁移。

---

## 9. Http3Server 设计

`Http3Transporter.bind()` 创建并启动 `Http3Server`。

职责：

1. 创建 UDP Datagram Channel；
2. 初始化 QUIC Server codec、TLS 1.3 和 ALPN `h3`；
3. 接受 QUIC Connection；
4. 接受 HTTP/3 request stream；
5. 校验 `POST /proxy` 和协议 Header；
6. 返回 HTTP/3 200 Headers；
7. 在 Stream Pipeline 安装解密、解码和现有 `ExchangeHandler`；
8. 统计连接数和 Stream 数；
9. 关闭时释放 UDP Channel、QUIC Connection、Stream 和 EventLoop。

Stream Pipeline：

```text
Http3FrameCodec
  → Http3CipherDecodeHandler
  → Http3ProxyMessageDecoder
  → ExchangeHandler

ExchangeHandler outbound
  → Http3ProxyMessageEncoder
  → Http3CipherEncodeHandler
  → Http3 DATA Frame
```

`ExchangeHandler` 本身不感知这是 HTTP/2 还是 HTTP/3。

`Http3Transporter` 必须校验 `MessageHandler instanceof ChannelHandler`，并将同一个
`@Sharable ExchangeHandler` 实例直接安装到每条 request-stream pipeline。Client 和 Server 都不得
只调用 `MessageHandler.onMessage()`；否则客户端 Future、服务端 `inboundCtx` 和回程路由均不会生效。

---

## 10. 编解码与加密

### 10.1 可以复用

- `ProxyCodec`；
- `Cipher` SPI；
- `NoneCipher`、AES、ChaCha 算法实现；
- `CipherConfig`；
- 现有 crypto vector。

### 10.2 不能直接复用

以下 Handler 绑定 `Http2DataFrame`，需要新增 HTTP/3 版本：

- `ProxyMessageEncoder`；
- `ProxyMessageDecoder`；
- `CipherEncodeHandler`；
- `CipherDecodeHandler`；
- `BackpressureHandler`。

V1 不抽象统一 Frame 接口，分别维护 HTTP/2 和 HTTP/3 Handler，避免为了少量复用引入新的中间层。
HTTP/3 Decoder 仍需复用现有“累积字节 + 长度校验 + 循环解码”的算法，不能因 Netty 暴露
`Http3DataFrame` 就省略跨 Frame 累积。

所有长度字段在分配内存前必须校验非负、单消息上限和整数溢出；协议错误只关闭当前 Stream，
同时释放 cumulation、待发消息和所有持有的 `ByteBuf`。

### 10.3 Cipher 策略

QUIC 已强制使用 TLS 1.3，所以 V1 默认：

```yaml
cipher: none
```

为了验证兼容性，HTTP/3 Handler 仍支持现有 Cipher SPI，但性能对比必须分别记录：

- QUIC TLS only；
- QUIC TLS + 应用层 Cipher。

避免把双重加密的 CPU 消耗误判为 QUIC 性能问题。

---

## 11. 流控与背压

### 11.1 V1 原则

- 使用 QUIC 原生 Connection/Stream flow control；
- 每个 Stream 维护有界 pending queue；
- 每个 QUIC Connection 维护聚合 pending bytes 上限；
- 配置 Netty write-buffer high/low watermark，并响应 `channelWritabilityChanged`；
- 不把现有 `BackpressureHandler` 原样搬到 HTTP/3；
- 禁止因一个 Stream 积压而对父 QUIC Connection 设置 `AUTO_READ=false`；
- 单 Stream 不可写时只暂停该 Stream；
- Connection 达到聚合上限时拒绝新 Stream，并按确定策略失败产生溢出的 Stream，禁止无界写入 Netty outbound buffer。

### 11.2 初始参数

```text
maxBidirectionalStreams = 1000
initialConnectionWindow = 16 MiB
initialStreamWindow     = 1 MiB
maxProxyMessageBytes    = 8 MiB
streamPendingHardLimit  = 4 MiB
connectionPendingHardLimit = 64 MiB
writeBufferLowWaterMark     = 256 KiB
writeBufferHighWaterMark    = 1 MiB
```

参数只是压测起点，不能通过不断增大窗口掩盖消费速度不足。尤其不能按
`maxBidirectionalStreams × streamPendingHardLimit` 预留内存，实际总积压必须受 Connection 级硬上限约束。

### 11.3 Flush

V1 先保持当前每个 `ProxyMessage` 调用 `writeAndFlush()` 的行为，以减少变量。

如果基准证明小包 flush 成为主要瓶颈，再单独增加 batching；不要在验证 QUIC 队头阻塞的第一版同时改变多个性能变量。

---

## 12. TLS 与证书

HTTP/3 必须使用 TLS 1.3。

客户端：

- 校验证书链和 hostname；
- 支持开发环境自定义 CA；
- 可选 SPKI pin；
- 禁止生产环境 trust-all；
- TLS/证书错误直接失败，本阶段不自动回退。

服务端：

- 从文件加载 certificate/private key；
- 私钥不进入 Git；
- 启动时证书不可读则直接失败；
- 日志只记录证书摘要和到期时间，不记录私钥内容。

本阶段不新增业务鉴权，沿用当前安全边界。业务鉴权另立任务。

---

## 13. Netty 与依赖

当前项目使用 Netty `4.1.108.Final`。HTTP/3 正式模块位于 Netty 4.2，因此 V1 统一升级整个 Maven 工程到同一 Netty 4.2 BOM。

设计基线：

```xml
<netty.version>4.2.18.Final</netty.version>
```

实施时复核最新安全版本。

HTTP/3 模块依赖：

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-http3</artifactId>
</dependency>
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-quic</artifactId>
</dependency>
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-native-quic</artifactId>
    <classifier>${os.detected.name}-${os.detected.arch}</classifier>
</dependency>
```

具体 artifact/classifier 以选定版本 POM 为准。

要求：

- HTTP/2 模块先在 Netty 4.2 上通过全部回归；
- HTTP/2 和 HTTP/3 禁止使用不同 Netty 大版本；
- Linux、Windows x64、macOS arm64 分别做 native load smoke test；
- fat jar 和 Tauri 资源必须包含目标平台 native 库。

---

## 14. 部署闭环

### 14.1 网络

HTTP/3 使用 UDP：

```text
UDP 9090 → Http3Server
```

HTTP/2 回滚路径继续使用：

```text
TCP 9090 → Nginx → NettyServer
```

TCP 和 UDP 可以使用相同端口号。

### 14.2 必须修改

- 云安全组开放 UDP 9090；
- 主机防火墙开放 UDP 9090；
- Docker Compose 增加 `9090:9090/udp`；
- Dockerfile/fat jar 包含 Linux QUIC native 库；
- systemd 配置证书路径；
- 启动日志打印实际绑定的协议和端口。

### 14.3 Nginx

V1 不改造 Nginx 承载 QUIC：

- HTTP/2 继续走现有 Nginx TCP stream；
- HTTP/3 由 `proxy-remote` 直接监听 UDP；
- 不在 V1 引入 UDP L4 负载均衡。

### 14.4 Docker Compose

```yaml
ports:
  - "9090:9090/tcp"
  - "9090:9090/udp"
volumes:
  - ./certs:/etc/simpleplane/tls:ro
```

---

## 15. Desktop、Dashboard 与 Android 边界

### 15.1 Desktop/TUN

桌面 TUN 链路为：

```text
tun-adapter → SOCKS5 → proxy-local → Http3Client
```

因此不需要修改 `tun-adapter`，只需要：

- 桌面打包包含升级后的 `proxy-local.jar`；
- 包含目标平台 QUIC native 库；
- 配置读写保留 `transport` 和 `http3` 字段；
- 安装包执行 native load smoke test。

### 15.2 Dashboard

V1 不要求完整新增 HTTP/3 配置 UI，只要求：

- Dashboard 读取、保存 YAML 时不丢失新字段；
- 状态页显示 `transport=http3`；
- 日志能看到 QUIC bind/connect 成功或失败；
- 后续再增加图形化证书和窗口配置。

### 15.3 Android

Android `plane-core` 直接连接 `proxy-remote`，不会经过 Java `proxy-local`。

V1 明确不覆盖 Android HTTP/3。Android 继续使用 HTTP/2，服务端部署时根据需要保留 HTTP/2 实例/端口。

Java HTTP/3 验证成功后，再单独编写 Rust QUIC 改造设计。

---

## 16. 测试方案

### 16.1 SPI 测试

- 未配置 transport → 加载 HTTP/2；
- `transport=http2` → `NettyTransporter`；
- `transport=http3` → `Http3Transporter`；
- 未知 transport → 启动失败；
- Client/Server 使用相同选择逻辑。

### 16.2 Codec 与 Handler 测试

- ProxyMessage CONNECT/DATA/DISCONNECT round trip；
- 一条消息在一个 DATA Frame 中可正确恢复；
- 一条消息拆到多个 DATA Frame 仍可正确恢复；
- 一个 DATA Frame 中合并多条消息仍可逐条恢复；
- 消息跨底层 QUIC packet 仍可正确恢复；
- Cipher none/AES/ChaCha round trip；
- 非法长度、损坏密文、未知消息类型正确关闭单 Stream；
- 单 Stream 协议错误不会失败其他 Stream 的 Future；
- ByteBuf 引用计数无泄漏。

### 16.3 Java 端到端测试

```text
SOCKS5 Client
  → proxy-local
  → Http3Client
  → Http3Server
  → proxy-remote
  → TCP Echo Server
```

验证：

- CONNECT 成功；
- 上行和下行字节完全一致；
- 多次 DATA 保序；
- 100 个并发 streamId 相互隔离；
- 一个 Stream pending 溢出或 RESET 时，其余 Stream 继续正常收发；
- Connection 聚合 pending 达到上限后内存保持有界；
- DISCONNECT 后客户端/服务端 Map 均释放；
- QUIC Connection 断开后所有 Stream 失败；
- 重连成功后新会话正常；
- 错误证书拒绝连接；
- UDP 未开放时快速报错。

### 16.4 HTTP/2 回归

Netty 升级到 4.2 后，必须先通过现有：

- `ProxyCodecTest`；
- `CryptoChannelIntegrationTest`；
- `ClientServerIntegrationTest`；
- `OutboundIntegrationTest`；
- SOCKS5 Handler 测试；
- Dashboard 测试。

### 16.5 队头阻塞专项

测试模型：

- 一个持续大流；
- 50～100 个周期性小流；
- 分别注入 0%、1%、3%、5% 丢包；
- RTT 分别为 20ms、100ms、200ms；
- HTTP/2 和 HTTP/3 使用完全相同 payload、并发和运行时间。

采集：

- 总吞吐；
- 小流 P50/P95/P99；
- 最大停顿时间；
- QUIC RTT、丢包和重传/PTO；
- CPU、内存、direct memory；
- active Stream 和 pending queue。
- Stream/Connection 两级 pending bytes 和 Channel writability 切换次数。

验收目标：

- 丢包时 HTTP/3 小流不出现全部同步停顿；
- 1% 丢包时小流 P99 相比 HTTP/2 明显改善，目标至少 50%；
- 0% 丢包时 HTTP/3 吞吐不低于 HTTP/2 的 90%；
- 连接关闭后 Stream Map 和 pending queue 清零；
- 2 小时并发压测无持续内存增长。

---

## 17. 日志与最小指标

V1 不建设完整指标平台，但必须输出足够信息判断 QUIC 是否有效。

日志字段：

```text
transport
remoteAddress
quicConnectionIdHash
streamId
messageType
requestId
activeStreams
pendingMessages
pendingBytes
handshakeDurationMs
errorScope
errorCategory
```

最小计数器：

- QUIC connection success/failure；
- handshake duration；
- active QUIC connections；
- active HTTP/3 streams；
- Stream open failure；
- send failure；
- Connection reconnect；
- pending queue overflow；
- Stream-local / Connection-level failure；
- bytes up/down。

禁止记录 Cipher key、私钥、完整证书 pin 和 DATA 内容。

---

## 18. 实施任务

### Task 0：基线

- 用现有 HTTP/2 跑队头阻塞专项；
- 固化压测参数和结果；
- 记录当前 CPU、内存和吞吐。

验收：能稳定复现用户观察到的问题。

### Task 1：SPI 可选择

- 注册 `http2/http3`；
- HeaderExchanger 按 URL 选择；
- Local/Remote 配置传入 transport；
- 增加 SPI 单测。

验收：使用测试 Transporter 证明 connect/bind 都能按配置加载。

### Task 2：Netty 4.2 升级

- 升级统一 BOM；
- 修复编译兼容；
- 跑 HTTP/2 全量回归。

验收：默认仍为 HTTP/2，现有行为不变。

### Task 3：Http3Client

- QUIC/TLS/H3 建连；
- streamId → request stream；
- Stream/Connection 两级 pending queue 与 write-buffer watermark；
- Stream 状态机和 EventLoop 线程封闭；
- Stream-local 与 Connection-level 错误隔离；
- Encoder/Decoder/Cipher Pipeline；
- close/reconnect/isAvailable。

验收：Client 能与测试 H3 Server 双向发送 ProxyMessage。

### Task 4：Http3Server

- UDP bind；
- TLS/ALPN；
- `/proxy` request stream；
- Pipeline 直接接入现有 `@Sharable ExchangeHandler`，保留 `ChannelHandlerContext`；
- close 和资源释放。

验收：Java Client → Remote → echo target 全链路通过。

### Task 5：配置与部署

- YAML 配置；
- UDP/firewall/Docker；
- 证书挂载；
- desktop native 打包；
- Dashboard 保存配置不丢字段。

验收：测试服务器和桌面安装包可以实际运行 H3。

### Task 6：性能验证

- HTTP/2 vs HTTP/3 对照；
- netem 丢包/RTT；
- 并发和长稳；
- 输出结论报告。

验收：达到第 16.5 节门槛，否则不进入默认使用阶段。

---

## 19. 发布与回滚

V1 不自动协商协议，通过配置控制：

```yaml
transport: http3
```

灰度顺序：

1. 本地 loopback；
2. 测试服务器；
3. 内部一台桌面客户端；
4. 少量内部设备；
5. 根据压测和实际日志决定是否扩大。

回滚：

```yaml
transport: http2
```

然后重启 `proxy-local` 和对应 `proxy-remote` 实例。

服务端可以并行部署两个实例：

```text
TCP 9090  → HTTP/2 稳定实例
UDP 9090  → HTTP/3 实验实例
```

HTTP/3 故障不需要回滚代码或配置结构，只需客户端切回 HTTP/2。

### 回滚触发条件

- QUIC handshake 成功率低于预期；
- UDP 网络兼容性不可接受；
- P99 没有改善或明显恶化；
- CPU/内存超过 HTTP/2 基线 30%；
- 出现消息乱序、Stream 泄漏或 native crash；
- 桌面平台 native 库加载不稳定。

---

## 20. V1 完成定义

以下全部满足才算本阶段全链路闭合：

- [ ] `transport=http3` 能通过 SPI 加载 `Http3Transporter`；
- [ ] `Invoker → ClientInvoker → HeaderExchangeClient → Http3Client` 链路不变；
- [ ] `ProxyMessage` 编解码和 requestId/Future 语义不变；
- [ ] HTTP/3 接收端支持 ProxyMessage 跨 DATA Frame 及多消息合并 Frame；
- [ ] 一个应用 streamId 始终对应一个 HTTP/3 Stream；
- [ ] Http3Server 收到消息后进入现有 ExchangeHandler/DispatchInvoker；
- [ ] ExchangeHandler 通过 Stream Pipeline 执行，服务端 `inboundCtx` 和回程语义保持不变；
- [ ] 单 Stream 故障不会触发 `DefaultFuture.failAll()` 或影响其他 Stream；
- [ ] Stream/Connection 两级 pending 上限和 write-buffer watermark 生效，积压内存有界；
- [ ] 目标站点回包能沿同一 Stream 返回本地应用；
- [ ] CONNECT、DATA、DISCONNECT 和异常断开全部通过 E2E；
- [ ] HTTP/2 在 Netty 4.2 上完整回归；
- [ ] V1 单节点、单连接配置约束有启动校验；
- [ ] UDP、防火墙、Docker、证书和 native 库部署完成；
- [ ] Windows x64、macOS arm64、Linux x64 native load smoke 通过；
- [ ] Desktop TUN → proxy-local → H3 → remote 真机通过；
- [ ] Dashboard 保存配置不丢失 HTTP/3 字段；
- [ ] 队头阻塞专项达到验收门槛；
- [ ] 配置切回 HTTP/2 的回滚演练通过；
- [ ] 文档记录实际压测结果和已知限制。

---

## 21. 后续阶段候选项

以下内容只有在 V1 证明 QUIC 确实带来收益后才进入设计：

1. 多节点、多连接下按 streamId 做会话粘滞；
2. HTTP/3 失败自动 fallback HTTP/2；
3. 标准 HTTP/3 CONNECT，移除 DATA 的 ProxyMessage 包装；
4. DATA write batching；
5. Android/Rust HTTP/3；
6. 0-RTT 和 Connection Migration；
7. QUIC-aware UDP 负载均衡；
8. 完整 Dashboard HTTP/3 配置 UI；
9. 业务鉴权和证书自动轮换。

这些都不是 V1 合并和验证的前置条件。

---

## 22. 参考资料

- [RFC 9000 — QUIC](https://www.rfc-editor.org/rfc/rfc9000.html)
- [RFC 9001 — QUIC TLS](https://www.rfc-editor.org/rfc/rfc9001.html)
- [RFC 9002 — QUIC Loss Detection and Congestion Control](https://www.rfc-editor.org/rfc/rfc9002.html)
- [RFC 9114 — HTTP/3](https://www.rfc-editor.org/rfc/rfc9114.html)
- [Netty 4.2 API](https://netty.io/4.2/api/)

---

## 23. 评审结论

推荐通过本 V1 方案：

- 保持 `Invoker → ExchangeClient → Client` 架构；
- 通过 SPI 新增 `Http3Transporter/Http3Client/Http3Server`；
- HTTP/3 DATA 继续承载现有 `ProxyMessage`；
- 上层业务、Exchange、Filter、Dispatch 和 Outbound 不动；
- 用单节点、单连接约束控制阶段风险；
- 先以对照压测试证 QUIC 对队头阻塞的收益；
- 收益成立后，再讨论会话粘滞、自动 fallback、标准 CONNECT 和 Android。

这是一条能够快速验证、完整闭环、随时回滚的最小改造路径。
