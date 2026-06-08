# SimplePlanePlatform

基于 Dubbo 微内核思想设计的高性能加密隧道代理框架，支持 SOCKS5/HTTP CONNECT 代理模式和 TUN 全局透明代理模式。全链路 SPI 可插拔，集群容错、负载均衡、加密算法、传输层实现均可独立扩展替换。

## 项目定位

将 Dubbo 经典的微内核 + 插件化架构应用到网络代理场景。支持两种工作模式：

- **代理模式（SOCKS5 / HTTP CONNECT）**：应用程序主动通过代理端口转发流量，适合浏览器/命令行等可配置代理的场景。
- **TUN 模式（全局透明代理）**：通过虚拟网卡劫持系统全部流量，配合 FakeDNS 实现应用程序无感知的全局代理，无需逐个配置。

## 架构总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TUN Mode (tun-adapter)                          │
│   utun9 虚拟网卡 → smoltcp 用户态协议栈 → FakeDNS/真实DNS分流          │
│   → 域名路由判断 → SOCKS5 Client → proxy-local                         │
├─────────────────────────────────────────────────────────────────────────┤
│                         Local Server Layer                               │
│   ProxyLocalServer / ProtocolDetector / SOCKS5 / HTTP CONNECT           │
├─────────────────────────────────────────────────────────────────────────┤
│                         Cluster Layer                                    │
│   ClusterInvoker: Failover / Failfast / Forking / Failback              │
│   LoadBalance: RoundRobin / Random / LeastActive / ConsistentHash       │
├─────────────────────────────────────────────────────────────────────────┤
│                         Filter Chain Layer                               │
│   Router → RateLimit → Monitor → AccessLog → Traffic                    │
├─────────────────────────────────────────────────────────────────────────┤
│                         Exchange Layer                                   │
│   HeaderExchangeClient / DefaultFuture / RequestId-Future 映射          │
├─────────────────────────────────────────────────────────────────────────┤
│                         Transport Layer                                  │
│   NettyClient / HTTP/2 Stream 多路复用 / 连接管理                       │
├─────────────────────────────────────────────────────────────────────────┤
│                         Codec & Crypto Layer                             │
│   ProxyMessage 编解码 / AES-GCM / ChaCha20 / AES-CTR-HMAC              │
├─────────────────────────────────────────────────────────────────────────┤
│                         SPI Core (proxy-common)                          │
│   ExtensionLoader / @SPI / @Activate / @Order                           │
└─────────────────────────────────────────────────────────────────────────┘
```

### TUN 模式数据流

```
┌───────────┐   DNS query    ┌──────────────────────────────────────────────┐
│  任意 App  │ ────────────→ │            tun-adapter (utun9)               │
│           │   TCP/UDP      │                                              │
└───────────┘ ────────────→ │  ┌─────────────────────────────────────┐     │
                             │  │  DNS 分流引擎                        │     │
                             │  │  ├─ 内网域名 → 转发到真实 DNS 服务器 │     │
                             │  │  └─ 外网域名 → FakeDNS 分配虚拟 IP  │     │
                             │  └─────────────────────────────────────┘     │
                             │  ┌─────────────────────────────────────┐     │
                             │  │  路由判断                            │     │
                             │  │  ├─ 内网 IP/域名 → 直连（bypass）   │     │
                             │  │  └─ 外网 IP → SOCKS5 转发           │     │
                             │  └─────────────────────────────────────┘     │
                             └──────────────────┬───────────────────────────┘
                                                │ SOCKS5
                                                ▼
                             ┌──────────────────────────────────────────────┐
                             │         proxy-local (port 1080)              │
                             │    Filter Chain → Cluster → Exchange         │
                             └──────────────────┬───────────────────────────┘
                                                │ HTTP/2 加密隧道
                                                ▼
                             ┌──────────────────────────────────────────────┐
                             │     proxy-remote (远程服务器)                 │
                             │         → 代为访问目标网站                    │
                             └──────────────────────────────────────────────┘
```

### 内网共存原理

TUN 模式的核心挑战是不能破坏内网连接（如 VPN、企业服务）。本项目通过三层机制保证内网可达：

1. **DNS 分流**：在 smoltcp 用户态协议栈拦截所有 DNS 请求，对内网域名（如 `*.sankuai.com`）转发到真实内网 DNS 服务器获取真实 IP，外网域名走 FakeDNS 分配虚拟 IP。
2. **路由 bypass**：通过系统路由表为内网网段（10/8、11/8、172.16/12、192.168/16）和代理服务器 IP 添加 bypass 路由，使这些流量不进入 TUN 设备。
3. **macOS resolver 分流**：在 `/etc/resolver/` 下为内网域名创建配置文件，使系统级 DNS 查询也能正确路由到内网 DNS。

## 模块结构

```
SimplePlanePlatform/
├── proxy-common            SPI 内核：接口定义、ExtensionLoader、注解体系、数据模型
├── proxy-exchange          交换层：请求-响应语义（RequestId + Future 映射）
├── proxy-transport-netty   传输层：Netty HTTP/2 多路复用、编解码、心跳
├── proxy-crypto            加密层：4 种可插拔 AEAD 加密实现
├── proxy-cluster           集群层：4 种容错策略 + 4 种负载均衡 + 6 个 Filter
├── proxy-local             本地客户端：SOCKS5/HTTP CONNECT 双协议、路由分流、系统代理
├── proxy-remote            远程服务端：请求分发、出站连接管理、nginx 部署脚本
├── tun-adapter             TUN 透明代理：Rust 实现，smoltcp 协议栈 + FakeDNS + 域名路由
├── dashboard               Web 管理面板：可视化编辑所有配置参数
├── start-tun.sh            TUN 模式一键启动脚本
└── restore-dns.sh          TUN 异常退出后的 DNS 恢复脚本
```

## 快速开始

### 环境要求

| 组件 | 要求 | 用途 |
|------|------|------|
| JDK | 1.8+ | 编译运行 proxy-local / proxy-remote |
| Maven | 3.6+ | Java 项目构建 |
| Rust | stable | 编译 tun-adapter |
| macOS | 10.15+ | TUN 模式（需要 root 权限） |
| 云服务器 | 公网 IP | 部署 proxy-remote |

### 编译打包

```bash
git clone https://github.com/zhh293/SimplePlanePlatform.git
cd SimplePlanePlatform

# Java 部分
mvn clean package -DskipTests

# Rust TUN 适配器
cd tun-adapter
cargo build --release
cd ..
```

产物位置：

- `proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar`（本地代理客户端）
- `proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar`（远程代理服务端）
- `tun-adapter/target/release/tun-adapter`（TUN 透明代理）

## 部署远程服务端

将 jar 上传到服务器：

```bash
scp -i <your-key.pem> proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar <user>@<server-ip>:~/
```

### 方式一：Nginx 反向代理部署（推荐）

生产环境推荐通过 nginx 做 TCP 四层代理，Netty 只绑定 `127.0.0.1` 不直接暴露：

```bash
ssh -i <your-key.pem> <user>@<server-ip> 'bash -s' < proxy-remote/deploy-nginx.sh
```

脚本自动完成：安装 nginx stream 模块 → 写入配置 → 启动 Netty(127.0.0.1:19090) → nginx 对外监听 9090 转发。最终架构：

```
客户端 → nginx(0.0.0.0:9090) → Netty(127.0.0.1:19090) → 目标网站
```

### 方式二：直接部署

如不需要 nginx 层，可直接启动（需将 `remote.yml` 中 host 改为 `0.0.0.0`，port 改为 `9090`）：

```bash
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
```

确保安全组/防火墙已开放对应端口（TCP 入站）。

## 使用方式一：代理模式（SOCKS5 / HTTP CONNECT）

适合浏览器或支持代理设置的应用。

### 配置

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

route:
  defaultRoute: direct
  proxyList:
    - "*.google.com"
    - "*.github.com"
    - "*.youtube.com"
    - "*.openai.com"
  directList:
    - "*.baidu.com"
    - 127.0.0.1
    - localhost
```

路由优先级：`directList` > `proxyList` > `defaultRoute`。

### 启动

```bash
mvn package -pl proxy-local -am -DskipTests
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar
```

看到 `Proxy Local Server started on port 1080` 即启动成功。

### 验证

```bash
curl -x socks5://127.0.0.1:1080 --max-time 10 -I https://www.google.com
curl -x http://127.0.0.1:1080 --max-time 10 -I https://github.com
```

### 系统代理设置

可在 `proxy.yml` 中启用自动设置：

```yaml
systemProxy:
  enabled: true
  host: 127.0.0.1
```

或手动设置系统代理指向 `127.0.0.1:1080`（HTTP/SOCKS5 均可），也可使用 SwitchyOmega 等浏览器插件。

## 使用方式二：TUN 全局透明代理模式（推荐）

TUN 模式通过虚拟网卡劫持全部系统流量，应用程序无需任何配置即可实现全局代理。适合需要所有流量都走代理，同时保持内网/VPN 正常的场景。

### 前置配置

1. **编辑 `proxy-local/src/main/resources/proxy.yml`**：配置远程服务器地址，`defaultRoute` 设为 `proxy`（TUN 模式下所有进入 proxy-local 的流量都应走代理）。

2. **编辑 `tun-adapter/config/tun.toml`**：

```toml
[bypass]
# 替换为你的代理服务器真实 IP
proxy_remote_ips = ["YOUR_PROXY_REMOTE_IP"]
# 不进入 TUN 的内网网段
extra_cidrs = ["10.0.0.0/8", "11.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]

[intranet_dns]
# 内网 DNS 服务器地址
servers = ["11.11.11.11", "11.11.11.12"]
# 需要走内网 DNS 的域名后缀
domains = ["sankuai.com", "meituan.com", "sankuai.info", "neixin.cn", "dianping.com", "meituan.net"]
```

### 一键启动

```bash
./start-tun.sh
```

脚本自动完成以下步骤：

1. 检查上次是否异常退出，如是则先恢复 DNS
2. 编译 proxy-local（如有需要）并启动，等待 1080 端口就绪
3. 编译 tun-adapter（如有需要）并以 sudo 启动
4. 创建 utun9 虚拟网卡、配置系统路由、设置 DNS 分流
5. 输出运行状态信息

启动成功后所有流量自动分流，无需手动配置。按 `Ctrl+C` 停止，脚本会自动恢复所有系统设置。

### 手动启动（高级）

如果需要分步调试：

```bash
# 终端 1：启动 proxy-local（带 DNS bypass 参数）
java -Dproxy.dns.nameservers=114.114.114.114,223.5.5.5 \
     -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar

# 终端 2：启动 tun-adapter（需要 root 权限）
cd tun-adapter
sudo ./target/release/tun-adapter -c config/tun.toml
```

### 验证 TUN 模式

```bash
# 检查 TUN 设备是否创建
ifconfig utun9

# 验证内网域名解析走真实 DNS（应返回 10.x.x.x 内网 IP）
nslookup your-intranet-domain.com

# 验证外网走代理
curl --max-time 10 -I https://www.google.com
```

### 异常恢复

如果 tun-adapter 异常退出（kill -9、终端意外关闭等）导致网络异常：

```bash
sudo ./restore-dns.sh
```

该脚本会读取 `/tmp/tun-adapter-dns-backup.conf` 备份文件，自动恢复 DNS 设置、删除 `/etc/resolver/` 配置、刷新 DNS 缓存。

### TUN 模式配置参考（tun.toml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| tun.name | utun9 | TUN 设备名称 |
| tun.address | 198.18.0.1 | TUN 设备 IP 地址 |
| tun.mtu | 1500 | MTU 大小 |
| fakeip.range | 198.18.0.0/15 | FakeDNS 虚拟 IP 分配范围 |
| fakeip.capacity | 65536 | FakeDNS 映射表容量 |
| proxy.socks5_addr | 127.0.0.1:1080 | 上游 SOCKS5 代理地址 |
| routing.default_action | proxy | 默认路由动作（proxy/direct） |
| routing.rules[] | — | 域名/IP 路由规则（见下方） |
| bypass.proxy_remote_ips | — | 代理服务器 IP（bypass 路由） |
| bypass.extra_cidrs | — | 额外 bypass 网段 |
| bypass.dns_bypass_ips | — | DNS bypass IP |
| intranet_dns.servers | — | 内网 DNS 服务器 |
| intranet_dns.domains | — | 内网域名后缀列表 |

路由规则类型支持：`domain_suffix`（域名后缀匹配）、`domain_keyword`（域名关键词匹配）、`ip_cidr`（IP 段匹配）。

## Docker 部署

项目提供 docker-compose 一键编排，两端在同一 compose 网络内通信：

```bash
docker compose up -d --build    # 构建并启动
docker compose logs -f          # 查看日志
docker compose down             # 停止
```

容器模式下 proxy-local 的 1080 端口映射到宿主机，通过服务名 `proxy-remote` 连接服务端。加密配置见 `docker/proxy.yml` 和 `docker/remote.yml`。

## Web 管理面板

```bash
cd dashboard && node server.js
# 访问 http://localhost:3000
```

支持：可视化编辑 proxy.yml / remote.yml 全部参数、远程节点增删改、路由规则编辑、配置预设管理、YAML 导入导出、外部文件变更实时推送（SSE）、表单验证。零外部依赖。

## 核心特性

**全链路 SPI 可插拔** — 借鉴 Dubbo ExtensionLoader，7 个扩展点（Transporter、Exchanger、Cipher、ClusterInvoker、LoadBalance、Filter、FilterChainBuilder）均通过 `@SPI` 注解标记，支持按名加载、默认实现、条件激活。扩展只需实现接口 + 在 `META-INF/proxy/` 注册。

**HTTP/2 多路复用传输** — 单条 TCP 连接承载 1000+ 并发 Stream，通过 `ReentrantLock + Condition` 实现精准的 Stream 等待唤醒，极大减少连接建立开销。

**TUN 全局透明代理** — Rust 实现的用户态协议栈（smoltcp），通过 macOS utun 设备劫持全部流量，配合 FakeDNS 实现域名级路由判断。内网域名自动转发到真实 DNS 解析，确保 VPN 和企业服务不受影响。

**四种集群容错** — Failover（失败自动切换）、Failfast（快速失败）、Forking（并行调用取最快）、Failback（失败后台重试）。

**四种负载均衡** — RoundRobin（加权轮询）、Random（加权随机）、LeastActive（最少活跃）、ConsistentHash（一致性哈希）。

**多层加密体系** — AES-256-GCM（Intel AES-NI 硬件加速）、ChaCha20-Poly1305（ARM 友好，基于 BouncyCastle）、AES-CTR-HMAC（经典流式加密）、None（调试用）。

**Filter 责任链** — 通过 `@Activate` 自动发现排序，内置 6 个 Filter：路由过滤、滑动窗口令牌桶限流、监控统计、访问日志、流量控制。

**双协议共端口** — 首字节嗅探（0x05 = SOCKS5，ASCII = HTTP），同一端口支持 SOCKS5 和 HTTP CONNECT，动态修改 Netty Pipeline 零拷贝切换。

**智能路由分流** — 基于域名的 proxyList / directList 通配符匹配，国内直连国外代理。TUN 模式下支持 domain_suffix / domain_keyword / ip_cidr 三种匹配方式。

**优雅启停** — TUN 模式注册 SIGTERM handler，退出时通过 Rust Drop trait 自动恢复系统路由表和 DNS 设置，异常退出可通过 `restore-dns.sh` 手动恢复。

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
| Java | 1.8+ | 代理客户端/服务端 |
| Netty | 4.1.108 | NIO 网络框架、HTTP/2 |
| Rust | stable | TUN 适配器 |
| smoltcp | 0.11 | 用户态 TCP/IP 协议栈 |
| tokio | 1.x | Rust 异步运行时 |
| tun (crate) | 0.7 | macOS TUN 设备操作 |
| BouncyCastle | 1.70 | ChaCha20-Poly1305 |
| SnakeYAML | 2.2 | YAML 配置解析 |
| SLF4J + Logback | 1.7.36 / 1.2.11 | 日志 |
| JUnit 5 | 5.10.2 | 测试 |
| Nginx | 1.24+ | TCP 四层反向代理 |
| Docker | — | 容器化部署 |
| Node.js | — | Web 管理面板 |

## 扩展指南

得益于 SPI 架构，扩展任何层只需两步：实现对应接口 → 在 `META-INF/proxy/{接口全限定名}` 注册。无需修改框架已有代码，符合开闭原则。

TUN 适配器的路由规则同样支持扩展，在 `tun.toml` 的 `[[routing.rules]]` 中添加新规则即可生效。

## 常见问题

**启动报 `Address already in use`** — 端口被占用，`kill $(lsof -ti :1080)` 后重试。

**连接远端失败 `Connection refused`** — 确认远程端已启动、nginx 正常运行、安全组已放行端口、proxy.yml 中 IP 正确。

**TUN 模式启动后网络中断** — 检查 `tun.toml` 中 `bypass.proxy_remote_ips` 是否正确填写了代理服务器 IP。如果代理服务器 IP 没有被 bypass，其流量也会进入 TUN 形成死循环。

**TUN 模式下内网无法访问** — 确认 `intranet_dns.domains` 包含了所有内网域名后缀，且 `intranet_dns.servers` 填写了正确的内网 DNS 地址。同时确认 `bypass.extra_cidrs` 覆盖了内网 IP 段。

**TUN 异常退出后 DNS 坏了** — 运行 `sudo ./restore-dns.sh` 恢复，或手动执行 `sudo networksetup -setdnsservers Wi-Fi Empty && dscacheutil -flushcache`。

**访问超时无报错** — 检查域名是否在 proxyList 中（代理模式），或 routing.rules 中是否配置了对应的 direct 规则（TUN 模式）。

**cargo build 报错** — 确保已安装 Rust 工具链：`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`

## License

MIT
