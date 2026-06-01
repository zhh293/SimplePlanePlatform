# Netty-Proxy

一个基于 Dubbo 微内核思想设计的高性能加密隧道代理框架。全链路 SPI 可插拔，集群容错、负载均衡、加密算法、传输层实现均可独立扩展替换，用约 3000 行核心代码实现了完整的企业级架构。

## 项目定位

本项目将 Dubbo 的经典架构哲学——微内核 + 插件化——创造性地应用到网络代理场景。在本地启动代理服务（支持 SOCKS5 和 HTTP CONNECT 协议），通过加密隧道将流量转发至远程服务器，由远程服务器代为访问目标站点。

核心数据流如下：

```
浏览器/系统 → SOCKS5 / HTTP CONNECT → 本地代理
→ Filter 责任链 → ClusterInvoker (容错 + 负载均衡)
→ ExchangeClient (请求-响应映射) → Netty HTTP/2 加密传输
→ 远程代理服务器 → 目标网站
```

## 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                      Local Server Layer                           │
│    ProxyLocalServer / ProtocolDetector / SOCKS5 / HTTP CONNECT   │
├──────────────────────────────────────────────────────────────────┤
│                      Cluster Layer                                │
│    ClusterInvoker: Failover / Failfast / Forking / Failback      │
│    LoadBalance: RoundRobin / Random / LeastActive / ConsistentHash│
├──────────────────────────────────────────────────────────────────┤
│                      Filter Chain Layer                           │
│    Router → RateLimit → Monitor → AccessLog → Traffic            │
├──────────────────────────────────────────────────────────────────┤
│                      Exchange Layer                               │
│    HeaderExchangeClient / DefaultFuture / RequestId-Future 映射  │
├──────────────────────────────────────────────────────────────────┤
│                      Transport Layer                              │
│    NettyClient / HTTP/2 ConnectionPool / Stream 多路复用          │
├──────────────────────────────────────────────────────────────────┤
│                      Codec & Crypto Layer                         │
│    ProxyMessage 编解码 / AES-GCM / ChaCha20 / AES-CTR-HMAC      │
├──────────────────────────────────────────────────────────────────┤
│                      SPI Core (proxy-common)                      │
│    ExtensionLoader / @SPI / @Activate / 全部接口定义              │
└──────────────────────────────────────────────────────────────────┘
```

## 模块说明

| 模块 | 职责 |
|------|------|
| `proxy-common` | 内核抽象层，定义所有 SPI 接口、核心数据模型、ExtensionLoader 和注解体系，不含任何具体实现 |
| `proxy-exchange` | 交换层，在底层 Transport 之上构建请求-响应语义（RequestId + Future 映射） |
| `proxy-transport-netty` | 传输层实现，基于 Netty HTTP/2 多路复用构建高性能连接池 |
| `proxy-crypto` | 加密模块，提供 4 种可插拔的 AEAD 加密算法实现 |
| `proxy-cluster` | 集群层，包含 4 种容错策略、4 种负载均衡算法和 5 个 Filter 实现 |
| `proxy-local` | 本地客户端入口，加载配置、组装调用链、启动代理监听 |
| `proxy-remote` | 远程服务端，接收加密请求并代为访问目标站点（开发中） |

## 核心特性

### 全链路 SPI 可插拔

借鉴 Dubbo 的 ExtensionLoader 设计，自研了完整的 SPI 体系。所有核心组件——Transporter、Exchanger、Cipher、ClusterInvoker、LoadBalance、Filter、FilterChainBuilder——共 7 个扩展点，均通过 `@SPI` 注解标记并支持按名加载、默认实现、条件激活三种获取方式。扩展一个新实现只需要两步：编写实现类，在 `META-INF/proxy/` 下注册映射。

### HTTP/2 多路复用传输

摒弃传统 TCP 连接池的一连接一请求模型，直接采用 HTTP/2 Stream 多路复用。单条 TCP 连接可承载 100+ 并发流，极大减少连接建立开销和端口占用。连接池内部通过 `ReentrantLock + Condition` 实现精准的 Stream 等待唤醒机制，当所有 Stream 耗尽时线程排队等待而非直接拒绝。

### 四种集群容错策略

与 Dubbo 完全对标的经典容错模型：Failover（失败自动切换，适合幂等读操作）、Failfast（快速失败，适合非幂等写操作）、Forking（并行调用取最快结果，适合实时性要求高的场景）、Failback（失败后台重试，适合可最终一致的消息通知类操作）。

### 四种负载均衡算法

RoundRobin（加权轮询）、Random（加权随机）、LeastActive（最少活跃调用数，自动感知服务端处理能力）、ConsistentHash（一致性哈希，相同请求路由到相同节点）。

### 多层加密体系

独立的 Cipher SPI 扩展点，支持 4 种加密方案：AES-256-GCM（默认，利用 Intel AES-NI 硬件加速的 AEAD 加密）、ChaCha20-Poly1305（ARM 平台友好的 AEAD 方案，基于 BouncyCastle）、AES-CTR-HMAC（经典流式加密 + 消息认证码）、None（无加密，用于调试和本地测试）。每种方案都是 AEAD 级别的认证加密，防篡改防重放。

### Filter 责任链

通过 `@Activate` 注解自动发现和排序，Filter 链以 Invoker 包装方式构建，每个 Filter 是独立的横切关注点。已内置 5 个 Filter：路由过滤、滑动窗口令牌桶限流、RT/成功率/吞吐量监控统计、访问日志记录、流量控制。新增 Filter 只需实现接口并注册 SPI，无需修改任何已有代码。

### 双协议共端口

通过 `ProtocolDetector` 首字节嗅探技术（peek 0x05 为 SOCKS5，ASCII 字母为 HTTP），在同一端口上同时支持 SOCKS5 和 HTTP CONNECT 两种代理协议。识别后动态修改 Netty Pipeline，零拷贝切换协议处理器。

### 全异步 CompletableFuture

从交换层到集群层全链路基于 `CompletableFuture<Response>` 异步编程，配合 Netty 的 HashedWheelTimer（50ms tick，512 slots）做超时检测，兼顾低延迟和高吞吐。

## 设计模式

本项目综合运用了多种经典设计模式：

- **SPI + 策略模式**：所有核心组件通过 SPI 加载，运行时按配置切换策略实现
- **责任链模式**：Filter 链式处理，关注点分离，开闭原则
- **Future 异步模式**：DefaultFuture 管理 RequestId → CompletableFuture 的全局映射
- **适配器模式**：ClientInvoker 将 ExchangeClient 适配为统一 Invoker 接口
- **Builder 模式**：ProxyMessage.Builder 流式构建消息对象
- **观察者模式**：连接池 Stream 释放时通过 Condition 通知等待线程

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 1.8+ | 基础语言 |
| Netty | 4.1.108 | NIO 网络框架、HTTP/2 实现 |
| BouncyCastle | 1.70 | ChaCha20-Poly1305 加密 |
| SnakeYAML | 2.2 | YAML 配置解析 |
| SLF4J + Logback | 2.0.12 / 1.5.3 | 日志框架 |
| JUnit 5 | 5.10.2 | 单元测试 |
| Maven | - | 多模块构建管理 |

## 快速开始

### 构建

```bash
mvn clean package -DskipTests
```

### 配置

编辑 `proxy-local/src/main/resources/proxy.yml`，配置远程服务器地址、加密方式、连接池大小等参数。

### 启动本地代理

```bash
java -jar proxy-local/target/proxy-local.jar
```

启动后本地代理将监听配置的端口，支持 SOCKS5 和 HTTP CONNECT 协议接入。

## 与 Dubbo 的对比

本项目深度借鉴了 Dubbo 的架构设计，但将其应用于完全不同的领域：

| 维度 | Dubbo | Netty-Proxy |
|------|-------|-------------|
| 应用场景 | 微服务 RPC 调用 | 加密隧道代理 |
| 传输协议 | Dubbo 协议 / Triple | HTTP/2 多路复用 |
| 连接模型 | 单连接 / 连接池 | HTTP/2 Stream 复用 |
| 加密层 | TLS（可选） | SPI 可插拔 AEAD 加密 |
| 代码体量 | 数十万行 | ~3000 行核心代码 |
| 设计目标 | 通用 RPC 框架 | 轻量高性能代理工具 |

## 扩展指南

得益于 SPI 架构，扩展任何层的实现都非常简单：

1. 实现对应的 SPI 接口（如 `Cipher`、`LoadBalance`、`Filter` 等）
2. 在 `META-INF/proxy/{接口全限定名}` 文件中注册 `name=实现类全限定名`
3. 通过配置或代码指定使用新的实现名称

无需修改框架任何已有代码，完全符合开闭原则。

## License

MIT
