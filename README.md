# Netty-Proxy

基于 Dubbo 微内核思想设计的高性能加密隧道代理框架。全链路 SPI 可插拔，集群容错、负载均衡、加密算法、传输层实现均可独立扩展替换。

## 项目定位

将 Dubbo 经典的微内核 + 插件化架构应用到网络代理场景。本地启动代理服务（SOCKS5 / HTTP CONNECT 双协议共端口），通过 HTTP/2 加密隧道将流量转发至远程服务器，由远程端代为访问目标站点。

```
浏览器/系统 → SOCKS5 / HTTP CONNECT → proxy-local(1080)
→ Filter 责任链 → ClusterInvoker(容错 + 负载均衡)
→ ExchangeClient(请求-响应映射) → Netty HTTP/2 加密传输
→ nginx(9090) → proxy-remote(19090) → 目标网站
```

## 快速开始

### 环境要求

JDK 1.8+、Maven 3.6+、一台有公网 IP 的云服务器。

### 编译打包

```bash
git clone https://github.com/zhh293/SimplePlanePlatform.git
cd SimplePlanePlatform
mvn clean package -DskipTests
```

产物在各模块的 `target/` 目录：`proxy-local-1.0.0-SNAPSHOT.jar`（本地端）、`proxy-remote-1.0.0-SNAPSHOT.jar`（远程端）。

### 部署远程服务端

将 jar 上传到服务器：

```bash
scp -i <your-key.pem> proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar <user>@<server-ip>:~/
```

#### 方式一：Nginx 反向代理部署（推荐）

生产环境推荐通过 nginx 做 TCP 四层代理，Netty 只绑定 `127.0.0.1` 不直接暴露：

```bash
# 本地一键执行
ssh -i <your-key.pem> <user>@<server-ip> 'bash -s' < proxy-remote/deploy-nginx.sh
```

脚本自动完成：安装 nginx stream 模块 → 写入配置 → 启动 Netty(127.0.0.1:19090) → nginx 对外监听 9090 转发。

最终架构：

```
客户端 → nginx(0.0.0.0:9090) → Netty(127.0.0.1:19090) → 目标网站
```

nginx 配置参见 `proxy-remote/nginx-stream.conf`。

#### 方式二：直接部署

如不需要 nginx 层，可直接启动（需将 `remote.yml` 中 host 改为 `0.0.0.0`，port 改为 `9090`）：

```bash
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
```

确保安全组/防火墙已开放对应端口（TCP 入站）。

### 配置本地客户端

编辑 `proxy-local/src/main/resources/proxy.yml`：

```yaml
localPort: 1080
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    ssl: false
    cipher: "none"
    cipherKey: "your-cipher-key"
cluster: failover
loadBalance: roundrobin
timeoutMs: 30000
connectionsPerNode: 1
httpProxyEnabled: true
```

重新编译后启动：

```bash
mvn package -pl proxy-local -am -DskipTests
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar
```

看到 `Proxy Local Server started on port 1080` 即表示成功。

### 配置路由规则

在 `proxy.yml` 的 `route` 部分设置分流策略：

```yaml
route:
  defaultRoute: direct          # 默认直连
  proxyList:                    # 走代理的域名
    - "*.google.com"
    - "*.github.com"
    - github.com
    - "*.youtube.com"
    - "*.openai.com"
  directList:                   # 强制直连
    - "*.baidu.com"
    - 127.0.0.1
    - localhost
```

优先级：`directList` > `proxyList` > `defaultRoute`。

### 配置系统代理

```yaml
systemProxy:
  enabled: false    # macOS/Windows/Linux 自动设置系统代理（需管理员权限）
  host: 127.0.0.1
```

或手动设置：系统代理指向 `127.0.0.1:1080`（HTTP/SOCKS5 均可），也可使用 SwitchyOmega 等浏览器插件。

### 验证

```bash
curl -x http://127.0.0.1:1080 --max-time 10 -I https://www.google.com
curl -x socks5://127.0.0.1:1080 --max-time 10 -I https://github.com
```

### Docker 部署

项目提供 docker-compose 一键编排，两端在同一 compose 网络内通信：

```bash
docker compose up -d --build    # 构建并启动
docker compose logs -f          # 查看日志
docker compose down             # 停止
```

容器模式下 proxy-local 的 1080 端口映射到宿主机，通过服务名 `proxy-remote` 连接服务端。加密配置见 `docker/proxy.yml` 和 `docker/remote.yml`。

## 模块结构

```
netty-proxy/
├── proxy-common            SPI 内核：接口定义、ExtensionLoader、注解体系、数据模型
├── proxy-exchange          交换层：请求-响应语义（RequestId + Future 映射）
├── proxy-transport-netty   传输层：Netty HTTP/2 多路复用、编解码、心跳
├── proxy-crypto            加密层：4 种可插拔 AEAD 加密实现
├── proxy-cluster           集群层：4 种容错策略 + 4 种负载均衡 + 6 个 Filter
├── proxy-local             本地客户端：SOCKS5/HTTP CONNECT 双协议、路由分流、系统代理
├── proxy-remote            远程服务端：请求分发、出站连接管理、nginx 部署脚本
└── dashboard               Web 管理面板：可视化编辑所有配置参数
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
│    NettyClient / HTTP/2 Stream 多路复用 / 连接管理                │
├──────────────────────────────────────────────────────────────────┤
│                      Codec & Crypto Layer                         │
│    ProxyMessage 编解码 / AES-GCM / ChaCha20 / AES-CTR-HMAC      │
├──────────────────────────────────────────────────────────────────┤
│                      SPI Core (proxy-common)                      │
│    ExtensionLoader / @SPI / @Activate / @Order                   │
└──────────────────────────────────────────────────────────────────┘
```

## 核心特性

**全链路 SPI 可插拔** — 借鉴 Dubbo ExtensionLoader，7 个扩展点（Transporter、Exchanger、Cipher、ClusterInvoker、LoadBalance、Filter、FilterChainBuilder）均通过 `@SPI` 注解标记，支持按名加载、默认实现、条件激活。扩展只需实现接口 + 在 `META-INF/proxy/` 注册。

**HTTP/2 多路复用传输** — 单条 TCP 连接承载 1000+ 并发 Stream，通过 `ReentrantLock + Condition` 实现精准的 Stream 等待唤醒，极大减少连接建立开销。

**四种集群容错** — Failover（失败自动切换）、Failfast（快速失败）、Forking（并行调用取最快）、Failback（失败后台重试）。

**四种负载均衡** — RoundRobin（加权轮询）、Random（加权随机）、LeastActive（最少活跃）、ConsistentHash（一致性哈希）。

**多层加密体系** — AES-256-GCM（Intel AES-NI 硬件加速）、ChaCha20-Poly1305（ARM 友好，基于 BouncyCastle）、AES-CTR-HMAC（经典流式加密）、None（调试用）。

**Filter 责任链** — 通过 `@Activate` 自动发现排序，内置 6 个 Filter：路由过滤、滑动窗口令牌桶限流、监控统计、访问日志、流量控制。

**双协议共端口** — 首字节嗅探（0x05 = SOCKS5，ASCII = HTTP），同一端口支持 SOCKS5 和 HTTP CONNECT，动态修改 Netty Pipeline 零拷贝切换。

**智能路由分流** — 基于域名的 proxyList / directList 通配符匹配，国内直连国外代理。

**Nginx 反向代理** — 生产部署 Netty 只绑定 127.0.0.1，由 nginx stream 模块做 TCP 四层代理对外暴露，可叠加 IP 白名单和限流。

**系统代理自动设置** — 支持 macOS（networksetup）、Windows（注册表）、Linux（gsettings）自动配置/还原系统级代理。

**全异步 CompletableFuture** — 交换层到集群层全链路异步，配合 Netty HashedWheelTimer 做超时检测。

## Web 管理面板

```bash
cd dashboard && node server.js
# 访问 http://localhost:3000
```

支持：可视化编辑 proxy.yml / remote.yml 全部参数、远程节点增删改、路由规则编辑、配置预设管理、YAML 导入导出、外部文件变更实时推送（SSE）、表单验证。零外部依赖。

## 配置参考

### proxy-local（proxy.yml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| localPort | 1080 | 本机代理监听端口 |
| remoteServers[].host | — | 远程服务端地址 |
| remoteServers[].port | 9090 | 远程服务端端口 |
| remoteServers[].cipher | none | 加密算法，需与服务端一致 |
| remoteServers[].cipherKey | — | 加密密钥，需与服务端一致 |
| cluster | failover | 集群容错策略 |
| loadBalance | roundrobin | 负载均衡策略 |
| timeoutMs | 30000 | 请求超时（ms） |
| connectionsPerNode | 1 | 每节点 HTTP/2 连接数 |
| httpProxyEnabled | true | 是否支持 HTTP CONNECT |
| route.defaultRoute | direct | 默认路由（proxy/direct） |
| systemProxy.enabled | false | 自动设置系统代理 |

### proxy-remote（remote.yml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| host | 127.0.0.1 | 监听地址（nginx 代理模式绑定本地） |
| port | 19090 | 监听端口（nginx 对外 9090 转发到此端口） |
| bizThreads | 200 | 业务线程池大小 |
| cipher | none | 加密算法，需与客户端一致 |
| cipherKey | — | 加密密钥，需与客户端一致 |
| maxStreams | 1000 | 单连接最大并发 Stream 数 |
| readIdleTimeout | 60 | 读空闲超时（秒） |
| outbound.connectTimeoutMs | 5000 | 连接目标站点超时 |
| outbound.activeWaitTimeoutMs | 5000 | 等待出站连接就绪超时 |

## 开启加密

客户端和服务端设置相同的 cipher 和 cipherKey 即可：

```yaml
# proxy.yml
cipher: "aes-gcm"
cipherKey: "your-secret-key"

# remote.yml
cipher: aes-gcm
cipherKey: your-secret-key
```

支持的算法：`none`（无加密）、`aes-gcm`（推荐 x86）、`chacha20`（推荐 ARM）、`aes-ctr-hmac`（经典组合）。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 1.8+ | 基础语言 |
| Netty | 4.1.108 | NIO 网络框架、HTTP/2 |
| BouncyCastle | 1.70 | ChaCha20-Poly1305 |
| SnakeYAML | 2.2 | YAML 配置解析 |
| SLF4J + Logback | 1.7.36 / 1.2.11 | 日志 |
| JUnit 5 | 5.10.2 | 测试 |
| Nginx | 1.24+ | TCP 四层反向代理 |
| Docker | — | 容器化部署 |
| Node.js | — | Web 管理面板 |

## 扩展指南

得益于 SPI 架构，扩展任何层只需两步：实现对应接口 → 在 `META-INF/proxy/{接口全限定名}` 注册。无需修改框架已有代码，符合开闭原则。

## 常见问题

**启动报 `Address already in use`** — 端口被占用，`kill $(lsof -ti :1080)` 后重试。

**连接远端失败 `Connection refused`** — 确认远程端已启动、nginx 正常运行、安全组已放行端口、proxy.yml 中 IP 正确。

**访问超时无报错** — 检查域名是否在 proxyList 中，或 defaultRoute 是否为 proxy。

**YAML 解析报错** — remoteServers 列表项需使用标准 YAML 格式（每个字段独立一行缩进），不要用逗号分隔的 inline 写法。

## License

MIT
