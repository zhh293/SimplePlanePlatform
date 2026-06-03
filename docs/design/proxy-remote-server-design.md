# proxy-remote 远程服务端 技术设计文档

| 属性     | 内容                                              |
| -------- | ------------------------------------------------- |
| 文档版本 | v1.0                                              |
| 项目名称 | Netty-Proxy 远程代理服务端                        |
| 所属模块 | proxy-remote / proxy-common / proxy-exchange / proxy-transport-netty |
| 作者     | zhanghonghao                                      |
| 状态     | Draft                                             |

---

## 1. 背景与目标

### 1.1 现状分析

当前 Netty-Proxy 框架已完成客户端全链路的设计与实现，包含本地代理监听（SOCKS5/HTTP CONNECT）、Filter 责任链、集群容错、负载均衡、请求-响应交换层以及 Netty HTTP/2 传输层。整体架构遵循 Dubbo 微内核+插件化设计哲学，所有核心组件通过 SPI 机制可插拔替换。

然而，远程服务端（`proxy-remote`）模块目前为空壳状态（`dispatch/` 与 `outbound/` 目录为空），框架核心 SPI 接口（`Exchanger`、`Transporter`）仅暴露了面向客户端的 `connect` 方法，不具备服务端监听（`bind`）的能力。

### 1.2 设计目标

本次设计旨在为框架补齐服务端能力，使其成为一套完整的双向通信框架。具体目标如下：

1. 在 `Transporter` 层新增 `bind` 语义，支持启动 Netty Server 监听端口接收客户端连接。
2. 在 `Exchanger` 层新增 `bind` 语义，构建服务端的请求接收-处理-响应闭环。
3. 复用已有 Filter/Invoker SPI 体系，将请求处理逻辑封装为 `Invoker`，支持动态代理增强与 Filter 链拦截。
4. 全链路非阻塞，IO 线程仅负责收发数据，业务处理交由独立线程池异步执行。
5. 保持架构对称性，客户端与服务端复用同一套编解码、加密、心跳机制，新增代码不破坏既有模块。

### 1.3 非目标（本期不涉及）

- 服务注册与发现：多节点远程服务器的注册中心接入不在本期范围。
- TLS 证书管理：底层 HTTP/2 连接的 TLS 握手沿用现有实现，不做额外改造。
- Outbound 连接池/连接复用：代理场景目标地址多变，不做池化。一 Stream 一连接，按需建连。
- 异步 DNS 解析：本期直接使用 Netty Bootstrap 默认的 DNS 解析，后续增强可引入异步 DNS + 本地缓存。

---

## 2. 整体架构

### 2.1 数据流全景

```
客户端(proxy-local)                                    远程服务端(proxy-remote)
─────────────────                                    ──────────────────────
浏览器 → SOCKS5/HTTP CONNECT                          Netty ServerBootstrap
  → Filter Chain                                       ← 监听端口(bind)
  → ClusterInvoker(容错+LB)                            │
  → ExchangeClient.request()                           ▼
  → [编码 → 加密 → HTTP/2 发送] ─────网络────→ [HTTP/2 接收 → 解密 → 解码]
                                                       │
                                                       ▼
                                                  ServerExchangeHandler.onMessage()
                                                       │
                                                       ▼
                                                  Invoker.invoke(invocation)
                                                  (Filter链 → 真实处理器)
                                                       │
                                                       ▼
                                                  CompletableFuture<Response>
                                                       │
                                                       ▼ .whenComplete()
                                                  ctx.writeAndFlush(响应消息)
                                                  [编码 → 加密 → HTTP/2 发送]
                                                       │
客户端 ExchangeHandler.onMessage()  ←────网络────────────┘
  → DefaultFuture.received(requestId, response)
  → 唤醒业务线程
```

### 2.2 模块职责划分

| 模块                   | 本次新增/变更内容                                                                 |
| ---------------------- | -------------------------------------------------------------------------------- |
| `proxy-common`         | 新增 `Server` 接口、`Exchanger.bind()` 方法、`ExchangeServer` 接口               |
| `proxy-exchange`       | 新增 `ServerExchangeHandler`、`HeaderExchangeServer`、`HeaderExchanger.bind()` 实现 |
| `proxy-transport-netty`| 新增 `NettyServer`、`NettyTransporter.bind()` 实现                                |
| `proxy-remote`         | 新增 `ProxyRemoteServer` 启动入口、`DispatchInvoker`、服务端 Filter 链组装        |

---

## 3. 详细设计

### 3.1 Transport 层扩展

#### 3.1.1 新增 `Server` 接口

定义位置：`proxy-common/src/main/java/com/proxy/common/transport/Server.java`

```java
package com.proxy.common.transport;

/**
 * 服务端抽象 —— 代表一个监听中的服务实例
 */
public interface Server {

    /**
     * 启动服务，开始监听
     */
    void start();

    /**
     * 优雅关闭服务
     */
    void close();

    /**
     * 服务是否正在运行
     */
    boolean isActive();

    /**
     * 获取当前活跃连接数
     */
    int getActiveConnectionCount();

    /**
     * 获取监听地址
     */
    String getBindAddress();

    /**
     * 获取监听端口
     */
    int getBindPort();
}
```

#### 3.1.2 `Transporter` 接口新增 `bind` 方法

```java
@SPI("netty")
public interface Transporter {

    Client connect(URL url, MessageHandler handler) throws TransportException;

    /**
     * 绑定端口，启动服务端监听
     * <p>
     * 内部启动 Netty ServerBootstrap，为每条新连接的 Pipeline 拼接：
     * ProxyMessageDecoder → CipherDecodeHandler → ServerMessageHandler(handler)
     * → CipherEncodeHandler → ProxyMessageEncoder
     * </p>
     *
     * @param url     监听地址及参数（host、port、cipher、workerThreads 等）
     * @param handler 消息处理器（由上层 Exchanger 创建，处理客户端请求）
     * @return Server 实例
     * @throws TransportException 绑定端口失败时抛出
     */
    default Server bind(URL url, MessageHandler handler) throws TransportException {
        throw new UnsupportedOperationException("bind not supported by " + getClass().getName());
    }
}
```

> 注：使用 `default` 方法保证向后兼容，现有客户端代码无需修改即可编译通过。

#### 3.1.3 `NettyServer` 实现

定义位置：`proxy-transport-netty/src/main/java/com/proxy/transport/netty/NettyServer.java`

核心设计要点：

- 使用 `ServerBootstrap` 启动，`bossGroup`（1线程）负责 Accept，`workerGroup`（CPU×2线程）负责 IO 读写。
- **父 Channel（TCP 连接级别）** 仅挂载 HTTP/2 协议处理器：`SslHandler`（可选）+ `Http2FrameCodec(forServer)` + `Http2MultiplexHandler`。
- **子 Channel（HTTP/2 Stream 级别）** 才挂载业务 Handler：`CipherDecodeHandler` → `ProxyMessageDecoder` → `IdleStateHandler` → `HeartbeatHandler` → `ServerChannelHandler` + 出站方向的 `CipherEncodeHandler` → `ProxyMessageEncoder`。
- 这与客户端 `ConnectionPool` 的架构完全对称：客户端通过 `Http2StreamChannelBootstrap.open()` 主动创建出站 Stream，服务端通过 `Http2MultiplexHandler` 的 `inboundStreamInitializer` 被动接收客户端发起的入站 Stream。
- `ProxyMessageDecoder` 继承 `MessageToMessageDecoder<Http2DataFrame>`，它依赖 `Http2MultiplexHandler` 将 HTTP/2 DATA 帧按 streamId 分发到子 Channel 后产生的 `Http2DataFrame` 对象，因此**必须**装在 Stream 子 Channel 上，不能装在父 Channel 上。

**Pipeline 架构（与客户端镜像对称）：**

```
TCP Parent Channel (每条客户端连接):
  ├── SslHandler (可选, SslContext.forServer)
  ├── Http2FrameCodec (forServer, 配置 maxConcurrentStreams)
  └── Http2MultiplexHandler (inboundStreamInitializer)
        │
        └── Per-Stream SubChannel (客户端主动发起的每个 Stream):
              ├── CipherDecodeHandler   (入站: 解密 data 字段)
              ├── ProxyMessageDecoder   (入站: Http2DataFrame → ProxyMessage)
              ├── IdleStateHandler      (空闲检测)
              ├── HeartbeatHandler      (心跳自动响应)
              ├── ServerChannelHandler  (入站: 调用 invoker → 回写响应)
              ├── CipherEncodeHandler   (出站: 加密 data 字段)
              └── ProxyMessageEncoder   (出站: ProxyMessage → Http2DataFrame)
```

```java
public class NettyServer implements Server {

    private final URL url;
    private final MessageHandler handler;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private volatile SslContext sslContext;

    public NettyServer(URL url, MessageHandler handler) {
        this.url = url;
        this.handler = handler;
        int bossThreads = url.getParameter("bossThreads", 1);
        int workerThreads = url.getParameter("workerThreads", 0); // 0 = Netty default (CPU*2)
        this.bossGroup = new NioEventLoopGroup(bossThreads);
        this.workerGroup = new NioEventLoopGroup(workerThreads);

        if (url.getParameter("ssl", false)) {
            initSslContext();
        }
    }

    private void initSslContext() {
        // 服务端 SSL 上下文（与客户端的 forClient 不同，这里是 forServer）
        sslContext = SslContextBuilder.forServer(certChainFile, keyFile)
                .sslProvider(SslProvider.JDK)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();
    }

    @Override
    public void start() {
        int maxStreamsPerConnection = url.getParameter("maxStreams", 100);
        int readIdleTimeout = url.getParameter("readIdleTimeout", 60);
        int heartbeatInterval = url.getParameter("heartbeatInterval", 30);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .option(ChannelOption.SO_BACKLOG, 1024)
                 .childOption(ChannelOption.TCP_NODELAY, true)
                 .childOption(ChannelOption.SO_KEEPALIVE, true)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) {
                         ChannelPipeline p = ch.pipeline();

                         // ======== 父 Channel: 仅处理 HTTP/2 协议层 ========

                         // 1. TLS（可选）
                         if (sslContext != null) {
                             p.addLast("ssl", sslContext.newHandler(ch.alloc()));
                         }

                         // 2. HTTP/2 帧编解码 —— 注意是 forServer()
                         p.addLast("http2-codec",
                                 Http2FrameCodecBuilder.forServer()
                                         .initialSettings(Http2Settings.defaultSettings()
                                                 .maxConcurrentStreams(maxStreamsPerConnection))
                                         .build());

                         // 3. HTTP/2 多路复用 —— 为客户端发起的每个 Stream 创建子 Channel
                         //    inboundStreamInitializer: 服务端接收到的入站 Stream
                         p.addLast("http2-multiplex",
                                 new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                     @Override
                                     protected void initChannel(Channel streamCh) {
                                         // ======== 子 Channel: 业务处理链 ========
                                         ChannelPipeline sp = streamCh.pipeline();
                                         sp.addLast("cipher-decode", new CipherDecodeHandler());
                                         sp.addLast("cipher-encode", new CipherEncodeHandler());
                                         sp.addLast("decoder", new ProxyMessageDecoder());
                                         sp.addLast("encoder", new ProxyMessageEncoder());
                                         sp.addLast("idle",
                                                 new IdleStateHandler(
                                                         readIdleTimeout,
                                                         heartbeatInterval,
                                                         0, TimeUnit.SECONDS));
                                         sp.addLast("heartbeat", new HeartbeatHandler());
                                         sp.addLast("server-handler",
                                                 new ServerChannelHandler(handler));
                                     }
                                 }));

                         // 连接计数
                         connectionCount.incrementAndGet();
                         ch.closeFuture().addListener(f -> connectionCount.decrementAndGet());
                     }
                 });

        try {
            serverChannel = bootstrap.bind(url.getHost(), url.getPort())
                                     .sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Bind interrupted", e);
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @Override
    public boolean isActive() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public int getActiveConnectionCount() {
        return connectionCount.get();
    }

    @Override
    public String getBindAddress() {
        return url.getHost();
    }

    @Override
    public int getBindPort() {
        return url.getPort();
    }
}
```

**关键说明 —— 为什么编解码 Handler 必须在子 Channel 上：**

`ProxyMessageDecoder` 继承了 `MessageToMessageDecoder<Http2DataFrame>`，期望接收的输入类型是 `Http2DataFrame`。而 `Http2DataFrame` 只有在 `Http2MultiplexHandler` 将 HTTP/2 帧按 streamId 分发到子 Channel 之后才会产生。如果把 `ProxyMessageDecoder` 放在父 Channel 上，它永远收不到 `Http2DataFrame` 类型的消息，Pipeline 直接跳过它。

这与客户端 `ConnectionPool.openStream()` 中的子 Channel Pipeline 完全对称：

| 角色 | 父 Channel 职责 | 子 Channel 职责 |
|------|----------------|----------------|
| 客户端 | SSL + Http2FrameCodec(forClient) + Http2MultiplexHandler | 出站 Stream: Cipher + Codec + Idle + Heartbeat + ClientMessageHandler |
| 服务端 | SSL + Http2FrameCodec(forServer) + Http2MultiplexHandler | 入站 Stream: Cipher + Codec + Idle + Heartbeat + ServerChannelHandler |

#### 3.1.4 `NettyTransporter.bind()` 实现

```java
@Override
public Server bind(URL url, MessageHandler handler) throws TransportException {
    try {
        NettyServer server = new NettyServer(url, handler);
        server.start();
        log.info("NettyServer started on {}:{}", url.getHost(), url.getPort());
        return server;
    } catch (Exception e) {
        throw new TransportException("Failed to bind on " +
                url.getHost() + ":" + url.getPort(), e);
    }
}
```

---

### 3.2 Exchange 层扩展

#### 3.2.1 新增 `ExchangeServer` 接口

定义位置：`proxy-common/src/main/java/com/proxy/common/exchange/ExchangeServer.java`

```java
package com.proxy.common.exchange;

import com.proxy.common.transport.Server;

/**
 * 交换层服务端 —— 包装底层 Server，对上层暴露请求处理能力
 * <p>
 * 由 Exchanger.bind() 创建，内部持有底层 Server 和 Invoker。
 * 上层使用方式：
 * <pre>
 * Exchanger exchanger = SPI.load("header");
 * ExchangeServer server = exchanger.bind(url, invoker);
 * // server 启动后自动接收请求并通过 invoker 处理
 * </pre>
 * </p>
 */
public interface ExchangeServer {

    /**
     * 关闭服务端
     */
    void close();

    /**
     * 是否运行中
     */
    boolean isActive();

    /**
     * 获取底层 Server（用于监控等场景）
     */
    Server getServer();
}
```

#### 3.2.2 `Exchanger` 接口新增 `bind` 方法

```java
@SPI("header")
public interface Exchanger {

    ExchangeClient connect(URL url);

    /**
     * 绑定端口，启动交换层服务端
     * <p>
     * 内部组装 ServerExchangeHandler + 调用 Transporter.bind() + 包装成 ExchangeServer。
     * ServerExchangeHandler 收到请求后调用 invoker.invoke()，异步回写响应。
     * </p>
     *
     * @param url     监听地址及参数
     * @param invoker 请求处理器（已封装 Filter 链的最终 Invoker）
     * @return ExchangeServer 实例
     */
    default ExchangeServer bind(URL url, Invoker invoker) {
        throw new UnsupportedOperationException("bind not supported by " + getClass().getName());
    }
}
```

关键设计决策：`bind` 方法接收一个 `Invoker` 参数而非裸 Handler，意味着 Filter 链在 `bind` 之前已经组装完毕（由上层 `ProxyRemoteServer` 负责），Exchanger 层不关心 Filter 编排细节，只负责网络收发和请求-响应语义映射。

#### 3.2.3 `ServerExchangeHandler` 实现

定义位置：`proxy-exchange/src/main/java/com/proxy/exchange/header/ServerExchangeHandler.java`

```java
package com.proxy.exchange.header;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.MessageHandler;

import java.util.concurrent.CompletableFuture;

/**
 * 服务端消息处理器 —— 接收客户端请求并异步响应
 * <p>
 * 与客户端的 ExchangeHandler 职责对称：
 * - 客户端 ExchangeHandler：收到响应 → 按 requestId 唤醒 DefaultFuture
 * - 服务端 ServerExchangeHandler：收到请求 → invoker.invoke() → 异步写回响应
 * </p>
 * <p>
 * 核心设计：全程非阻塞。onMessage 在 IO 线程执行，
 * invoker.invoke() 返回 CompletableFuture，通过 whenComplete 回调写回 Channel，
 * 绝不阻塞 IO 线程。
 * </p>
 */
public class ServerExchangeHandler implements MessageHandler {

    private final Invoker invoker;

    public ServerExchangeHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public void onMessage(ProxyMessage message) {
        if (message == null || message.getType() == null) {
            return;
        }

        // 心跳请求直接响应，不走 Invoker
        if (message.getType() == ProxyMessage.MessageType.HEARTBEAT_REQUEST) {
            handleHeartbeat(message);
            return;
        }

        long requestId = message.getRequestId();

        // 1. 将 ProxyMessage 转换为 Invocation
        Invocation invocation = new Invocation(
                message.getHost(),
                message.getPort(),
                message.getData(),
                message.getType()
        );
        invocation.setAttachment("requestId", requestId);
        invocation.setAttachment("streamId", message.getStreamId());

        // 2. 调用 Invoker（Filter 链 → 真实处理器），全程非阻塞
        CompletableFuture<Response> future = invoker.invoke(invocation);

        // 3. 异步回写响应
        future.whenComplete((response, throwable) -> {
            ProxyMessage reply = buildReply(requestId, message.getStreamId(), response, throwable);
            writeResponse(reply);
        });
    }

    @Override
    public void onError(Throwable cause) { /* 记录错误日志 */ }

    @Override
    public void onDisconnected() { /* 清理连接相关资源 */ }

    private ProxyMessage buildReply(long requestId, long streamId,
                                    Response response, Throwable throwable) { ... }

    private void handleHeartbeat(ProxyMessage message) { ... }

    private void writeResponse(ProxyMessage reply) { ... }
}
```

**ChannelHandlerContext 传递问题**

`ServerExchangeHandler` 需要通过 `ChannelHandlerContext` 写回响应，但当前 `MessageHandler` 接口的 `onMessage(ProxyMessage)` 签名不携带 ctx。解决方案：

在 Netty Pipeline 尾端的 `ServerChannelHandler` 中，将 ctx 绑定到 ProxyMessage 的 attachment 或通过 ThreadLocal 传递。推荐做法是在 `ServerChannelHandler.channelRead()` 中直接完成调度：

```java
// ServerChannelHandler（Netty Pipeline 中的 ChannelInboundHandler）
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ProxyMessage message = (ProxyMessage) msg;
    
    // 将 ctx 封装进 handler 调用，由 handler 内部通过闭包持有 ctx
    Invocation invocation = toInvocation(message);
    CompletableFuture<Response> future = invoker.invoke(invocation);
    
    future.whenComplete((resp, ex) -> {
        ProxyMessage reply = buildReply(message.getRequestId(), resp, ex);
        ctx.writeAndFlush(reply);  // ctx 通过闭包捕获，线程安全
    });
}
```

这种方式最为简洁：`ServerChannelHandler` 本身就是 Netty Handler，天然持有 ctx，无需额外的接口抽象。`ServerExchangeHandler` 的逻辑实际内聚到 `ServerChannelHandler` 中即可。

> **说明**：上述「内聚一层」是本期落地选用的方案，理由见下一节的取舍分析。它的代价是牺牲了一点与客户端的结构对称性（客户端是 `ClientMessageHandler` + `ExchangeHandler` 两层，服务端被压成一层）。如果更看重架构对称性，可采用下一节的 **Map 路由表方案** 恢复两层结构。

#### 3.2.4 方案对比：Map 路由表恢复两层结构（可选增强）

##### 3.2.4.1 问题回顾

上一节的根本矛盾在于：`MessageHandler.onMessage(ProxyMessage)` 的签名**不携带 `ChannelHandlerContext`**，导致位于 Exchange 层的 `ServerExchangeHandler` 在 `invoke()` 完成后**无法定位到要写回响应的那条 Channel**。「内聚一层」是通过让持有 ctx 的 `ServerChannelHandler` 自己把活全干完来回避这个问题的，代价是丢失了与客户端的两层对称结构。

##### 3.2.4.2 方案思路

既然 ctx 不能从参数传入，就在外部维护一张 **`requestId → Channel` 的全局映射表**，让 `ServerExchangeHandler` 在回写时**反查**出目标 Channel。这样 `ServerChannelHandler` 只需「登记映射 + 向下转发」，业务逻辑回到 `ServerExchangeHandler`，两层结构得以恢复。

**这张表与客户端的 `requestId → DefaultFuture` 表是完全同构的设计**——`requestId` 在两端都扮演「对暗号的钥匙」：客户端用它找「哪个等待的业务线程（Future）」，服务端用它找「哪条要回写的连接（Channel）」。

| | 客户端 | 服务端（Map 方案） |
|---|---|---|
| 全局表 | `requestId → DefaultFuture` | `requestId → Channel` |
| 谁登记 | `request()` 发请求时登记 Future | `ServerChannelHandler` 收请求时登记 Channel |
| 谁查表 | `ExchangeHandler.onMessage` 收响应时查 Future 唤醒 | `ServerExchangeHandler` 回写时查 Channel 并 writeAndFlush |
| 分层 | 两层（MessageHandler 不碰 ctx） | **两层（MessageHandler 不碰 ctx）** |

##### 3.2.4.3 实现示例

```java
// ServerChannelHandler（Netty 层，持有 ctx）——退化为「登记 + 转发」
public class ServerChannelHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private final MessageHandler messageHandler; // 即 ServerExchangeHandler

    public ServerChannelHandler(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage msg) {
        // 收到请求时，登记 requestId → channel 映射
        // 注意：key 必须全局唯一，跨连接共享，故使用复合 key 防止不同客户端的 requestId 撞车
        ChannelRouter.register(routingKey(ctx, msg), ctx.channel());
        // 纯转发，自己不碰业务，两层结构恢复
        messageHandler.onMessage(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开时，批量清理该 channel 名下所有未完成的映射，防止泄漏
        ChannelRouter.evictByChannel(ctx.channel());
    }

    private static String routingKey(ChannelHandlerContext ctx, ProxyMessage msg) {
        return ctx.channel().id().asShortText() + ":" + msg.getRequestId();
    }
}
```

```java
// ServerExchangeHandler（Exchange 层，不持有 ctx）——恢复为真正干活的一层
public class ServerExchangeHandler implements MessageHandler {

    private final Invoker invoker;

    public ServerExchangeHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public void onMessage(ProxyMessage message) {
        if (message == null || message.getType() == null) {
            return;
        }
        if (message.getType() == ProxyMessage.MessageType.HEARTBEAT_REQUEST) {
            handleHeartbeat(message);
            return;
        }

        String key = message.getRoutingKey(); // 与 ServerChannelHandler 约定一致的 key
        Invocation invocation = toInvocation(message);

        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            ProxyMessage reply = buildReply(message.getRequestId(), response, throwable);
            // 回写时按 key 反查 channel，查完即删
            Channel ch = ChannelRouter.remove(key);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(reply);
            }
            // ch 为 null 通常意味着连接已断开（已被 channelInactive 清理），丢弃响应即可
        });
    }

    @Override
    public void onError(Throwable cause) { /* 记录错误日志 */ }

    @Override
    public void onDisconnected() { /* 清理连接相关资源 */ }
}
```

```java
// ChannelRouter —— 全局路由表，需配套生命周期管理
public final class ChannelRouter {

    private static final ConcurrentMap<String, Channel> MAP = new ConcurrentHashMap<>();

    public static void register(String key, Channel channel) { MAP.put(key, channel); }

    public static Channel remove(String key) { return MAP.remove(key); }

    // 连接断开时按 channel 批量清理，防止未完成请求的映射永久泄漏
    public static void evictByChannel(Channel channel) {
        MAP.entrySet().removeIf(e -> e.getValue() == channel);
    }
}
```

##### 3.2.4.4 取舍对比

| 维度 | 内聚一层（本期选用） | Map 路由表两层（可选增强） |
|------|---------------------|---------------------------|
| 架构对称性 | 弱（服务端与客户端结构不对称） | **强（两端完全镜像对称）** |
| 实现复杂度 | 低（ctx 由 lambda 闭包捕获，零额外结构） | 中（需维护全局表 + 生命周期管理） |
| 内存泄漏风险 | 无（闭包随回调结束自动 GC） | **有**（`invoke` 不 complete 或异常路径会残留映射，需超时清理 / 连接断开清理兜底） |
| 并发开销 | 零（引用直传） | 每请求一次 `put` + 一次 `remove`，高 QPS 下有哈希与锁竞争开销 |
| requestId 约束 | 无（不跨连接共享） | **更严**（表跨所有连接共享，需复合 key `channelId:requestId` 防撞车，否则响应会串台） |
| 可扩展性 | 一般 | 好（与客户端共用同一套「标识 → 上下文」心智模型，易于演进） |

##### 3.2.4.5 选型结论

本期**选用「内聚一层」**：当前为单服务、轻量、追求零管理成本的场景，闭包捕获 ctx 是最简洁且无泄漏风险的实现。

**Map 路由表方案是更优雅的演进路径**，工业级框架（如 Dubbo 服务端基于 channel 上下文 + 请求标识路由响应）正是此思路。当框架向「多服务、强对称、高可扩展」方向演进时，可平滑切换到该方案——届时只需让 `ServerChannelHandler` 退化为「登记 + 转发」、并补齐 `ChannelRouter` 的超时与断连清理机制即可，`ServerExchangeHandler` 的对外契约不变。

#### 3.2.5 `HeaderExchangeServer` 实现

```java
package com.proxy.exchange.header;

import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.transport.Server;

public class HeaderExchangeServer implements ExchangeServer {

    private final Server server;

    public HeaderExchangeServer(Server server) {
        this.server = server;
    }

    @Override
    public void close() { server.close(); }

    @Override
    public boolean isActive() { return server.isActive(); }

    @Override
    public Server getServer() { return server; }
}
```

#### 3.2.6 `HeaderExchanger.bind()` 实现

```java
@Override
public ExchangeServer bind(URL url, Invoker invoker) {
    // 1. 创建服务端消息处理器（持有 invoker 引用）
    ServerExchangeHandler handler = new ServerExchangeHandler(invoker);

    // 2. 通过 SPI 加载 Transporter，启动服务端监听
    Transporter transporter = ExtensionLoader.getLoader(Transporter.class).getDefaultExtension();
    Server server = transporter.bind(url, handler);

    // 3. 包装成 ExchangeServer 返回
    log.info("HeaderExchanger bound ExchangeServer on {}:{}", url.getHost(), url.getPort());
    return new HeaderExchangeServer(server);
}
```

---

### 3.3 Invoker 与 Filter 链组装（服务端视角）

#### 3.3.1 设计思路

服务端的 Invoker 体系与客户端完全对称，复用同一套 `Filter` / `Invoker` / `FilterChainBuilder` SPI：

```
客户端调用链:
  Filter(Router) → Filter(RateLimit) → Filter(Monitor) → ClientInvoker → ExchangeClient.request()

服务端处理链:
  Filter(RateLimit) → Filter(Monitor) → Filter(AccessLog) → DispatchInvoker → 业务处理逻辑
```

区别仅在于链末端的"真实 Invoker"：客户端是 `ClientInvoker`（发送网络请求），服务端是 `DispatchInvoker`（分派到具体业务逻辑）。

#### 3.3.2 `DispatchInvoker` —— 服务端请求分派器

定义位置：`proxy-remote/src/main/java/com/proxy/remote/dispatch/DispatchInvoker.java`

```java
package com.proxy.remote.dispatch;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 服务端请求分派器 —— Filter 链的末端，真正执行业务逻辑的地方
 * <p>
 * 根据请求类型（CONNECT / DATA / DISCONNECT）分派到对应的处理逻辑。
 * 所有处理均在独立业务线程池中异步执行，确保不阻塞 IO 线程。
 * </p>
 * <p>
 * 后续迭代中，CONNECT 请求将触发 Outbound 连接建立（连接目标站点），
 * DATA 请求将触发数据转发，DISCONNECT 请求将触发连接清理。
 * 本期仅定义框架骨架，具体出站逻辑留白。
 * </p>
 */
public class DispatchInvoker implements Invoker {

    private final ExecutorService bizExecutor;

    public DispatchInvoker(ExecutorService bizExecutor) {
        this.bizExecutor = bizExecutor;
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) {
        CompletableFuture<Response> future = new CompletableFuture<>();

        bizExecutor.execute(() -> {
            try {
                Response response = dispatch(invocation);
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private Response dispatch(Invocation invocation) {
        ProxyMessage.MessageType type = invocation.getType();

        switch (type) {
            case CONNECT:
                return handleConnect(invocation);
            case DATA:
                return handleData(invocation);
            case DISCONNECT:
                return handleDisconnect(invocation);
            default:
                return Response.error("Unsupported message type: " + type);
        }
    }

    private Response handleConnect(Invocation invocation) {
        // 隧道建立：客户端告知目标 host:port，服务端在此时机异步发起 Outbound 连接
        // Phase 2 实现流程：
        //   1. 从 invocation 中取出 targetHost:targetPort
        //   2. 通过 Outbound Bootstrap 异步建连（DNS + TCP + 可能的 TLS）
        //   3. 将连接 Future/引用注册到 streamId → OutboundSession 映射
        //   4. 立即返回 OK（通知客户端隧道就绪，可以开始发送 DATA）
        // 利用 CONNECT 与第一个 DATA 之间的时间窗口完成建连，减少用户首包延迟
        return Response.ok();
    }

    private Response handleData(Invocation invocation) {
        // 数据面核心路径：用户的实际流量到达
        // Phase 2 实现流程：
        //   1. 根据 streamId 从映射中取出 OutboundSession
        //   2. 如果 Outbound 连接已 ready → 直接转发用户数据到目标站点
        //      如果连接尚未建好 → 等待连接就绪（或缓冲排队）后再发
        //   3. 异步等待目标站点响应
        //   4. 将响应数据通过 CompletableFuture 返回，由上层通过 Stream Channel 推回客户端
        return Response.ok(invocation.getData());
    }

    private Response handleDisconnect(Invocation invocation) {
        // 会话断开：客户端通知隧道关闭
        // Phase 2 实现流程：
        //   1. 根据 streamId 从映射中移除 OutboundSession
        //   2. 关闭到目标站点的 Outbound 连接（或归还连接池）
        //   3. 释放该会话占用的所有资源（缓冲区、计数器等）
        return Response.ok();
    }
}
```

#### 3.3.3 服务端 Filter 链组装

通过 `@Activate` 注解的 `group` 属性区分客户端/服务端 Filter，启动时自动过滤：

```java
// 服务端 Filter 组装逻辑（位于 ProxyRemoteServer 启动流程中）
FilterChainBuilder chainBuilder = ExtensionLoader.getLoader(FilterChainBuilder.class)
        .getDefaultExtension();

// 最终 Invoker（链末端）
Invoker dispatchInvoker = new DispatchInvoker(bizExecutor);

// 通过 SPI 自动发现 group="server" 的 Filter 并按 @Order 排序
List<Filter> filters = ExtensionLoader.getLoader(Filter.class)
        .getActivateExtensions("server");
Invoker invokerChain = chainBuilder.build(dispatchInvoker, filters);

// 将组装好的 invokerChain 传给 Exchanger.bind()
ExchangeServer server = exchanger.bind(url, invokerChain);
```

---

### 3.4 启动入口 `ProxyRemoteServer`

定义位置：`proxy-remote/src/main/java/com/proxy/remote/ProxyRemoteServer.java`

```java
package com.proxy.remote;

import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.*;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.remote.dispatch.DispatchInvoker;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程代理服务端启动入口
 * <p>
 * 职责：
 * 1. 加载配置（监听端口、加密方式、线程池大小等）
 * 2. 组装 Filter 链 → Invoker
 * 3. 调用 Exchanger.bind() 启动服务
 * </p>
 */
public class ProxyRemoteServer {

    private ExchangeServer exchangeServer;
    private ExecutorService bizExecutor;

    public void start() {
        // 1. 加载配置
        URL url = loadConfig();

        // 2. 创建业务线程池（处理实际的请求分发逻辑）
        int bizThreads = url.getParameter("bizThreads", 200);
        bizExecutor = Executors.newFixedThreadPool(bizThreads);

        // 3. 构建 DispatchInvoker（Filter 链末端）
        Invoker dispatchInvoker = new DispatchInvoker(bizExecutor);

        // 4. 组装 Filter 链（SPI 自动发现 group="server" 的 Filter）
        FilterChainBuilder chainBuilder = ExtensionLoader
                .getLoader(FilterChainBuilder.class).getDefaultExtension();
        List<Filter> filters = ExtensionLoader
                .getLoader(Filter.class).getActivateExtensions("server");
        Invoker invokerChain = chainBuilder.build(dispatchInvoker, filters);

        // 5. 启动 ExchangeServer（bind 内部启动 Netty Server 并拼接 Pipeline）
        Exchanger exchanger = ExtensionLoader
                .getLoader(Exchanger.class).getDefaultExtension();
        exchangeServer = exchanger.bind(url, invokerChain);
    }

    public void shutdown() {
        if (exchangeServer != null) {
            exchangeServer.close();
        }
        if (bizExecutor != null) {
            bizExecutor.shutdown();
        }
    }

    private URL loadConfig() {
        // 从 remote.yml 加载配置，构建 URL
        // URL 包含：host、port、cipher、cipherKey、workerThreads、bizThreads 等参数
        ...
    }
}
```

---

## 4. 线程模型

```
┌─────────────────────────────────────────────────────────────────┐
│                        Thread Model                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  [Boss EventLoopGroup]  (1 thread)                               │
│     └── Accept 新连接 → 注册到 Worker                             │
│                                                                   │
│  [Worker EventLoopGroup]  (CPU×2 threads)                        │
│     └── IO 读写 → 解码 → 解密 → 调用 handler.onMessage()         │
│     └── handler.onMessage() 内部：                                │
│           ├── 构建 Invocation                                     │
│           ├── invoker.invoke(invocation) → 返回 Future            │
│           └── 【立即返回，不阻塞 IO 线程】                         │
│                                                                   │
│  [Business ThreadPool]  (可配置，默认200线程)                     │
│     └── DispatchInvoker.invoke() 内部：                           │
│           ├── 执行业务逻辑（后续迭代：建连目标、转发数据）         │
│           └── future.complete(response)                           │
│                                                                   │
│  [Future Callback]  (在 bizExecutor 线程中执行)                   │
│     └── whenComplete → buildReply → ctx.writeAndFlush(reply)     │
│         （writeAndFlush 只是提交到 IO 线程的任务队列，非阻塞）    │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

关键原则：

- IO 线程（Worker EventLoop）仅负责网络读写和协议编解码，单次执行时间必须在微秒级。
- 任何可能耗时的操作（DNS 解析、建立 Outbound 连接、等待目标服务器响应）必须在业务线程池中执行。
- `ctx.writeAndFlush()` 是线程安全的，可在任意线程回调中执行，Netty 内部会判断当前线程是否为 EventLoop 线程，若非则提交到 EventLoop 任务队列。
- Invoker 链内部若涉及阻塞操作，应提交到独立的业务线程池（`bizExecutor`），通过 `CompletableFuture` 返回保持全链路异步。

---

## 5. Pipeline 设计

### 5.1 服务端 Pipeline（双层结构）

```
┌──────────────────────────────────────────────────────────────┐
│              Server TCP Parent Channel Pipeline                 │
│  (每条客户端 TCP 连接一个)                                      │
├──────────────────────────────────────────────────────────────┤
│                                                                │
│  网络字节流                                                     │
│    → SslHandler               (TLS 解密, 可选)                 │
│    → Http2FrameCodec          (HTTP/2 帧编解码, forServer)     │
│    → Http2MultiplexHandler    (按 streamId 分发到子 Channel)   │
│                                                                │
└──────────────────────────────────────────────────────────────┘
                          │
                          │ 每个客户端发起的 HTTP/2 Stream
                          ▼
┌──────────────────────────────────────────────────────────────┐
│              Per-Stream SubChannel Pipeline                     │
│  (每个 HTTP/2 Stream 一个，由 Http2MultiplexHandler 自动创建)   │
├──────────────────────────────────────────────────────────────┤
│                                                                │
│  Inbound (接收方向):                                            │
│    Http2DataFrame (由 Http2MultiplexHandler 分发)               │
│    → CipherDecodeHandler      (解密 data 字段)                  │
│    → ProxyMessageDecoder      (Http2DataFrame → ProxyMessage)  │
│    → IdleStateHandler         (空闲检测)                        │
│    → HeartbeatHandler         (心跳请求直接响应)                │
│    → ServerChannelHandler     (调用 invoker.invoke(), ctx 回写) │
│                                                                │
│  Outbound (发送方向):                                           │
│    ← ServerChannelHandler     (ctx.writeAndFlush(ProxyMessage))│
│    ← CipherEncodeHandler      (加密 data 字段)                  │
│    ← ProxyMessageEncoder      (ProxyMessage → Http2DataFrame)  │
│    (由 Http2MultiplexHandler 向上传递到父 Channel 的帧编码器)   │
│                                                                │
└──────────────────────────────────────────────────────────────┘
```

**为什么是双层结构：** `ProxyMessageDecoder` 继承 `MessageToMessageDecoder<Http2DataFrame>`，期望接收 `Http2DataFrame` 类型。而 `Http2DataFrame` 只有 `Http2MultiplexHandler` 将 HTTP/2 帧按 streamId 分发到子 Channel 后才会产生。父 Channel 上的消息类型是 `Http2Frame`（帧级别），不会被 `ProxyMessageDecoder` 匹配。因此编解码和加密 Handler 必须放在子 Channel 上。

### 5.2 Handler 复用情况

| Handler | 客户端已有 | 服务端复用 | 说明 |
|---------|------------|------------|------|
| ProxyMessageDecoder | ✅ | ✅ | 编解码逻辑相同 |
| ProxyMessageEncoder | ✅ | ✅ | 编解码逻辑相同 |
| CipherDecodeHandler | ✅ | ✅ | 加密算法相同 |
| CipherEncodeHandler | ✅ | ✅ | 加密算法相同 |
| HeartbeatHandler | ✅ | ✅ | 心跳逻辑相同 |
| ClientMessageHandler | ✅ | ❌ | 客户端专用（调用 DefaultFuture） |
| ServerChannelHandler | ❌ | ✅ | **新增**，服务端专用 |

---

## 6. 配置设计

### 6.1 服务端配置文件

文件位置：`proxy-remote/src/main/resources/remote.yml`

```yaml
# ============================================
# 远程代理服务端配置
# ============================================

# 服务端监听配置
server:
  host: "0.0.0.0"
  port: 8443
  # 业务线程池大小（处理请求分发，执行 Invoker 链）
  bizThreads: 200
  # Netty Worker IO 线程数（0 = CPU核心数 × 2）
  workerThreads: 0
  # Boss 线程数（Accept 连接）
  bossThreads: 1
  # 最大并发连接数（超过后拒绝新连接）
  maxConnections: 10000
  # 空闲连接超时时间（秒，超时后主动断开）
  idleTimeout: 300
  # TCP backlog 队列大小
  backlog: 1024

# 加密配置（必须与客户端一致）
crypto:
  # 加密算法：aes-gcm / chacha20 / aes-ctr-hmac / none
  cipher: "aes-gcm"
  # 32 字节密钥
  key: "your-32-byte-secret-key-here!!!"

# 服务端 Filter 配置
filters:
  rateLimit:
    enabled: true
    # 单机最大 QPS
    maxQps: 50000
  monitor:
    enabled: true
    # 指标上报间隔（毫秒）
    reportIntervalMs: 60000
  accessLog:
    enabled: true
    logLevel: "INFO"

# Outbound 连接配置（Phase 2）
outbound:
  # 连接目标服务器超时
  connectTimeoutMs: 5000
  # 等待目标服务器响应超时
  readTimeoutMs: 30000
  # Outbound 连接池最大连接数
  maxPoolSize: 1000
  # 是否保持长连接
  keepAlive: true
```

---

## 7. 模块结构规划

### 7.1 proxy-common 变更

```
proxy-common/src/main/java/com/proxy/common/
├── exchange/
│   ├── ExchangeClient.java          [不变]
│   ├── ExchangeServer.java          [新增]
│   ├── Exchanger.java               [修改：新增 bind default method]
│   ├── ProxyRequest.java            [不变]
│   ├── ProxyResponse.java           [不变]
│   └── Tunnel.java                  [不变]
├── filter/
│   └── ...                          [不变，全部复用]
└── transport/
    ├── Client.java                  [不变]
    ├── MessageHandler.java          [不变]
    ├── Server.java                  [新增]
    ├── Transporter.java             [修改：新增 bind default method]
    └── TransportException.java      [不变]
```

### 7.2 proxy-exchange 变更

```
proxy-exchange/src/main/java/com/proxy/exchange/header/
├── DefaultFuture.java               [不变]
├── ExchangeHandler.java             [不变，客户端专用]
├── HeaderExchangeClient.java        [不变]
├── HeaderExchangeServer.java        [新增]
├── HeaderExchanger.java             [修改：实现 bind 方法]
├── RequestIdGenerator.java          [不变]
└── ServerExchangeHandler.java       [新增]
```

### 7.3 proxy-transport-netty 变更

```
proxy-transport-netty/src/main/java/com/proxy/transport/netty/
├── NettyClient.java                 [不变]
├── NettyServer.java                 [新增]
├── NettyTransporter.java            [修改：实现 bind 方法]
├── codec/
│   └── ProxyCodec.java              [不变]
├── handler/
│   ├── CipherDecodeHandler.java     [不变，复用]
│   ├── CipherEncodeHandler.java     [不变，复用]
│   ├── ClientMessageHandler.java    [不变，客户端专用]
│   ├── HeartbeatHandler.java        [不变，复用]
│   ├── ProxyMessageDecoder.java     [不变，复用]
│   ├── ProxyMessageEncoder.java     [不变，复用]
│   └── ServerChannelHandler.java    [新增，服务端专用]
└── pool/
    └── ...                          [不变]
```

### 7.4 proxy-remote 新增

```
proxy-remote/src/main/java/com/proxy/remote/
├── ProxyRemoteServer.java           [新增：启动入口 + main 方法]
├── config/
│   └── RemoteConfig.java            [新增：YAML 配置解析]
├── dispatch/
│   ├── DispatchInvoker.java         [新增：请求分派 Invoker]
│   └── ServerInvokerBuilder.java    [新增：Filter 链构建工具]
└── outbound/                        [Phase 2，本期仅建目录]
    └── package-info.java
```

---

## 8. 实现计划

### Phase 1：服务端骨架（本期核心目标）

| 序号 | 任务项 | 涉及模块 | 优先级 | 预估工作量 |
|------|--------|----------|--------|------------|
| 1.1 | 定义 `Server` 接口 | proxy-common | P0 | 0.5h |
| 1.2 | `Transporter` 接口新增 `bind` default method | proxy-common | P0 | 0.5h |
| 1.3 | 定义 `ExchangeServer` 接口 | proxy-common | P0 | 0.5h |
| 1.4 | `Exchanger` 接口新增 `bind` default method | proxy-common | P0 | 0.5h |
| 1.5 | 实现 `NettyServer`（ServerBootstrap + Pipeline 拼接） | proxy-transport-netty | P0 | 4h |
| 1.6 | 实现 `ServerChannelHandler`（Pipeline 尾端处理器） | proxy-transport-netty | P0 | 2h |
| 1.7 | `NettyTransporter` 实现 `bind` 方法 | proxy-transport-netty | P0 | 1h |
| 1.8 | 实现 `ServerExchangeHandler` | proxy-exchange | P0 | 2h |
| 1.9 | 实现 `HeaderExchangeServer` | proxy-exchange | P0 | 1h |
| 1.10 | `HeaderExchanger` 实现 `bind` 方法 | proxy-exchange | P0 | 1h |
| 1.11 | 实现 `DispatchInvoker`（桩实现） | proxy-remote | P0 | 1h |
| 1.12 | 实现 `ServerInvokerBuilder`（Filter 链构建） | proxy-remote | P0 | 1h |
| 1.13 | 实现 `RemoteConfig`（YAML 配置加载） | proxy-remote | P0 | 1.5h |
| 1.14 | 实现 `ProxyRemoteServer`（启动入口） | proxy-remote | P0 | 2h |
| 1.15 | 集成测试：客户端发送 → 服务端接收并响应 | 全模块 | P0 | 3h |
| 1.16 | SPI 注册文件补充 | 各模块 resources | P0 | 0.5h |

**Phase 1 合计预估：~22h**

### Phase 2：Outbound 出站连接

| 序号 | 任务项 | 优先级 |
|------|--------|--------|
| 2.1 | 实现 `OutboundSession`（出站会话，持有目标连接 + 回写上下文） | P0 |
| 2.2 | 实现 `OutboundConnector`（异步建连器，按需建连，不做池化） | P0 |
| 2.3 | 实现 `OutboundHandler`（目标响应中继，收字节 → 推回客户端 Stream） | P0 |
| 2.4 | 实现 `SessionManager`（streamId → OutboundSession 映射管理） | P0 |
| 2.5 | 升级 `DispatchInvoker`（桩实现 → 完整 Outbound 逻辑） | P0 |
| 2.6 | 修改 `ProxyRemoteServer`（初始化 OutboundConnector 并注入） | P0 |
| 2.7 | 修改 `RemoteConfig`（新增 Outbound 配置项） | P0 |
| 2.8 | 集成测试：完整 CONNECT → DATA 透传 → DISCONNECT 链路验证 | P0 |
| 2.9 | 异步 DNS 解析 + 本地缓存（后续增强） | P2 |

---

## 8.1 Phase 2 详细设计：Outbound 出站连接

### 8.1.1 定位与边界

Outbound 是远程服务端收到客户端请求后，向真正的目标网站（如 google.com:443）发起连接并双向透传流量的子系统。它与 Inbound（客户端到服务端）链路完全独立，本质上是一个**轻量级的按需建连器 + 透明字节流中继器**。

**不使用连接池**的原因：代理场景下用户访问的目标地址千变万化，连接池中缓存的连接几乎无法复用，反而增加管理复杂度和资源占用。因此采用一 Stream 一连接的简单模型，Stream 关闭时连接即销毁。

**目标地址获取方式**：proxy-local 在收到用户的 SOCKS5/HTTP CONNECT 请求时，已经解析出了目标 host 和 port，并将其填入 `ProxyMessage` 的 `host` 和 `port` 字段。proxy-remote 的 `ServerChannelHandler` 在构建 `Invocation` 时会从 `ProxyMessage` 中提取这两个字段，`DispatchInvoker` 通过 `invocation.getTargetHost()` 和 `invocation.getTargetPort()` 即可获得目标地址。**不需要额外定义 CONNECT 帧类型或从流量中嗅探目标地址**——现有协议已经原生支持。

关键约束：
- **裸 TCP 透传**：目标网站不理解 ProxyMessage 协议，也不走 HTTP/2 多路复用。Outbound 连接就是普通的 TCP 连接（或由客户端与目标端到端 TLS 加密的字节流），服务端只做字节搬运，不解析内容。
- **一 Stream 一连接，按需建连不池化**：客户端的每个 HTTP/2 Stream 对应一条到目标网站的独立 TCP 连接。不做连接池，连接随 Stream 生命周期创建和销毁。HTTPS 场景下客户端与目标直接握手 TLS，服务端看到的是密文字节流，无法复用。
- **全异步非阻塞**：Outbound 建连、数据转发、关闭全部在业务线程池 + Netty EventLoop 中异步完成，不阻塞 Inbound IO 线程。

### 8.1.2 整体数据流

```
客户端 HTTP/2 Stream                 远程服务端                          目标网站
─────────────────                  ─────────────                      ──────────
                                        
CONNECT(host:port) ─────────→ DispatchInvoker.handleConnect()
                                    │ 异步建连：Bootstrap.connect(host, port)
                                    │ 注册映射：streamId → OutboundChannel
                                    │ 返回 Response.ok()
                    ←───────── OK 响应                              ←── TCP 握手完成
                                        
DATA(payload) ──────────────→ DispatchInvoker.handleData()
                                    │ 按 streamId 取出 OutboundChannel
                                    │ outboundChannel.writeAndFlush(payload)
                                    │                                ─→ 字节到达目标
                                    │                                ←─ 目标响应字节
                                    │ OutboundHandler.channelRead() 收到响应
                                    │ 通过 inboundCtx.writeAndFlush() 推回客户端
                    ←───────── DATA(response payload)
                                        
DISCONNECT ─────────────────→ DispatchInvoker.handleDisconnect()
                                    │ 按 streamId 取出 OutboundChannel
                                    │ outboundChannel.close()
                                    │ 移除映射，释放资源
                    ←───────── OK 响应
```

### 8.1.3 核心组件设计

#### `OutboundSession` —— 出站会话

每个客户端 Stream 对应一个 `OutboundSession`，持有到目标网站的 TCP 连接和回写客户端的上下文。

```java
package com.proxy.remote.outbound;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * 出站会话 —— 绑定一个客户端 Stream 到一条目标 TCP 连接
 * <p>
 * 生命周期：
 *   CONNECT 到达 → 创建 Session → 异步建连 → 建连成功(ACTIVE)
 *   DATA 到达    → 通过 outboundChannel 转发
 *   DISCONNECT   → 关闭 outboundChannel → 销毁 Session
 * </p>
 */
public class OutboundSession {

    /** 出站连接（到目标网站的 TCP Channel） */
    private volatile Channel outboundChannel;

    /** 入站上下文（回写客户端响应用，通过闭包从 ServerChannelHandler 传入） */
    private final ChannelHandlerContext inboundCtx;

    /** 目标地址 */
    private final String targetHost;
    private final int targetPort;

    /** 会话状态 */
    private volatile SessionState state = SessionState.CONNECTING;

    public enum SessionState {
        CONNECTING,  // 正在建连
        ACTIVE,      // 连接就绪，可转发数据
        CLOSED       // 已关闭
    }

    public OutboundSession(ChannelHandlerContext inboundCtx, 
                           String targetHost, int targetPort) {
        this.inboundCtx = inboundCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /** 建连完成后设置 outbound channel */
    public void setOutboundChannel(Channel channel) {
        this.outboundChannel = channel;
        this.state = SessionState.ACTIVE;
    }

    /** 转发数据到目标 */
    public void forward(byte[] data) {
        if (state == SessionState.ACTIVE && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(
                    outboundChannel.alloc().buffer().writeBytes(data));
        }
    }

    /** 回写响应到客户端 Stream */
    public void writeBack(byte[] data) {
        if (inboundCtx.channel().isActive()) {
            // 构建 ProxyMessage 响应，通过 inbound Stream 回推客户端
            ProxyMessage reply = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .data(data)
                    .build();
            inboundCtx.writeAndFlush(reply);
        }
    }

    /** 关闭出站连接 */
    public void close() {
        state = SessionState.CLOSED;
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.close();
        }
    }

    // getter methods...
}
```

#### `OutboundConnector` —— 出站连接器

负责异步建立到目标网站的 TCP 连接，使用共享的 EventLoopGroup（复用 Inbound 的 Worker 线程）。

```java
package com.proxy.remote.outbound;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.CompletableFuture;

/**
 * 出站连接器 —— 异步建立到目标网站的 TCP 连接
 * <p>
 * 设计要点：
 * - 复用 Worker EventLoopGroup 作为 Outbound 的 IO 线程（避免额外线程池开销）
 * - 纯裸 TCP 连接，不加任何协议层（字节流透传）
 * - Pipeline 仅挂载一个 OutboundHandler 做数据中继
 * </p>
 */
public class OutboundConnector {

    private final Bootstrap bootstrap;

    public OutboundConnector(EventLoopGroup workerGroup, int connectTimeoutMs) {
        this.bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs);
    }

    /**
     * 异步连接目标地址
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param session 关联的出站会话（用于回写数据到客户端）
     * @return 连接结果 Future
     */
    public CompletableFuture<Channel> connect(String host, int port, 
                                               OutboundSession session) {
        CompletableFuture<Channel> future = new CompletableFuture<>();

        bootstrap.clone()
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("outbound-handler",
                                new OutboundHandler(session));
                    }
                })
                .connect(host, port)
                .addListener((ChannelFutureListener) cf -> {
                    if (cf.isSuccess()) {
                        future.complete(cf.channel());
                    } else {
                        future.completeExceptionally(cf.cause());
                    }
                });

        return future;
    }
}
```

#### `OutboundHandler` —— 目标站点响应中继器

挂在 Outbound Channel Pipeline 上，收到目标网站的响应字节后，通过 `OutboundSession.writeBack()` 推回客户端。

```java
package com.proxy.remote.outbound;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Outbound Channel Pipeline 上的唯一 Handler
 * <p>
 * 职责极简：
 *   目标网站返回字节 → 读取 ByteBuf → 通过 session.writeBack() 推回客户端 Inbound Stream
 * 
 * Pipeline 结构：
 *   Outbound Channel (到目标网站的裸 TCP):
 *     └── OutboundHandler (仅此一个)
 * 
 * 与 Inbound Pipeline（HTTP/2 + 加密 + 编解码）的对比：
 *   Outbound 不需要任何编解码——它透传的是客户端与目标网站之间的原始字节流
 *   （HTTPS 场景下这些字节就是 TLS 密文，服务端不解析也不需要解析）
 * </p>
 */
public class OutboundHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final OutboundSession session;

    public OutboundHandler(OutboundSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // 目标网站返回的数据 → 推回客户端
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        session.writeBack(data);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 目标网站主动断开连接 → 通知客户端
        session.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        session.close();
        ctx.close();
    }
}
```

#### `SessionManager` —— 会话管理器

管理 `streamId → OutboundSession` 的全局映射，提供增删查和生命周期管理。

```java
package com.proxy.remote.outbound;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话管理器 —— streamId 到 OutboundSession 的映射
 * <p>
 * 核心数据结构极简：一张 ConcurrentHashMap。
 * 
 * 生命周期管理：
 *   CONNECT  → register(streamId, session)
 *   DATA     → get(streamId) → session.forward(data)
 *   DISCONNECT → remove(streamId) → session.close()
 *   连接异常  → remove(streamId) → session.close()
 * </p>
 */
public class SessionManager {

    private final ConcurrentMap<String, OutboundSession> sessions = new ConcurrentHashMap<>();

    /** CONNECT 时注册会话 */
    public void register(String streamId, OutboundSession session) {
        sessions.put(streamId, session);
    }

    /** DATA 时获取会话 */
    public OutboundSession get(String streamId) {
        return sessions.get(streamId);
    }

    /** DISCONNECT 或异常时移除并关闭会话 */
    public OutboundSession remove(String streamId) {
        OutboundSession session = sessions.remove(streamId);
        if (session != null) {
            session.close();
        }
        return session;
    }

    /** 获取当前活跃会话数（监控用） */
    public int activeCount() {
        return sessions.size();
    }

    /** 服务关闭时清理所有会话 */
    public void closeAll() {
        sessions.values().forEach(OutboundSession::close);
        sessions.clear();
    }
}
```

### 8.1.4 DispatchInvoker 完整实现（Phase 2）

Phase 1 的桩实现升级为完整的出站逻辑：

```java
public class DispatchInvoker implements Invoker {

    private final ExecutorService bizExecutor;
    private final OutboundConnector connector;
    private final SessionManager sessionManager;

    public DispatchInvoker(ExecutorService bizExecutor, 
                           OutboundConnector connector) {
        this.bizExecutor = bizExecutor;
        this.connector = connector;
        this.sessionManager = new SessionManager();
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) {
        CompletableFuture<Response> future = new CompletableFuture<>();

        bizExecutor.execute(() -> {
            try {
                Response response = dispatch(invocation);
                future.complete(response);
            } catch (RejectedExecutionException e) {
                future.complete(Response.error(503, "Server overloaded"));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private Response dispatch(Invocation invocation) {
        switch (invocation.getType()) {
            case CONNECT:    return handleConnect(invocation);
            case DATA:       return handleData(invocation);
            case DISCONNECT: return handleDisconnect(invocation);
            default:         return Response.error("Unsupported type: " + invocation.getType());
        }
    }

    private Response handleConnect(Invocation invocation) {
        String targetHost = invocation.getTargetHost();
        int targetPort = invocation.getTargetPort();
        String streamId = (String) invocation.getAttachment("streamId");
        ChannelHandlerContext inboundCtx = 
                (ChannelHandlerContext) invocation.getAttachment("inboundCtx");

        // 1. 创建 OutboundSession
        OutboundSession session = new OutboundSession(inboundCtx, targetHost, targetPort);
        sessionManager.register(streamId, session);

        // 2. 异步建连到目标（不阻塞当前业务线程等结果）
        connector.connect(targetHost, targetPort, session)
                .whenComplete((channel, ex) -> {
                    if (ex != null) {
                        // 建连失败，清理 session
                        sessionManager.remove(streamId);
                    } else {
                        // 建连成功，绑定 outbound channel
                        session.setOutboundChannel(channel);
                    }
                });

        // 3. 立即返回 OK —— 利用 CONNECT 与首个 DATA 之间的时间窗口完成建连
        //    如果建连比首个 DATA 慢，handleData 中有等待/缓冲机制兜底
        return Response.ok();
    }

    private Response handleData(Invocation invocation) {
        String streamId = (String) invocation.getAttachment("streamId");
        OutboundSession session = sessionManager.get(streamId);

        if (session == null) {
            return Response.error("No session found for stream: " + streamId);
        }

        if (session.getState() == OutboundSession.SessionState.CONNECTING) {
            // Outbound 连接尚未就绪 → 等待（带超时）
            // 实现方式：轮询 + Thread.sleep 或使用 session 内部的 CountDownLatch/CompletableFuture
            if (!session.awaitActive(5000)) {
                return Response.error("Outbound connection timeout");
            }
        }

        // 转发数据到目标网站
        session.forward(invocation.getData());

        // 注意：目标网站的响应不在这里返回
        // 响应由 OutboundHandler.channelRead0() 异步推回客户端
        // 这里只确认"转发成功"
        return Response.ok();
    }

    private Response handleDisconnect(Invocation invocation) {
        String streamId = (String) invocation.getAttachment("streamId");
        sessionManager.remove(streamId); // 内部会 close outbound channel
        return Response.ok();
    }

    /** 服务关闭时清理所有会话 */
    public void shutdown() {
        sessionManager.closeAll();
    }
}
```

### 8.1.5 线程模型（含 Outbound）

```
┌────────────────────────────────────────────────────────────────────────┐
│                     Thread Model (Full)                                    │
├────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  [Inbound Worker EventLoop]  (CPU×2 threads)                            │
│     └── 接收客户端 HTTP/2 Stream 数据                                     │
│     └── 解码 → 解密 → ServerChannelHandler → invoker.invoke()            │
│     └── 【不阻塞，立即返回 Future】                                       │
│                                                                          │
│  [Business ThreadPool]  (可配置，默认200线程)                             │
│     └── DispatchInvoker.dispatch()                                       │
│         ├── CONNECT: 创建 Session + 异步建连（fire-and-forget）           │
│         ├── DATA: 取 Session → forward() → outboundChannel.writeAndFlush │
│         └── DISCONNECT: remove Session → close outbound channel          │
│                                                                          │
│  [Outbound IO] (复用 Inbound Worker EventLoopGroup)                      │
│     └── OutboundConnector.connect() 的实际 IO 在 Worker 线程完成          │
│     └── OutboundHandler.channelRead0() 收到目标响应                       │
│         → session.writeBack() → inboundCtx.writeAndFlush()              │
│         （跨 EventLoop 提交任务到 Inbound Stream 的 EventLoop 队列）      │
│                                                                          │
│  关键点：Outbound 复用 Worker EventLoopGroup，不新建线程池                │
│          Netty 的 EventLoop 是线程安全的，跨 Channel 的 writeAndFlush     │
│          会自动提交到目标 Channel 的 EventLoop 执行                        │
│                                                                          │
└────────────────────────────────────────────────────────────────────────┘
```

### 8.1.6 Outbound Pipeline 设计

```
Outbound Channel (到目标网站的裸 TCP 连接):
┌───────────────────────────────────────────────────┐
│  SocketChannel (NioSocketChannel)                    │
│                                                       │
│  Pipeline（极简，只有一个 Handler）:                    │
│    └── OutboundHandler                               │
│          ├── channelRead0: 收到目标响应 → writeBack   │
│          ├── channelInactive: 目标断开 → close session│
│          └── exceptionCaught: 异常 → close all       │
│                                                       │
│  不需要：                                              │
│    ✗ Http2FrameCodec（目标不说 HTTP/2）               │
│    ✗ CipherHandler（TLS 是端到端的，服务端不解密）     │
│    ✗ ProxyMessage 编解码（透传原始字节）               │
│    ✗ HeartbeatHandler（TCP 级别 keepalive 即可）      │
└───────────────────────────────────────────────────┘

对比 Inbound Stream SubChannel（功能丰富）:
┌───────────────────────────────────────────────────┐
│  Http2StreamChannel (子 Channel)                     │
│                                                       │
│  Pipeline（完整处理链）:                               │
│    ├── CipherDecodeHandler                           │
│    ├── ProxyMessageDecoder                           │
│    ├── IdleStateHandler                              │
│    ├── HeartbeatHandler                              │
│    ├── ServerChannelHandler                          │
│    ├── CipherEncodeHandler                           │
│    └── ProxyMessageEncoder                           │
└───────────────────────────────────────────────────┘
```

### 8.1.7 关键设计决策

#### 为什么一 Stream 一 TCP 连接（不做连接复用）

| 因素 | 分析 |
|------|------|
| HTTPS 场景 | 客户端与目标站点端到端 TLS，服务端只看到密文。不同 Stream（不同域名/不同会话）的 TLS 会话无法共享，强制一对一。 |
| HTTP 场景 | 理论上同域名可复用 TCP 连接（HTTP Keep-Alive），但引入连接池 + 请求队列 + Host 路由逻辑的复杂度远超收益。 |
| 隧道语义 | SOCKS5/HTTP CONNECT 建立的隧道本身就是"一个逻辑连接"，天然一对一映射最简单。 |
| 简单可靠 | 一对一映射无状态串扰风险，Stream 关闭时直接 close TCP，资源释放干净利落。 |

结论：采用**一 Stream 一 TCP 连接**的简单模型，**不做连接池**。目标地址千变万化的代理场景下，池化连接几乎不会被复用，徒增管理成本。

#### 为什么 Outbound 复用 Worker EventLoopGroup

- 建连和 IO 读写本身是轻量操作（微秒级），不需要独立线程池。
- 减少线程数量，降低上下文切换开销。
- Netty 的 `Bootstrap.group(existingGroup)` 天然支持共享 EventLoopGroup。
- 如果未来目标站点数量极大（>10K 并发出站连接），可独立拆分 Outbound EventLoopGroup，当前不需要。

#### inboundCtx 如何传递到 DispatchInvoker

`ServerChannelHandler` 在构建 Invocation 时，将 `ctx` 作为 attachment 传入：

```java
// ServerChannelHandler.channelRead0() 中
invocation.setAttachment("inboundCtx", ctx);
invocation.setAttachment("streamId", ctx.channel().id().asShortText());
```

`DispatchInvoker.handleConnect()` 从 attachment 中取出 ctx，传给 `OutboundSession`。后续 `OutboundHandler` 通过 session 持有的 inboundCtx 回写响应，形成完整的数据环路。

### 8.1.8 模块结构（Phase 2 新增）

```
proxy-remote/src/main/java/com/proxy/remote/
├── ProxyRemoteServer.java           [修改：初始化 OutboundConnector]
├── config/
│   └── RemoteConfig.java            [修改：加载 outbound 配置]
├── dispatch/
│   ├── DispatchInvoker.java         [修改：接入 SessionManager + Connector]
│   └── ServerInvokerBuilder.java    [不变]
└── outbound/
    ├── OutboundConnector.java       [新增：异步建连器]
    ├── OutboundHandler.java         [新增：目标响应中继]
    ├── OutboundSession.java         [新增：出站会话]
    └── SessionManager.java          [新增：会话管理]
```

### 8.1.9 配置项（Phase 2 补充）

```yaml
# Outbound 出站连接配置
outbound:
  # 连接目标服务器超时（毫秒）
  connectTimeoutMs: 5000
  # 等待 Outbound 连接就绪超时（毫秒，用于 handleData 中 CONNECTING 状态的等待）
  activeWaitTimeoutMs: 5000
  # TCP SO_KEEPALIVE
  keepAlive: true
  # TCP_NODELAY
  tcpNoDelay: true
```

### 8.1.10 异常处理与资源回收

| 异常场景 | 处理策略 |
|---------|---------|
| Outbound 建连超时 | `connector.connect()` Future 超时 → remove session → 回写错误响应给客户端 |
| Outbound 建连被拒绝（目标不可达） | connect Future 异常完成 → remove session → 回写 502 错误给客户端 |
| 目标网站主动断开 | `OutboundHandler.channelInactive()` → session.close() → 通知客户端 DISCONNECT |
| 客户端 Inbound Stream 断开 | `ServerChannelHandler.channelInactive()` → sessionManager.remove(streamId) → close outbound |
| 服务端 shutdown | `sessionManager.closeAll()` → 批量关闭所有 outbound 连接 |
| DATA 到达但 session 不存在 | 返回错误 Response（可能是 CONNECT 未到或已 DISCONNECT） |
| DATA 到达但 outbound 仍在 CONNECTING | `session.awaitActive(timeout)` 阻塞等待（在 bizExecutor 线程中，不影响 IO 线程） |

### 8.1.11 性能考量

- **零拷贝优化（后续增强）**：当前 `OutboundHandler.channelRead0()` 中 `msg.readBytes(data)` 会产生一次内存拷贝。后续可通过 `ByteBuf.retain()` + `CompositeByteBuf` 实现零拷贝透传，减少 GC 压力。
- **背压联动**：如果客户端消费慢导致 Inbound Channel 不可写（`channel.isWritable()` 为 false），Outbound 应暂停从目标读取数据（`outboundChannel.config().setAutoRead(false)`），形成端到端背压。目标站点的 TCP 窗口自然缩小。
- **首包延迟优化**：当前 CONNECT 帧到达后异步建连，利用 CONNECT 与首个 DATA 之间的时间窗口完成 TCP 握手。绝大多数场景下 DATA 到达时连接已就绪，无额外等待。极端情况下 `session.awaitActive(timeout)` 在业务线程中短暂等待，不影响 IO 线程。

---

## 9. 非功能性需求

### 9.1 性能指标

| 指标 | 目标值 | 测试条件 |
|------|--------|----------|
| 单机最大并发连接 | ≥ 10,000 | 8C16G 机器，Netty NIO |
| 请求处理延迟（P99） | ≤ 5ms | 不含 Outbound 网络耗时，纯框架开销 |
| 吞吐量 | ≥ 50,000 QPS | 纯转发场景，8 核机器 |
| 内存占用 | ≤ 512MB | 10K 并发连接 + 200 业务线程 |
| GC 停顿 | ≤ 50ms (P99) | G1 收集器，-Xmx512m |

### 9.2 可靠性

- 服务端进程异常退出时，所有客户端连接应感知到断连并触发容错逻辑（Failover 自动切换节点）。
- 单个客户端连接异常不影响其他连接的处理。
- 业务线程池满时，`DispatchInvoker` 应捕获 `RejectedExecutionException`，返回 503 错误响应而非静默丢弃。
- 心跳超时自动断开空闲连接，释放资源。

### 9.3 可观测性

- Monitor Filter 定期上报服务端关键指标：RT 分布、QPS、成功率、活跃连接数。
- AccessLog Filter 记录每次请求的来源 IP、目标地址、处理耗时、响应状态码。
- 支持通过 JMX 或 HTTP 端点暴露运行时指标（连接数、线程池利用率、内存使用）。
- 异常情况打印完整堆栈到日志，便于排障。

### 9.4 安全性

- 加密算法和密钥必须与客户端配置一致，不匹配时连接建立阶段即解码失败并断连。
- 绑定地址默认 `0.0.0.0`，生产环境建议通过防火墙/安全组限制来源 IP。
- 后续迭代可考虑 Token 认证机制（在 CONNECT 消息的 attachment 中携带认证信息）。

---

## 10. 兼容性与约束

### 10.1 向后兼容

- `Transporter` 和 `Exchanger` 接口新增的 `bind` 方法使用 Java 8 `default` 实现（抛出 `UnsupportedOperationException`），确保现有客户端代码无需修改即可编译通过。
- 现有 SPI 配置文件（`META-INF/proxy/`）无需变动，新增的 SPI 实现通过新文件注册。
- 客户端已有的 Filter 实现通过 `@Activate(group = "client")` 标记，与服务端 Filter 互不干扰。

### 10.2 技术约束

- Java 版本：1.8+（与现有项目一致，使用 `default` 方法但不依赖更高版本特性）。
- Netty 版本：4.1.108.Final（与现有依赖一致，不升级）。
- 不引入新的第三方依赖（加密层、编解码层完全复用 proxy-crypto 和 proxy-transport-netty 已有实现）。
- 配置文件格式沿用 SnakeYAML 解析。

---

## 11. 风险与对策

| 风险 | 影响级别 | 概率 | 对策 |
|------|----------|------|------|
| Transporter 接口变更导致第三方扩展编译失败 | 高 | 低 | 使用 default method；文档明确标注为"非 Breaking Change" |
| 服务端 IO 线程被意外阻塞导致雪崩 | 高 | 中 | 代码审查确保 invoker.invoke() 异步返回；增加线程监控告警 |
| HTTP/2 服务端 Multiplex 实现复杂度超预期 | 中 | 中 | 优先使用 Netty 内置的 Http2MultiplexHandler；可降级为单 Stream 模式验证 |
| 客户端/服务端加密配置不一致导致静默失败 | 中 | 中 | 解码失败时明确打印错误日志并主动断连；考虑握手阶段协商加密方式 |
| 业务线程池配置不当导致 OOM 或请求堆积 | 中 | 低 | 使用有界队列 + CallerRunsPolicy；配置项提供合理默认值和注释说明 |
| （若采用 Map 路由表方案）`requestId → Channel` 映射在异常路径下残留导致内存泄漏 | 中 | 中 | 连接断开时按 channel 批量清理（`channelInactive`）+ 为映射项设置超时扫描兜底；本期默认采用「内聚一层」方案规避此风险 |

---

## 12. 验收标准

### Phase 1 验收 Checklist

- [ ] 服务端能通过 `java -jar proxy-remote.jar` 正常启动，监听配置端口。
- [ ] 客户端通过 `Exchanger.connect()` 能成功连接到服务端（TCP + HTTP/2 握手通过）。
- [ ] 客户端发送 CONNECT 消息，服务端 `DispatchInvoker` 收到并返回 OK 响应。
- [ ] 客户端发送 DATA 消息，服务端正确接收 payload 并回显。
- [ ] 客户端发送 DISCONNECT 消息，服务端正确处理连接清理。
- [ ] 加密通信验证：AES-GCM 加解密双向正常，篡改数据能被检测。
- [ ] 服务端 Filter 链工作正常（RateLimit 超限返回错误、Monitor 输出指标、AccessLog 打印日志）。
- [ ] 心跳机制正常：客户端定时发送心跳，服务端正确响应。
- [ ] 压测验证：1000 并发连接，10000 QPS 持续 60s，IO 线程 CPU 占用正常（无阻塞迹象）。
- [ ] 异常场景：业务线程池满时返回 503 错误响应，不丢消息不阻塞 IO 线程。
- [ ] 优雅关闭：调用 shutdown 后，所有连接正常断开，无资源泄漏。

---

## 附录 A：术语表

| 术语 | 定义 |
|------|------|
| Inbound | 客户端到远程服务端方向的连接和数据流 |
| Outbound | 远程服务端到目标网站方向的连接和数据流 |
| Stream | HTTP/2 协议中的逻辑流，一条 TCP 连接上可承载多个并发 Stream |
| Invoker | 统一的调用抽象，Filter 链中每一环和末端处理器都实现此接口 |
| Filter | 过滤器，责任链中的一环，实现横切关注点（限流、监控、日志等） |
| SPI | Service Provider Interface，可插拔的服务发现与加载机制 |
| AEAD | Authenticated Encryption with Associated Data，认证加密 |
| Pipeline | Netty 的处理器链，消息按 Inbound/Outbound 方向依次经过各 Handler |
| EventLoop | Netty 的 IO 线程，负责一组 Channel 的读写事件循环 |
| bizExecutor | 业务线程池，执行可能耗时的请求处理逻辑，隔离 IO 线程 |

---

## 附录 B：与客户端架构的对称关系

```
              客户端 (connect)                    服务端 (bind)
            ─────────────────                  ─────────────────
SPI 接口     Transporter.connect()              Transporter.bind()
实现类       NettyClient                        NettyServer
包装层       Exchanger.connect()                Exchanger.bind()
实现类       HeaderExchangeClient               HeaderExchangeServer
Handler      ExchangeHandler (唤醒 Future)      ServerExchangeHandler (invoke → 回写)
链末端       ClientInvoker (发请求)              DispatchInvoker (处理请求)
Filter 链    @Activate(group="client")           @Activate(group="server")
```

这种对称性确保了框架的概念一致性：无论客户端还是服务端，开发者面对的都是同一套 SPI 抽象，学习成本最低，扩展最为自然。

> **关于 Handler 一行的说明**：上表展示的是**概念上的理想对称态**（服务端 `ServerExchangeHandler` 独立成层，与客户端 `ExchangeHandler` 镜像）。本期落地受 `MessageHandler.onMessage` 不携带 ctx 的限制，采用「内聚一层」方案（`ServerExchangeHandler` 逻辑合并进 `ServerChannelHandler`），详见 3.2.3。若需完全实现此对称结构，可采用 3.2.4 的 **Map 路由表方案**。

---

## 附录 C：参考资料

- 本项目 `README.md` 及现有源码实现
- Apache Dubbo 源码：`Exchanger` / `Transporter` / `HeaderExchanger` 分层设计
- Netty 官方文档：HTTP/2 Server Example、`Http2MultiplexHandler` 用法
- RFC 7540: Hypertext Transfer Protocol Version 2 (HTTP/2)
- RFC 1928: SOCKS Protocol Version 5
