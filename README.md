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

## 新手使用指南

### 环境要求

| 依赖 | 最低版本 | 说明 |
|------|----------|------|
| JDK | 1.8+ | 推荐 JDK 8 或 JDK 11 |
| Maven | 3.6+ | 用于编译打包 |
| 云服务器 | 任意 | 远程代理运行的服务器（需要有公网 IP，能够访问目标网站） |

### 第一步：克隆项目

```bash
git clone https://github.com/zhh293/SimplePlanePlatform.git
cd SimplePlanePlatform
```

### 第二步：编译打包

```bash
mvn clean package -DskipTests
```

编译完成后会在各模块的 `target/` 目录下生成 jar 文件：

- `proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar` — 本地代理客户端
- `proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar` — 远程代理服务端

### 第三步：部署远程服务端

将远程 jar 上传到你的云服务器：

```bash
scp -i your-key.pem proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar ubuntu@YOUR_SERVER_IP:/home/ubuntu/proxy-remote.jar
```

SSH 登录到服务器，编辑配置（可选，默认配置即可使用）：

```bash
ssh -i your-key.pem ubuntu@YOUR_SERVER_IP
```

配置文件在 jar 包内的 `remote.yml`，默认参数如下：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| host | 0.0.0.0 | 监听地址 |
| port | 9090 | 监听端口 |
| cipher | none | 加密算法（none/aes-gcm/chacha20/aes-ctr-hmac） |
| cipherKey | my-secret-key-123 | 加密密钥，客户端服务端必须一致 |
| maxStreams | 1000 | 单连接最大并发 Stream 数 |
| bizThreads | 200 | 业务线程池大小 |

如果需要修改配置，在 jar 同目录下创建 `remote.yml` 文件覆盖即可。

启动远程服务端：

```bash
nohup java -jar /home/ubuntu/proxy-remote.jar > proxy-remote.log 2>&1 &
```

验证是否启动成功：

```bash
tail -f proxy-remote.log
# 看到 "ProxyRemoteServer started successfully" 和 "Listening on 0.0.0.0:9090" 即表示成功
```

**注意：** 确保服务器的安全组/防火墙已开放 9090 端口（TCP 入站）。

### 第四步：配置本地客户端

编辑 `proxy-local/src/main/resources/proxy.yml`：

```yaml
# 本地监听端口
localPort: 1080

# 远程服务器地址（改成你自己服务器的 IP）
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    ssl: false
    cipher: "none"              # 需和服务端一致
    cipherKey: "my-secret-key-123"  # 需和服务端一致

# 每个节点的 HTTP/2 连接数（保持 1 即可）
connectionsPerNode: 1

# 是否支持 HTTP CONNECT 代理
httpProxyEnabled: true
```

修改配置后需要重新编译：

```bash
mvn clean package -DskipTests -pl proxy-local -am
```

### 第五步：配置路由规则

在 `proxy.yml` 的 `route` 部分配置哪些域名走代理、哪些直连：

```yaml
route:
  # 默认路由：proxy（全部走代理）或 direct（全部直连）
  defaultRoute: direct

  # 走代理的域名（支持通配符 *）
  proxyList:
    - "*.google.com"
    - "*.youtube.com"
    - "*.github.com"
    - "github.com"
    # ... 添加你需要代理的域名

  # 强制直连的域名（优先级高于 proxyList）
  directList:
    - "*.baidu.com"
    - "127.0.0.1"
    - "localhost"
    # ... 添加不需要代理的域名
```

路由优先级：`directList` > `proxyList` > `defaultRoute`

### 第六步：启动本地代理

```bash
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar
```

看到以下输出即表示启动成功：

```
=== Proxy Local Server started on port 1080 ===
  SOCKS5 proxy: localhost:1080
  HTTP  proxy:  localhost:1080
```

### 第七步：配置浏览器/系统代理

#### 方式一：系统全局代理（macOS）

系统偏好设置 → 网络 → 高级 → 代理：

- SOCKS 代理：`127.0.0.1`，端口 `1080`
- 或 HTTP/HTTPS 代理：`127.0.0.1`，端口 `1080`

#### 方式二：浏览器插件（推荐）

使用 SwitchyOmega（Chrome/Edge）或 FoxyProxy（Firefox）：

1. 安装插件
2. 新建代理情景模式
3. 协议选 HTTP 或 SOCKS5，服务器 `127.0.0.1`，端口 `1080`
4. 切换到该情景模式即可

#### 方式三：命令行临时使用

```bash
# HTTP 代理
export http_proxy=http://127.0.0.1:1080
export https_proxy=http://127.0.0.1:1080

# 或 SOCKS5 代理
export all_proxy=socks5://127.0.0.1:1080

# 测试
curl -I https://www.google.com
```

### 第八步：验证是否生效

```bash
# 通过代理访问 Google（应返回 200）
curl -x http://127.0.0.1:1080 -I https://www.google.com

# 通过 SOCKS5 代理访问
curl -x socks5://127.0.0.1:1080 -I https://www.youtube.com
```

本地终端的日志中会输出类似：

```
INFO  ACCESS_LOG - [OK] www.google.com:443 elapsed=0ms status=200
```

### 常用运维命令

```bash
# 查看本地代理是否在运行
lsof -i :1080

# 停止本地代理
kill $(lsof -ti :1080)

# 查看远程服务端进程
ssh -i your-key.pem ubuntu@YOUR_SERVER_IP "ps aux | grep proxy-remote | grep -v grep"

# 重启远程服务端
ssh -i your-key.pem ubuntu@YOUR_SERVER_IP "pkill -f proxy-remote.jar; sleep 1; nohup java -jar /home/ubuntu/proxy-remote.jar > proxy-remote.log 2>&1 &"

# 查看远程服务端日志
ssh -i your-key.pem ubuntu@YOUR_SERVER_IP "tail -50 proxy-remote.log"
```

### 开启加密（可选）

如果需要对传输链路加密，客户端和服务端设置相同的 cipher 和 cipherKey：

```yaml
# proxy.yml（本地）
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    cipher: "aes-gcm"           # 可选：aes-gcm / chacha20 / aes-ctr-hmac
    cipherKey: "your-secret-key"

# remote.yml（服务端）
cipher: aes-gcm
cipherKey: your-secret-key
```

支持的加密算法：

| 算法 | 特点 |
|------|------|
| `none` | 无加密，调试用，性能最高 |
| `aes-gcm` | AES-256-GCM，利用 AES-NI 硬件加速，推荐 x86 平台 |
| `chacha20` | ChaCha20-Poly1305，ARM 平台友好，推荐移动设备 |
| `aes-ctr-hmac` | AES-CTR + HMAC-SHA256，经典组合 |

### 常见问题

**Q: 启动报 `Address already in use`**

端口 1080 被占用，杀掉占用进程：`kill $(lsof -ti :1080)`，然后重新启动。

**Q: 连接远端失败 `Connection refused`**

1. 确认远程服务端已启动并监听 9090 端口
2. 确认服务器安全组/防火墙已放行 9090 端口
3. 确认 `proxy.yml` 中的 IP 地址正确

**Q: 访问网站超时但没有报错**

1. 检查该域名是否在 `proxyList` 中（或 `defaultRoute` 是否为 `proxy`）
2. 确认远端服务器能正常访问目标网站：`ssh 到服务器后 curl https://www.google.com`

**Q: 浏览器报证书错误**

这是正常的，本代理使用 CONNECT 隧道，不做中间人解密，不会出现证书问题。如果遇到，可能是代理未正确配置，流量没有走隧道。

**Q: 如何添加多个远程节点做负载均衡？**

```yaml
remoteServers:
  - host: "server1-ip"
    port: 9090
    cipher: "none"
    cipherKey: "key1"
  - host: "server2-ip"
    port: 9090
    cipher: "none"
    cipherKey: "key2"

loadBalance: "roundrobin"   # 或 random / leastactive / consistenthash
cluster: "failover"         # 失败自动切换到下一个节点
```

---

## Web 管理面板

项目提供了一个基于 Node.js 的 Web 管理面板，支持通过浏览器可视化编辑所有配置参数，无需手动修改 YAML 文件。

### 启动面板

```bash
cd dashboard
node server.js
```

启动后访问 `http://localhost:3000`。

### 功能概览

| 功能 | 说明 |
|------|------|
| Local Server 配置 | 编辑 localPort、cluster、loadBalance、timeoutMs、connectionsPerNode 等全部 proxy.yml 参数 |
| Remote Servers 管理 | 可视化增删改多节点，支持批量导入 YAML |
| Route Rules 编辑 | 双栏编辑 proxyList / directList，实时显示规则数量 |
| Remote Server 配置 | 编辑 remote.yml 全部参数（含 outbound 出站配置） |
| 配置预设管理 | 保存/加载/删除 remote server 配置预设，便于多环境切换 |
| YAML 导入 | 支持拖拽 .yml 文件或粘贴 YAML 文本导入配置 |
| 实时变更检测 | 外部修改 YAML 文件后自动推送更新（SSE） |
| 表单验证 | 保存前自动校验端口范围、必填项等 |

### 目录结构

```
dashboard/
├── server.js           # Node.js 服务端（REST API + 静态文件 + SSE）
├── yaml.js             # 零依赖 YAML 解析/序列化
├── package.json
├── presets/            # 配置预设存储目录
└── public/
    ├── index.html      # 单页应用
    ├── style.css       # OKLCH 暗色主题
    └── app.js          # 前端逻辑
```

> 面板零外部依赖，直接读写 `proxy-local/src/main/resources/proxy.yml` 和 `proxy-remote/src/main/resources/remote.yml`。

---

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
| `proxy-remote` | 远程服务端，接收加密请求并代为访问目标站点 |

## 核心特性

### 全链路 SPI 可插拔

借鉴 Dubbo 的 ExtensionLoader 设计，自研了完整的 SPI 体系。所有核心组件——Transporter、Exchanger、Cipher、ClusterInvoker、LoadBalance、Filter、FilterChainBuilder——共 7 个扩展点，均通过 `@SPI` 注解标记并支持按名加载、默认实现、条件激活三种获取方式。扩展一个新实现只需要两步：编写实现类，在 `META-INF/proxy/` 下注册映射。

### HTTP/2 多路复用传输

摒弃传统 TCP 连接池的一连接一请求模型，直接采用 HTTP/2 Stream 多路复用。单条 TCP 连接可承载 1000+ 并发流，极大减少连接建立开销和端口占用。连接池内部通过 `ReentrantLock + Condition` 实现精准的 Stream 等待唤醒机制，当所有 Stream 耗尽时线程排队等待而非直接拒绝。

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

### 智能路由分流

支持基于域名的路由规则，可配置 `proxyList`（走代理）和 `directList`（直连），支持通配符匹配。国内网站直连不绕路，国外网站走代理，兼顾速度和可达性。

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
| SLF4J + Logback | 1.7.36 / 1.2.11 | 日志框架 |
| JUnit 5 | 5.10.2 | 单元测试 |
| Maven | 3.6+ | 多模块构建管理 |

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
