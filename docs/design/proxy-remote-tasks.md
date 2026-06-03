# proxy-remote 服务端开发 — 子任务拆分

> 基于 `proxy-remote-server-design.md` Phase 1 + Phase 2 拆解，按依赖顺序排列。
> 每个任务满足 SDD 子任务规范：目标明确、输入输出清晰、验收标准可测试、粒度适中（单任务 0.5~4h）。

---

## 任务总览（依赖关系）

```
Task-1 ─┐
Task-2 ─┤
Task-3 ─┼─→ Task-5 ─→ Task-7 ─→ Task-8 ─→ Task-10 ─→ Task-12 ─→ Task-13
Task-4 ─┘         ↘                                ↗
                   Task-6 ─→ Task-9 ─→ Task-11 ──┘
```

---

## Task-1：定义 `Server` 接口

**目标**：在 proxy-common 的 transport 包中新增服务端抽象接口，定义服务实例的生命周期方法。

**输入**：设计文档 §3.1.1 的接口定义。

**输出文件**：`proxy-common/src/main/java/com/proxy/common/transport/Server.java`

**具体要求**：
- 定义 6 个方法：`start()`, `close()`, `isActive()`, `getActiveConnectionCount()`, `getBindAddress()`, `getBindPort()`
- 包含完整的 Javadoc 注释，说明每个方法的语义
- 不依赖任何第三方类库，纯接口定义

**验收标准**：
- 编译通过，无 warning
- `proxy-common` 模块 `mvn compile` 成功
- 接口方法签名与设计文档一致

**预估工时**：0.5h  
**依赖**：无

---

## Task-2：`Transporter` 接口新增 `bind` 方法

**目标**：为传输层 SPI 接口新增服务端绑定能力，保持向后兼容。

**输入**：设计文档 §3.1.2；现有 `Transporter.java` 仅有 `connect` 方法。

**输出文件**：`proxy-common/src/main/java/com/proxy/common/transport/Transporter.java`（修改）

**具体要求**：
- 新增 `default Server bind(URL url, MessageHandler handler) throws TransportException` 方法
- 使用 Java 8 `default` 实现，方法体抛出 `UnsupportedOperationException`
- 补充完整的 Javadoc（说明参数含义、Pipeline 拼接职责、与 connect 的对称关系）
- 不修改已有 `connect` 方法的签名和行为

**验收标准**：
- 编译通过，现有代码（NettyTransporter、测试）无需修改即可编译
- `proxy-common` + `proxy-transport-netty` 模块 `mvn compile` 成功
- 调用 `bind` 方法时抛出预期异常

**预估工时**：0.5h  
**依赖**：Task-1（需要 `Server` 接口的返回类型）

---

## Task-3：定义 `ExchangeServer` 接口

**目标**：在 proxy-common 的 exchange 包中新增交换层服务端抽象，包装底层 Server。

**输入**：设计文档 §3.2.1 的接口定义。

**输出文件**：`proxy-common/src/main/java/com/proxy/common/exchange/ExchangeServer.java`

**具体要求**：
- 定义 3 个方法：`close()`, `isActive()`, `getServer()`
- `getServer()` 返回 `com.proxy.common.transport.Server` 类型
- 包含完整 Javadoc，说明与 `ExchangeClient` 的对称关系和使用方式

**验收标准**：
- 编译通过
- `proxy-common` 模块 `mvn compile` 成功

**预估工时**：0.5h  
**依赖**：Task-1（`getServer()` 依赖 `Server` 接口）

---

## Task-4：`Exchanger` 接口新增 `bind` 方法

**目标**：为交换层 SPI 接口新增服务端绑定能力，接收已组装好的 Invoker 链。

**输入**：设计文档 §3.2.2；现有 `Exchanger.java` 仅有 `connect` 方法。

**输出文件**：`proxy-common/src/main/java/com/proxy/common/exchange/Exchanger.java`（修改）

**具体要求**：
- 新增 `default ExchangeServer bind(URL url, Invoker invoker)` 方法
- 使用 `default` 实现，方法体抛出 `UnsupportedOperationException`
- 参数说明：`invoker` 是已封装 Filter 链的最终调用器，Exchanger 不关心 Filter 编排细节
- 补充 Javadoc，说明 bind 方法内部组装 ServerExchangeHandler + 调用 Transporter.bind() 的职责

**验收标准**：
- 编译通过，现有代码无需修改
- `proxy-common` + `proxy-exchange` 模块 `mvn compile` 成功

**预估工时**：0.5h  
**依赖**：Task-3（需要 `ExchangeServer` 作为返回类型）

---

## Task-5：实现 `NettyServer`

**目标**：基于 Netty ServerBootstrap 实现服务端，支持 HTTP/2 多路复用接收客户端连接和 Stream。

**输入**：设计文档 §3.1.3 的完整实现方案和 Pipeline 架构图。

**输出文件**：`proxy-transport-netty/src/main/java/com/proxy/transport/netty/NettyServer.java`

**具体要求**：
- 实现 `Server` 接口的全部 6 个方法
- 构造函数接收 `URL` 和 `MessageHandler`
- `start()` 方法内部：
  - 创建 `bossGroup`（1线程）+ `workerGroup`（CPU×2 或 URL 配置）
  - 父 Channel Pipeline：（SslHandler 可选）+ `Http2FrameCodec.forServer()` + `Http2MultiplexHandler`
  - 子 Channel Pipeline（inboundStreamInitializer）：`CipherDecodeHandler` → `ProxyMessageDecoder` → `IdleStateHandler` → `HeartbeatHandler` → `ServerChannelHandler(handler)` + 出站 `CipherEncodeHandler` → `ProxyMessageEncoder`
  - 配置项：`maxStreams`（默认100）、`readIdleTimeout`（默认60s）、`backlog`（默认1024）
- `close()` 方法优雅关闭 bossGroup 和 workerGroup
- 连接计数：父 Channel 建立时 `incrementAndGet`，关闭时 `decrementAndGet`
- URL 参数读取使用 `url.getParameter(key, defaultValue)` 方法

**验收标准**：
- `proxy-transport-netty` 模块 `mvn compile` 成功
- 单元测试：能成功 bind 一个端口，`isActive()` 返回 true，`close()` 后 `isActive()` 返回 false
- Pipeline 中 Handler 顺序正确（通过日志或调试验证）

**预估工时**：4h  
**依赖**：Task-1, Task-2, Task-6（需要 `ServerChannelHandler`）

---

## Task-6：实现 `ServerChannelHandler`

**目标**：实现 Netty Pipeline 尾端的入站处理器，接收解码后的 ProxyMessage，通过 Invoker 链处理并异步回写响应。

**输入**：设计文档 §3.2.3 的"内聚一层"方案。

**输出文件**：`proxy-transport-netty/src/main/java/com/proxy/transport/netty/handler/ServerChannelHandler.java`

**具体要求**：
- 继承 `SimpleChannelInboundHandler<ProxyMessage>`
- 构造函数接收 `MessageHandler`（由上层传入，实际是 `ServerExchangeHandler`）
- `channelRead0` 方法：调用 `messageHandler.onMessage(message)`
- `exceptionCaught` 方法：调用 `messageHandler.onError(cause)`，并关闭 Channel
- `channelInactive` 方法：调用 `messageHandler.onDisconnected()`
- **关键**：由于采用"内聚一层"方案，实际上 `ServerExchangeHandler.onMessage()` 内部需要 ctx 来回写。解决方式：`ServerChannelHandler` 直接在 `channelRead0` 中持有 ctx 闭包完成 invoke + 回写（参见设计文档 §3.2.3 的代码示例）
- 也就是说，`ServerChannelHandler` 内部直接做：构建 Invocation → `invoker.invoke()` → `whenComplete` → `ctx.writeAndFlush(reply)`
- 构造函数改为直接接收 `Invoker`（而非 `MessageHandler`），以便直接操作

**验收标准**：
- 编译通过
- 与 `ProxyMessage` 类型匹配，能正确接收上游 Decoder 输出的消息
- 回写的响应消息包含正确的 requestId（与请求一致）
- 异步回调中使用 `ctx.writeAndFlush`，不阻塞 IO 线程

**预估工时**：2h  
**依赖**：无（仅依赖 proxy-common 中的 `Invoker`、`Invocation`、`Response`、`ProxyMessage` 等已有类）

---

## Task-7：`NettyTransporter` 实现 `bind` 方法

**目标**：在现有 NettyTransporter 中覆写 `bind` default 方法，创建并启动 NettyServer。

**输入**：设计文档 §3.1.4。

**输出文件**：`proxy-transport-netty/src/main/java/com/proxy/transport/netty/NettyTransporter.java`（修改）

**具体要求**：
- 覆写 `bind(URL url, MessageHandler handler)` 方法
- 内部创建 `NettyServer` 实例并调用 `start()`
- 启动成功打印日志：`NettyServer started on {host}:{port}`
- 启动失败包装为 `TransportException` 抛出
- 不修改现有 `connect` 方法

**验收标准**：
- 编译通过
- `proxy-transport-netty` 模块 `mvn compile` 成功
- 通过 SPI 加载 Transporter 后调用 bind，能成功启动服务端

**预估工时**：1h  
**依赖**：Task-2, Task-5

---

## Task-8：实现 `HeaderExchangeServer`

**目标**：实现交换层服务端包装类，持有底层 Server 实例并委托生命周期方法。

**输入**：设计文档 §3.2.5。

**输出文件**：`proxy-exchange/src/main/java/com/proxy/exchange/header/HeaderExchangeServer.java`

**具体要求**：
- 实现 `ExchangeServer` 接口
- 构造函数接收 `Server` 实例
- `close()` 委托给 `server.close()`
- `isActive()` 委托给 `server.isActive()`
- `getServer()` 返回持有的 Server 实例
- 简单包装层，不含复杂逻辑

**验收标准**：
- 编译通过
- 调用 `close()` 后底层 Server 的 `isActive()` 返回 false

**预估工时**：0.5h  
**依赖**：Task-3（`ExchangeServer` 接口）

---

## Task-9：实现 `ServerExchangeHandler`

**目标**：实现服务端交换层消息处理器（概念层，为 Map 路由表方案做预留），或在"内聚一层"方案中作为 `ServerChannelHandler` 内部逻辑的辅助类。

**输入**：设计文档 §3.2.3。

**输出文件**：`proxy-exchange/src/main/java/com/proxy/exchange/header/ServerExchangeHandler.java`

**具体要求**：
- 实现 `MessageHandler` 接口
- 构造函数接收 `Invoker`
- `onMessage` 方法：将 ProxyMessage 转换为 Invocation，调用 `invoker.invoke(invocation)`
- 心跳请求（`HEARTBEAT_REQUEST` 类型）识别并跳过 Invoker 链（日志记录即可，实际心跳由 Pipeline 中的 HeartbeatHandler 处理）
- **注意**：由于本期采用"内聚一层"方案，此类的 `onMessage` 在 `ServerChannelHandler` 中不会被实际调用（ctx 回写直接在 ServerChannelHandler 的闭包中完成）。此类作为架构预留存在，为后续 Map 路由表方案升级做准备
- 提供 `toInvocation(ProxyMessage)` 辅助方法供 `ServerChannelHandler` 复用

**验收标准**：
- 编译通过
- `toInvocation` 方法正确设置 targetHost、targetPort、data、type、requestId attachment

**预估工时**：1.5h  
**依赖**：Task-6（与 ServerChannelHandler 配合设计）

---

## Task-10：`HeaderExchanger` 实现 `bind` 方法

**目标**：在现有 HeaderExchanger 中覆写 `bind` 方法，组装服务端处理链并启动监听。

**输入**：设计文档 §3.2.6。

**输出文件**：`proxy-exchange/src/main/java/com/proxy/exchange/header/HeaderExchanger.java`（修改）

**具体要求**：
- 覆写 `bind(URL url, Invoker invoker)` 方法
- 内部流程：
  1. 通过 SPI 加载 Transporter
  2. 调用 `transporter.bind(url, handler)` 获得 Server（注意：由于"内聚一层"方案，这里需要将 Invoker 传递到 NettyServer/ServerChannelHandler。方式：通过 MessageHandler 适配或直接将 Invoker 设到 URL 的某个附加属性中由 NettyServer 读取）
  3. 包装成 `HeaderExchangeServer` 返回
- 打印日志：`HeaderExchanger bound ExchangeServer on {host}:{port}`

**设计决策说明**：
- 由于"内聚一层"方案中 `ServerChannelHandler` 需要直接持有 `Invoker`（而非通过 `MessageHandler` 间接调用），需要调整传递方式。建议方案：
  - 方案 A：`Transporter.bind()` 签名不变，但 `MessageHandler` 实现类（`ServerExchangeHandler`）内部持有 Invoker 的引用，`ServerChannelHandler` 从 `MessageHandler` 中取出 Invoker
  - 方案 B：`NettyServer` 构造函数额外接收 Invoker 参数（通过重载或 URL 传递）
  - **推荐方案 A**：保持接口不变，`ServerExchangeHandler` 暴露 `getInvoker()` 方法，`ServerChannelHandler` 通过类型转换获取

**验收标准**：
- 编译通过
- 通过 SPI 加载 Exchanger 后调用 `bind`，服务端正常启动监听

**预估工时**：2h  
**依赖**：Task-4, Task-7, Task-8, Task-9

---

## Task-11：实现 `DispatchInvoker`

**目标**：实现服务端 Filter 链末端的请求分派器，将请求提交到业务线程池异步处理。

**输入**：设计文档 §3.3.2。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/dispatch/DispatchInvoker.java`

**具体要求**：
- 实现 `Invoker` 接口
- 构造函数接收 `ExecutorService bizExecutor`
- `invoke` 方法：向 `bizExecutor` 提交任务，内部调用 `dispatch(invocation)` 按消息类型分派
- `dispatch` 方法根据 `invocation.getType()` 做 switch：
  - `CONNECT` → `handleConnect()`：本期返回 `Response.ok()`（桩实现）
  - `DATA` → `handleData()`：本期返回 `Response.ok(invocation.getData())`（回显）
  - `DISCONNECT` → `handleDisconnect()`：本期返回 `Response.ok()`（桩实现）
  - default → `Response.error("Unsupported message type")`
- 处理 `RejectedExecutionException`：线程池满时返回错误响应而非静默丢弃
- 每个 handle 方法添加 TODO 注释说明 Phase 2 的完整实现计划

**验收标准**：
- 编译通过
- 单元测试：发送 CONNECT/DATA/DISCONNECT 类型的 Invocation，验证返回正确的 Response
- 单元测试：模拟线程池满（使用 `Executors.newFixedThreadPool(1)` + CountDownLatch），验证不抛异常而是返回错误 Response

**预估工时**：1.5h  
**依赖**：无（仅依赖 proxy-common 中的 Invoker/Invocation/Response）

---

## Task-12：实现 `RemoteConfig` + 配置文件

**目标**：实现服务端 YAML 配置加载，解析为 URL 对象供各层使用。

**输入**：设计文档 §6.1 的配置文件格式。

**输出文件**：
- `proxy-remote/src/main/java/com/proxy/remote/config/RemoteConfig.java`
- `proxy-remote/src/main/resources/remote.yml`

**具体要求**：
- `RemoteConfig` 类通过 SnakeYAML 加载 `remote.yml`
- 提供 `loadURL()` 方法，将配置项转换为 `URL` 对象，包含以下参数：
  - host, port（监听地址）
  - bizThreads（业务线程池大小，默认 200）
  - workerThreads（Netty IO 线程数，默认 0 即 CPU×2）
  - bossThreads（Accept 线程数，默认 1）
  - cipher（加密算法名称）
  - cipherKey（加密密钥）
  - maxStreams（单连接最大并发 Stream 数，默认 100）
  - readIdleTimeout（空闲超时秒数，默认 60）
  - backlog（TCP backlog，默认 1024）
- `remote.yml` 提供完整的配置模板和注释说明
- 配置不存在时使用合理的默认值

**验收标准**：
- 编译通过
- 单元测试：加载默认配置文件，验证 URL 各参数正确
- 单元测试：缺失某配置项时使用默认值

**预估工时**：1.5h  
**依赖**：无

---

## Task-13：实现 `ProxyRemoteServer` 启动入口

**目标**：实现远程服务端的主启动类，串联配置加载 → Filter 链组装 → Exchanger.bind() 完整流程。

**输入**：设计文档 §3.4。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/ProxyRemoteServer.java`

**具体要求**：
- `start()` 方法完整流程：
  1. 调用 `RemoteConfig.loadURL()` 加载配置
  2. 创建业务线程池 `bizExecutor`（FixedThreadPool，大小从 URL 读取）
  3. 创建 `DispatchInvoker(bizExecutor)` 作为链末端
  4. 通过 SPI 加载 `FilterChainBuilder`，调用 `getActivateExtensions("server")` 获取服务端 Filter 列表
  5. 调用 `chainBuilder.build(dispatchInvoker, filters)` 构建完整 Invoker 链
  6. 通过 SPI 加载 `Exchanger`，调用 `exchanger.bind(url, invokerChain)` 启动服务
- `shutdown()` 方法：关闭 ExchangeServer + shutdown 线程池
- 提供 `main` 方法作为启动入口（添加 ShutdownHook 优雅关闭）
- 启动时打印 Banner 和配置摘要日志

**验收标准**：
- 编译通过
- `mvn package` 能打出可执行 jar
- `java -jar proxy-remote.jar` 启动后监听配置端口，日志输出正常
- Ctrl+C 触发 ShutdownHook，优雅关闭无异常

**预估工时**：2h  
**依赖**：Task-10, Task-11, Task-12

---

## Task-14：补充 SPI 注册文件

**目标**：确保所有新增的 SPI 实现在 META-INF/proxy/ 下正确注册。

**输入**：各模块新增的 SPI 实现类。

**输出文件**：
- `proxy-transport-netty/src/main/resources/META-INF/proxy/com.proxy.common.transport.Transporter`（已存在，确认无需修改——NettyTransporter 已注册）
- `proxy-exchange/src/main/resources/META-INF/proxy/com.proxy.common.exchange.Exchanger`（已存在，确认无需修改——HeaderExchanger 已注册）
- 服务端 Filter 注册文件（如果新增了 group="server" 的 Filter）

**具体要求**：
- 确认现有 SPI 文件已覆盖新增能力（bind 方法是在已有实现类上新增的，无需新注册）
- 如果现有 Filter（RateLimitFilter、MonitorFilter、AccessLogFilter）需要同时支持 server group，修改其 `@Activate` 注解为 `group = {"client", "server"}`
- 或者新建服务端专用 Filter（如 `ServerRateLimitFilter`），则需要新增 SPI 注册

**验收标准**：
- SPI 加载 `Transporter`、`Exchanger` 正常
- `ExtensionLoader.getLoader(Filter.class).getActivateExtensions("server")` 能返回预期的 Filter 列表

**预估工时**：1h  
**依赖**：Task-10, Task-13

---

## Task-15：集成测试 — 客户端到服务端全链路验证

**目标**：编写集成测试，验证客户端通过 ExchangeClient 发送请求到服务端，服务端 DispatchInvoker 处理后返回响应的完整链路。

**输入**：所有前置任务完成。

**输出文件**：`proxy-remote/src/test/java/com/proxy/remote/integration/ClientServerIntegrationTest.java`

**具体要求**：
- 测试用例 1：启动服务端 → 客户端 connect → 发送 CONNECT 消息 → 验证收到 OK 响应
- 测试用例 2：发送 DATA 消息（带 payload）→ 验证服务端回显正确数据
- 测试用例 3：发送 DISCONNECT 消息 → 验证收到 OK 响应
- 测试用例 4：加密验证 — 使用 AES-GCM 加密发送消息，服务端正确解密并响应
- 测试用例 5：心跳验证 — 客户端发送心跳，服务端正确响应
- 测试用例 6：并发验证 — 100 并发请求，全部正确返回
- 测试用例 7：异常验证 — 服务端线程池满时返回错误响应
- 每个测试方法独立（`@BeforeEach` 启动服务端，`@AfterEach` 关闭）
- 使用随机端口避免端口冲突

**验收标准**：
- 所有测试用例通过
- 无资源泄漏（测试结束后端口释放、线程池关闭）
- `mvn test` 全量通过

**预估工时**：3h  
**依赖**：Task-13, Task-14

---

## 执行顺序建议

**第一批（并行，无依赖）**：Task-1, Task-3, Task-6, Task-11, Task-12

**第二批（依赖第一批）**：Task-2（依赖 T1）, Task-4（依赖 T3）, Task-9（依赖 T6）

**第三批（依赖前两批）**：Task-5（依赖 T1,T2,T6）, Task-8（依赖 T3）

**第四批**：Task-7（依赖 T2,T5）

**第五批**：Task-10（依赖 T4,T7,T8,T9）

**第六批**：Task-13（依赖 T10,T11,T12）, Task-14（依赖 T10,T13）

**第七批**：Task-15（依赖全部）

---

## 总工时预估（Phase 1）

| 阶段 | 工时 |
|------|------|
| 接口定义（T1-T4） | 2h |
| 核心实现（T5-T10） | 11h |
| 业务层（T11-T13） | 5h |
| 收尾（T14-T15） | 4h |
| **合计** | **~22h** |

---
---

# Phase 2：Outbound 出站连接 — 子任务拆分

> 基于 `proxy-remote-server-design.md` §8.1 设计，Phase 1 完成后顺序执行。
> 所有新增/修改文件均在 `proxy-remote` 模块内，不改动其他模块。

---

## 任务总览（依赖关系）

```
Task-16 ─┐
Task-17 ─┼─→ Task-19 ─→ Task-20 ─→ Task-21 ─→ Task-22 ─→ Task-23
Task-18 ─┘
```

---

## Task-16：实现 `OutboundSession`

**目标**：实现出站会话类，绑定一个客户端 Stream 到一条目标 TCP 连接，管理会话状态和双向数据转发。

**输入**：设计文档 §8.1.3 `OutboundSession` 设计。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/outbound/OutboundSession.java`

**具体要求**：
- 持有 `ChannelHandlerContext inboundCtx`（回写客户端用）和 `Channel outboundChannel`（到目标的连接）
- 持有 `targetHost`、`targetPort` 信息
- 状态枚举 `SessionState`：`CONNECTING` → `ACTIVE` → `CLOSED`
- `setOutboundChannel(Channel)` 方法：设置目标连接、状态改为 ACTIVE
- `forward(byte[] data)` 方法：将数据写入 `outboundChannel`（状态为 ACTIVE 时）
- `writeBack(byte[] data)` 方法：将目标返回的数据封装为 `ProxyMessage`（type=DATA），通过 `inboundCtx.writeAndFlush()` 推回客户端
- `awaitActive(long timeoutMs)` 方法：等待状态变为 ACTIVE（使用 CountDownLatch 或 CompletableFuture），供 `handleData` 在 CONNECTING 状态时调用
- `close()` 方法：状态设为 CLOSED，关闭 outboundChannel

**验收标准**：
- 编译通过
- 单元测试：mock inboundCtx 和 outboundChannel，验证 forward/writeBack/close 行为正确
- 单元测试：`awaitActive` 在超时前设置 channel 能返回 true，超时后返回 false

**预估工时**：2h  
**依赖**：无（仅依赖 proxy-common 中的 `ProxyMessage`）

---

## Task-17：实现 `OutboundConnector`

**目标**：实现异步出站连接器，根据目标地址使用 Netty Bootstrap 建立裸 TCP 连接。

**输入**：设计文档 §8.1.3 `OutboundConnector` 设计。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/outbound/OutboundConnector.java`

**具体要求**：
- 构造函数接收 `EventLoopGroup workerGroup` 和 `int connectTimeoutMs`
- 内部创建 `Bootstrap`，配置 `NioSocketChannel`、`TCP_NODELAY`、`SO_KEEPALIVE`、`CONNECT_TIMEOUT_MILLIS`
- `connect(String host, int port, OutboundSession session)` 方法：
  - 使用 `bootstrap.clone()` 创建副本（避免 handler 共享问题）
  - 配置 `ChannelInitializer`，Pipeline 仅挂载一个 `OutboundHandler(session)`
  - 返回 `CompletableFuture<Channel>`
  - 连接成功时 `future.complete(channel)`，失败时 `future.completeExceptionally(cause)`
- **不做连接池**：每次调用都是全新连接
- **复用 Worker EventLoopGroup**：不创建新线程池

**验收标准**：
- 编译通过
- 集成测试：启动一个本地 TCP Server，验证 `connect()` 返回的 Future 能成功拿到 Channel
- 集成测试：连接一个不存在的端口，验证 Future 异常完成

**预估工时**：1.5h  
**依赖**：Task-16（`OutboundSession` 作为参数传入），Task-18（`OutboundHandler` 挂在 Pipeline 上）

---

## Task-18：实现 `OutboundHandler`

**目标**：实现目标网站响应中继器，挂在 Outbound Channel Pipeline 上，收到目标返回的字节后推回客户端。

**输入**：设计文档 §8.1.3 `OutboundHandler` 设计。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/outbound/OutboundHandler.java`

**具体要求**：
- 继承 `SimpleChannelInboundHandler<ByteBuf>`
- 构造函数接收 `OutboundSession session`
- `channelRead0(ctx, msg)` 方法：读取 ByteBuf 字节 → 调用 `session.writeBack(data)`
- `channelInactive(ctx)` 方法：目标网站断开 → 调用 `session.close()`
- `exceptionCaught(ctx, cause)` 方法：异常 → 调用 `session.close()` + `ctx.close()`
- **极简 Pipeline**：这是 Outbound Channel 上唯一的 Handler，不需要编解码器

**验收标准**：
- 编译通过
- 单元测试：使用 Netty EmbeddedChannel，写入 ByteBuf，验证 `session.writeBack()` 被正确调用
- 单元测试：模拟 channelInactive，验证 session.close() 被调用

**预估工时**：1h  
**依赖**：Task-16（依赖 `OutboundSession`）

---

## Task-19：实现 `SessionManager`

**目标**：实现 streamId → OutboundSession 的映射管理器，提供会话的注册、查询、移除和批量清理能力。

**输入**：设计文档 §8.1.3 `SessionManager` 设计。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/outbound/SessionManager.java`

**具体要求**：
- 内部使用 `ConcurrentHashMap<String, OutboundSession>`
- `register(String streamId, OutboundSession session)` 方法：注册映射
- `get(String streamId)` 方法：查询 session
- `remove(String streamId)` 方法：移除映射并调用 `session.close()`，返回被移除的 session
- `activeCount()` 方法：返回当前活跃会话数（监控用）
- `closeAll()` 方法：遍历所有 session 调用 close，清空 map（服务关闭时调用）

**验收标准**：
- 编译通过
- 单元测试：register → get 返回正确 session
- 单元测试：remove 后 get 返回 null，且 session.close() 被调用
- 单元测试：closeAll 后 activeCount 为 0

**预估工时**：1h  
**依赖**：Task-16（`OutboundSession` 类型）

---

## Task-20：升级 `DispatchInvoker`（桩实现 → 完整 Outbound 逻辑）

**目标**：将 Phase 1 的桩实现升级为完整的出站处理逻辑，接入 SessionManager 和 OutboundConnector。

**输入**：设计文档 §8.1.4 完整 DispatchInvoker 实现；Phase 1 的 Task-11 桩代码。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/dispatch/DispatchInvoker.java`（修改）

**具体要求**：
- 构造函数新增参数：`OutboundConnector connector`（在原有 `ExecutorService bizExecutor` 基础上）
- 内部创建 `SessionManager` 实例
- `handleConnect(invocation)` 完整实现：
  - 从 `invocation.getTargetHost()` / `getTargetPort()` 获取目标地址
  - 从 `invocation.getAttachment("streamId")` 获取 streamId
  - 从 `invocation.getAttachment("inboundCtx")` 获取 ChannelHandlerContext
  - 创建 `OutboundSession(inboundCtx, targetHost, targetPort)`
  - 调用 `sessionManager.register(streamId, session)`
  - 调用 `connector.connect(host, port, session).whenComplete(...)` 异步建连
  - 建连成功：`session.setOutboundChannel(channel)`
  - 建连失败：`sessionManager.remove(streamId)`
  - 立即返回 `Response.ok()`（不等建连完成）
- `handleData(invocation)` 完整实现：
  - 从 sessionManager 获取 session
  - session 不存在：返回错误 Response
  - session 处于 CONNECTING 状态：`session.awaitActive(activeWaitTimeoutMs)`
  - 超时仍未就绪：返回错误 Response
  - session ACTIVE：`session.forward(invocation.getData())` → 返回 `Response.ok()`
- `handleDisconnect(invocation)` 完整实现：
  - `sessionManager.remove(streamId)`（内部关闭 outbound 连接）
  - 返回 `Response.ok()`
- 新增 `shutdown()` 方法：`sessionManager.closeAll()`
- 提供 `getSessionManager()` 方法供 ProxyRemoteServer 关闭时使用

**验收标准**：
- 编译通过
- 单元测试：CONNECT 后 sessionManager 中存在该 streamId 的 session
- 单元测试：DATA 发送后 session.forward() 被调用
- 单元测试：DISCONNECT 后 session 被移除且 close 被调用
- 单元测试：session 不存在时 handleData 返回错误 Response

**预估工时**：3h  
**依赖**：Task-16, Task-17, Task-18, Task-19（Phase 2 前置任务）+ Phase 1 Task-11（桩代码存在）

---

## Task-21：修改 `ProxyRemoteServer`（初始化 OutboundConnector 并注入）

**目标**：修改服务端启动入口，在启动流程中创建 OutboundConnector 并注入到 DispatchInvoker。

**输入**：设计文档 §8.1.8 模块结构。

**输出文件**：`proxy-remote/src/main/java/com/proxy/remote/ProxyRemoteServer.java`（修改）

**具体要求**：
- 启动流程调整（在 Phase 1 基础上）：
  1. 配置加载（不变）
  2. 创建 bizExecutor（不变）
  3. **新增**：通过 SPI 加载 Transporter → bind → 拿到 NettyServer → 获取其 `workerGroup`
  4. **新增**：创建 `OutboundConnector(workerGroup, connectTimeoutMs)`
  5. 创建 `DispatchInvoker(bizExecutor, connector)`（新增 connector 参数）
  6. 构建 Filter 链 → bind（不变）
- `shutdown()` 方法调整：
  - 新增 `dispatchInvoker.shutdown()`（关闭所有 OutboundSession）
  - 原有的关闭 ExchangeServer + 线程池逻辑不变
- **注意**：需要调整启动顺序——先 bind 拿到 Server 获取 workerGroup，再创建 Connector。或者 NettyServer 暴露 `getWorkerGroup()` 方法供外部获取。

**验收标准**：
- 编译通过
- 启动后 OutboundConnector 正常初始化（日志可见）
- shutdown 时所有 Outbound 会话被清理

**预估工时**：1.5h  
**依赖**：Task-20（升级后的 DispatchInvoker）

---

## Task-22：修改 `RemoteConfig`（新增 Outbound 配置项）

**目标**：在服务端配置中加入 Outbound 相关的配置项。

**输入**：设计文档 §8.1.9 配置项。

**输出文件**：
- `proxy-remote/src/main/java/com/proxy/remote/config/RemoteConfig.java`（修改）
- `proxy-remote/src/main/resources/remote.yml`（修改）

**具体要求**：
- 新增配置项解析：
  - `outbound.connectTimeoutMs`（默认 5000）
  - `outbound.activeWaitTimeoutMs`（默认 5000）
  - `outbound.keepAlive`（默认 true）
  - `outbound.tcpNoDelay`（默认 true）
- 配置项写入 URL 对象的 parameters 中（key 为 `outbound.xxx`）
- `remote.yml` 模板中增加 outbound 配置段及注释

**验收标准**：
- 编译通过
- 单元测试：加载含 outbound 配置的 yml，验证 URL 参数正确
- 单元测试：缺失 outbound 配置时使用默认值

**预估工时**：1h  
**依赖**：Phase 1 Task-12（RemoteConfig 已存在）

---

## Task-23：集成测试 — Outbound 全链路验证

**目标**：编写集成测试，验证完整的 CONNECT → DATA 透传 → DISCONNECT 链路，确保客户端流量能通过 proxy-remote 正确到达目标并返回响应。

**输入**：所有 Phase 2 前置任务完成。

**输出文件**：`proxy-remote/src/test/java/com/proxy/remote/integration/OutboundIntegrationTest.java`

**具体要求**：
- 测试用例 1：启动本地 TCP EchoServer + proxy-remote → 客户端发送 CONNECT → 发送 DATA → 验证收到 echo 回包
- 测试用例 2：CONNECT 到不存在的地址 → 验证客户端收到错误响应
- 测试用例 3：正常连接后发送 DISCONNECT → 验证 Outbound 连接关闭 + session 清理
- 测试用例 4：目标 EchoServer 主动关闭连接 → 验证客户端收到 DISCONNECT 通知
- 测试用例 5：并发 50 个 Stream 同时 CONNECT 不同目标 → 全部正常透传
- 测试用例 6：背压验证 — 目标返回大量数据，客户端慢消费 → 验证不 OOM
- 测试用例 7：服务端 shutdown 时所有 session 正确清理

**验收标准**：
- 所有测试用例通过
- 无资源泄漏（连接、线程池、ByteBuf）
- `mvn test` 全量通过

**预估工时**：3h  
**依赖**：Task-20, Task-21, Task-22

---

## Phase 2 执行顺序建议

**第一批（并行，无 Phase 2 内部依赖）**：Task-16, Task-18, Task-22

**第二批（依赖第一批）**：Task-17（依赖 T16, T18）, Task-19（依赖 T16）

**第三批**：Task-20（依赖 T16, T17, T18, T19）

**第四批**：Task-21（依赖 T20）

**第五批**：Task-23（依赖全部）

---

## 总工时预估（Phase 2）

| 阶段 | 工时 |
|------|------|
| 核心组件（T16-T19） | 5.5h |
| 集成升级（T20-T22） | 5.5h |
| 集成测试（T23） | 3h |
| **合计** | **~14h** |

---

## 总工时预估（全量）

| 阶段 | 工时 |
|------|------|
| Phase 1 接口定义（T1-T4） | 2h |
| Phase 1 核心实现（T5-T10） | 11h |
| Phase 1 业务层（T11-T13） | 5h |
| Phase 1 收尾（T14-T15） | 4h |
| Phase 2 Outbound（T16-T23） | 14h |
| **合计** | **~36h** |
