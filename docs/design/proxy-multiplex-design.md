# 浏览器代理连接池瓶颈优化 —— 多端口监听 + PAC 脚本方案

## 1. 背景与问题

### 1.1 问题现象

用户在使用代理访问网站时，首次打开页面时 Chrome DevTools 的 Timing 面板经常出现长达数秒甚至近 10 秒的黑色 **Stalled** 时间。再次访问同一页面时，Stalled 降至微秒级。

### 1.2 根因分析

通过 `chrome://net-export/` 抓取浏览器内部日志，发现关键数据：

```
Socket Pool: 127.0.0.1:1080 (http_proxy_socket_pool)
  Max: 256
  Max Per Group: 6
  Handed Out: 19
  Idle: 1
```

Chrome 对同一个 `host:port` 的最大并发连接数硬编码为 **6**（`Max Per Group = 6`），写在 Chromium 源码中，无法通过配置修改。

正常情况下（不走代理），这个限制按目标域名独立计算——`google.com` 6 个、`github.com` 6 个，互不影响。但走了本地代理后，不管目标域名是什么，浏览器看到的都是连向 `127.0.0.1:1080` 的连接，所有请求共享同一个 socket group，共用 6 个连接。

第一次打开页面时，浏览器需要同时加载几十甚至上百个资源（HTML、JS、CSS、图片、字体、API 请求），全部挤在 6 个连接里排队。排在前面的每个请求通过代理走一趟来回至少几百毫秒，排在后面的请求就 Stalled 越来越久。

再次访问时，静态资源已被浏览器缓存，请求数量大幅减少，6 个连接够用，Stalled 降至微秒级。

### 1.3 问题边界

这个问题的根因在浏览器侧（Chrome 硬编码的连接池限制），代理服务器端无法直接修复。但可以通过**改变代理的连接拓扑**来规避——让浏览器认为它在连不同的代理地址，从而获得多个独立的 socket group。

## 2. 方案选型

### 2.1 方案对比

| 方案 | 原理 | 优势 | 劣势 |
|------|------|------|------|
| **A. HTTP/2 CONNECT (RFC 8441)** | 浏览器到本地代理使用 HTTP/2，所有 CONNECT 隧道多路复用在单条连接上 | 彻底解决，无连接数限制 | Chrome 对显式配置的代理不支持 HTTP/2；实现复杂度极高 |
| **B. 多端口监听 + PAC 脚本** | 代理监听多个端口，PAC 脚本按域名哈希分发到不同端口 | 实现简单，效果显著，兼容性好 | 需要浏览器支持 PAC（Chrome/Firefox/Safari 均支持） |
| **C. SOCKS5 代理模式** | 切换到 SOCKS5 协议 | 配置简单 | Chrome 对 SOCKS5 代理同样有类似的 group 限制，改善有限 |

### 2.2 选定方案：B —— 多端口监听 + PAC 脚本

选择方案 B 的核心理由：

1. **即时有效**：6 个端口 × 6 连接/端口 = 36 个并发连接，足以覆盖任何典型页面的首次加载需求。
2. **实现成本低**：只需在 proxy-local 增加多端口监听 + 一个轻量 PAC HTTP 服务，不涉及核心代理逻辑的改动。
3. **兼容性好**：PAC 是标准的代理配置协议，Chrome、Firefox、Safari 均原生支持。macOS、Windows、Linux 系统级代理设置也都支持 Auto Proxy Discovery。
4. **对现有架构零侵入**：每个端口的 pipeline 完全相同（ProtocolDetector → handlers），共享同一个 Invoker、RouteRule，不需要修改代理转发逻辑。

## 3. 整体架构

### 3.1 架构变化对比

**现有架构（单端口）**：

```
Browser ──(6 connections max)──→ 127.0.0.1:1080 (proxy-local)
                                      │
                                 HTTP/2 Tunnel
                                      │
                                 proxy-remote → Target
```

所有请求共享 6 个连接，首次加载时排队严重。

**优化后架构（多端口 + PAC）**：

```
Browser ──PAC Script──→ 域名哈希选择端口
                          │
           ┌──────────────┼──────────────┐
           │              │              │
     127.0.0.1:1080  127.0.0.1:1081  127.0.0.1:1085  (6 ports × 6 conn = 36)
           │              │              │
           └──────┬───────┴──────────────┘
                  │
            Shared Invoker / RouteRule
                  │
            HTTP/2 Tunnel (不变)
                  │
            proxy-remote → Target
```

每个端口是独立的 Chrome socket group，各享 6 连接配额，总并发提升到 36。

### 3.2 PAC 脚本工作原理

PAC（Proxy Auto-Config）是浏览器标准的代理配置方式。浏览器加载 PAC 脚本后，对每个请求调用 `FindProxyForURL(url, host)` 函数，根据返回值决定如何连接。

本方案的 PAC 脚本逻辑：

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

**设计要点**：

1. **域名哈希**：对目标域名做字符串哈希，映射到端口列表。同一域名始终走同一端口，保证连接可复用（keep-alive）。
2. **均匀分布**：不同域名分散到不同端口，避免热点。
3. **不处理直连路由**：PAC 脚本不判断哪些域名应该直连——这个逻辑继续由 proxy-local 内部的 `RouteRule` 处理。PAC 只负责端口分发，路由策略保持不变。

### 3.3 PAC 脚本分发

浏览器需要通过 HTTP URL 加载 PAC 脚本（Chrome 不支持 `file://` 协议的 PAC）。方案是在 proxy-local 内嵌一个轻量 HTTP 服务，专门用于提供 PAC 脚本：

```
Browser ──GET http://127.0.0.1:8080/proxy.pac──→ proxy-local PAC Server
         ←── PAC Script Content ──────────────────

Browser ──(根据PAC选择端口)──→ 127.0.0.1:1080~1085 (proxy-local Proxy Listeners)
```

PAC HTTP 服务与代理监听服务共用同一个 Netty EventLoopGroup，不额外创建线程池。

## 4. 配置变更

### 4.1 现有配置

```yaml
localPort: 1080
systemProxy:
  enabled: false
  host: 127.0.0.1
```

### 4.2 新增配置

```yaml
# 多端口监听（新）
localPorts: [1080, 1081, 1082, 1083, 1084, 1085]

# 向后兼容：如果未配置 localPorts，回退到 localPort 单端口模式
localPort: 1080

# PAC 服务（新）
pacServer:
  enabled: true
  port: 8080          # PAC HTTP 服务端口
  listenHost: 127.0.0.1

# 系统代理（修改）
systemProxy:
  enabled: true
  mode: pac           # "pac"（使用PAC脚本）或 "direct"（直接设置代理地址，旧模式）
  host: 127.0.0.1
```

当 `systemProxy.mode = pac` 时，SystemProxyManager 通过系统命令设置 Auto Proxy URL（而非直接设置代理地址）。当 `mode = direct` 时，保持原有行为。

## 5. 新增/修改的类

### 5.1 修改：ProxyLocalServer

**位置**：`proxy-local/src/main/java/com/proxy/local/ProxyLocalServer.java`

**改动**：

1. `start()` 方法支持绑定多个端口，为每个端口创建独立的 `ServerBootstrap`，共享同一个 `bossGroup` 和 `workerGroup`。
2. 新增 PAC HTTP 服务的启动逻辑。
3. `shutdown()` 方法关闭所有监听端口和 PAC 服务。

### 5.2 修改：ProxyConfig

**位置**：`proxy-local/src/main/java/com/proxy/local/config/ProxyConfig.java`

**改动**：

1. 新增 `localPorts` 字段（`List<Integer>`）。
2. 新增 `PacServerConfig` 内部类（`enabled` + `port` + `listenHost`）。
3. `SystemProxy` 内部类新增 `mode` 字段（`"pac"` 或 `"direct"`）。

### 5.3 新增：PacScriptGenerator

**位置**：`proxy-local/src/main/java/com/proxy/local/pac/PacScriptGenerator.java`

**职责**：根据监听端口列表生成 PAC 脚本字符串。纯工具类，无网络依赖。

### 5.4 新增：PacServerHandler

**位置**：`proxy-local/src/main/java/com/proxy/local/pac/PacServerHandler.java`

**职责**：Netty ChannelHandler，处理 HTTP GET 请求，返回 PAC 脚本内容。只处理 `/proxy.pac` 路径，其他路径返回 404。

### 5.5 修改：SystemProxyManager 接口及实现

**位置**：`proxy-local/src/main/java/com/proxy/local/systemproxy/SystemProxyManager.java`

**改动**：

1. 接口新增 `enablePac(String pacUrl)` 和 `disablePac()` 方法。
2. `MacSystemProxyManager`：通过 `networksetup -setautoproxyurl` 设置 PAC URL。
3. `WindowsSystemProxyManager`：通过注册表设置 `AutoConfigURL`。
4. `LinuxSystemProxyManager`：通过 `gsettings` 设置 `org.gnome.system.proxy autoconfig url`。

## 6. 交互流程

### 6.1 启动流程

```
ProxyLocalServer.main()
  │
  ├── 加载 proxy.yml 配置
  ├── 构建 Invoker 调用链（不变）
  │
  ├── 启动代理监听
  │   ├── 绑定 127.0.0.1:1080 → ProtocolDetector pipeline
  │   ├── 绑定 127.0.0.1:1081 → ProtocolDetector pipeline
  │   ├── ...
  │   └── 绑定 127.0.0.1:1085 → ProtocolDetector pipeline
  │
  ├── 启动 PAC HTTP 服务
  │   └── 绑定 127.0.0.1:8080 → PacServerHandler
  │
  └── 设置系统代理
      ├── mode = pac → enablePac("http://127.0.0.1:8080/proxy.pac")
      └── mode = direct → enable("127.0.0.1", 1080)  （旧模式）
```

### 6.2 浏览器请求流程

```
1. Browser 启动 → 加载 PAC: http://127.0.0.1:8080/proxy.pac
   ←── PAC Script (含端口列表 [1080,1081,...,1085])

2. Browser 访问 https://github.com
   ├── 调用 FindProxyForURL("https://github.com", "github.com")
   ├── hash("github.com") % 6 = 2 → return "PROXY 127.0.0.1:1082"
   └── Browser 连接 127.0.0.1:1082 → CONNECT github.com:443

3. Browser 加载 github.com 页面资源
   ├── "assets-cdn.github.com" → hash → port 1084 → 新 socket group (6 conn)
   ├── "avatars.githubusercontent.com" → hash → port 1080 → 新 socket group (6 conn)
   ├── "collector.githubapp.com" → hash → port 1083 → 新 socket group (6 conn)
   └── ... 每个域名独立 group，不再互相排队
```

## 7. 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `ProxyLocalServer.java` | **修改** | 支持多端口绑定 + PAC 服务启动 |
| `ProxyConfig.java` | **修改** | 新增 localPorts、PacServerConfig、SystemProxy.mode |
| `PacScriptGenerator.java` | **新增** | PAC 脚本生成工具类 |
| `PacServerHandler.java` | **新增** | PAC HTTP 请求处理器 |
| `SystemProxyManager.java` | **修改** | 接口新增 enablePac/disablePac |
| `MacSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `WindowsSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `LinuxSystemProxyManager.java` | **修改** | 实现 PAC 模式 |
| `proxy.yml` | **修改** | 新增 localPorts、pacServer 配置项 |

## 8. 风险与注意事项

### 8.1 端口冲突

多个监听端口需要确保未被占用。启动时应逐个检查端口绑定结果，如果某个端口绑定失败，记录警告但继续启动其他端口，并从 PAC 脚本的端口列表中移除该端口。

### 8.2 PAC 脚本缓存

浏览器会缓存 PAC 脚本（默认缓存时间约 30 分钟）。如果端口列表发生变化，用户需要手动刷新 PAC 缓存（重启浏览器或清除缓存）。在端口列表固定的常规使用场景下这不是问题。

### 8.3 端口数量选择

6 个端口提供 36 个并发连接，足以覆盖典型页面的首次加载（一般 30-60 个并发请求）。更多的端口会带来更多并发，但也会增加 TCP 连接数和内存开销。6 是一个平衡点。

### 8.4 向后兼容

如果用户不配置 `localPorts` 和 `pacServer`，系统回退到单端口 + 直接代理模式，行为与当前完全一致。新功能是可选的，不会影响现有用户。

### 8.5 线程模型

所有代理监听端口和 PAC HTTP 服务共用同一个 Netty `workerGroup`。PAC 请求极其轻量（返回一个静态字符串），不会影响代理转发的性能。bossGroup 也共用同一个，只负责 accept。

## 9. 任务拆分

| 任务 | 文档 | 内容 | 前置依赖 |
|------|------|------|---------|
| Task-1 | [proxy-multiplex-task-1-multi-port.md](./proxy-multiplex-task-1-multi-port.md) | ProxyLocalServer 多端口监听 + ProxyConfig 配置扩展 | 无 |
| Task-2 | [proxy-multiplex-task-2-pac-server.md](./proxy-multiplex-task-2-pac-server.md) | PAC 脚本生成 + PAC HTTP 服务 + SystemProxyManager PAC 模式 | Task-1 |
| Task-3 | [proxy-multiplex-task-3-integration.md](./proxy-multiplex-task-3-integration.md) | 集成测试、全链路测试、性能验证 | Task-1 + Task-2 |

## 10. 预期效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 最大并发连接数 | 6 | 36（6 端口 × 6 连接） |
| 首次加载 Stalled 最长时间 | ~10 秒 | < 1 秒（36 连接足以覆盖绝大多数页面） |
| 再次加载 Stalled | 微秒级 | 微秒级（不变） |
| 代理转发逻辑改动 | — | 零改动 |
| 配置复杂度 | 单端口 | 多端口 + PAC（一次配置，后续无感） |
