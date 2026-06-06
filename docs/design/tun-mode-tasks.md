# TUN 透明代理 — Phase TUN-1 (MVP) 开发子任务清单

> 本文档将技术规划方案拆解为可逐步执行的开发任务。每个任务包含：目标、输入/输出、技术要求、验收标准。
> 面向 AI 编码助手优化，确保拿到任务即可产出生产级代码。

---

## 全局约束

- 语言：Rust 2021 edition，async runtime 选 tokio
- 代码风格：`cargo fmt`（默认配置）；`cargo clippy -- -D warnings` 零警告
- 错误处理：使用 `thiserror` 定义模块级错误类型，向上传播用 `anyhow::Result`（仅 main/集成测试），库内部不用 `anyhow`
- 日志：全部使用 `tracing` 宏 (`tracing::info!`, `tracing::error!` 等)
- 项目位置：`/Users/zhanghonghao/Desktop/SimplePlanePlatform/tun-adapter/`
- Cargo workspace：tun-adapter 是独立 crate（不加入 Java 的 Maven workspace），有自己的 `Cargo.toml`
- 最低支持平台：macOS aarch64（MVP 开发机），Linux x86_64（Phase TUN-2）
- 对接目标：proxy-local SOCKS5 端口 `127.0.0.1:1080`

---

## Task 1：项目脚手架搭建

### 目标
创建 `tun-adapter` Rust 项目的完整骨架，包含模块划分、依赖声明、配置加载、日志初始化，以及基本的 CLI 入口。

### 产出文件结构

```
tun-adapter/
├── Cargo.toml
├── config/
│   └── tun.toml              # 默认配置文件
├── src/
│   ├── main.rs               # 入口：解析 CLI 参数、加载配置、初始化日志、启动各模块
│   ├── config.rs             # 配置结构体定义与 TOML 反序列化
│   ├── error.rs              # 全局错误类型定义
│   ├── tun_device.rs         # (占位) TUN 设备管理模块
│   ├── stack.rs              # (占位) 用户态 TCP/IP 栈模块
│   ├── fake_dns.rs           # (占位) FakeDNS 引擎模块
│   ├── router.rs             # (占位) 路由决策引擎模块
│   ├── socks5.rs             # (占位) SOCKS5 客户端模块
│   └── route_guard.rs        # (占位) 路由恢复 Drop Guard
├── build.rs                  # (可选) 编译时信息嵌入（git hash、编译时间）
└── .cargo/
    └── config.toml           # macOS linker 设置（如需要）
```

### 技术要求

1. **Cargo.toml 依赖**（锁定兼容版本范围）：
   ```toml
   [dependencies]
   tokio = { version = "1", features = ["full"] }
   tun2 = { version = "4", features = ["async"] }
   smoltcp = { version = "0.11", default-features = false, features = ["std", "medium-ip", "proto-ipv4", "proto-ipv6", "socket-tcp", "socket-udp", "socket-dns"] }
   hickory-proto = "0.24"
   fast-socks5 = "0.9"
   serde = { version = "1", features = ["derive"] }
   toml = "0.8"
   tracing = "0.1"
   tracing-subscriber = { version = "0.3", features = ["env-filter", "fmt", "json"] }
   thiserror = "2"
   anyhow = "1"
   lru = "0.12"
   clap = { version = "4", features = ["derive"] }
   ipnet = "2"
   maxminddb = "0.24"

   [dev-dependencies]
   tokio-test = "0.4"
   ```

2. **config.rs** 要定义完整的配置结构体，对应 `tun.toml` 中的所有字段（参考规划文档 7.3 节的 TOML 示例）。使用 `serde(default)` 提供合理默认值。

3. **main.rs** 流程：
   - 使用 `clap` 解析 `--config <path>` 参数（默认 `config/tun.toml`）
   - 加载并校验配置
   - 初始化 `tracing-subscriber`（支持 `RUST_LOG` 环境变量过滤）
   - 打印启动信息（版本、配置摘要）
   - 预留各模块的启动调用点（用 `todo!()` 或 `unimplemented!()` 标记）

4. **error.rs** 使用 `thiserror` 定义 `TunError` 枚举，至少包含：`Config`, `TunDevice`, `Network`, `Dns`, `Socks5`, `Route` 变体。

### 验收标准

- [ ] `cargo build` 无错误
- [ ] `cargo clippy -- -D warnings` 零警告
- [ ] `cargo run -- --config config/tun.toml` 能启动并打印配置摘要后退出（因为核心模块是 todo）
- [ ] `cargo run -- --help` 显示帮助信息

---

## Task 2：TUN 设备创建与 Drop Guard（macOS）

### 目标
实现 TUN 虚拟网卡的创建、配置、路由设置，以及进程退出时自动恢复路由的安全机制。

### 技术要求

1. **tun_device.rs** 实现 `TunManager` 结构体：
   ```rust
   pub struct TunManager {
       device: AsyncDevice,      // tun2 的异步设备
       route_guard: RouteGuard,  // Drop 时恢复路由
       mtu: u16,
   }
   ```

2. **设备创建**：
   - 使用 `tun2::Configuration` 设置设备名、地址 (`198.18.0.1`)、子网掩码 (`255.254.0.0` 即 /15)、MTU (1500)
   - 调用 `tun2::create_as_async()` 创建异步设备
   - 设备创建后通过 `Command` 执行 `ifconfig` 确认设备状态

3. **路由设置**（macOS 专用，通过 `std::process::Command` 调用系统命令）：
   - 添加 `0.0.0.0/1` 和 `128.0.0.0/1` 指向 TUN 设备（覆盖默认路由）
   - 排除 proxy-remote 真实 IP（从配置读取）
   - 排除内网段 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
   - 获取当前默认网关（解析 `netstat -rn` 或 `route get default` 输出）

4. **route_guard.rs** 实现 `RouteGuard`：
   ```rust
   pub struct RouteGuard {
       original_gateway: IpAddr,
       tun_name: String,
       routes_added: Vec<RouteEntry>,  // 记录所有添加的路由，drop 时逐一删除
   }

   impl Drop for RouteGuard {
       fn drop(&mut self) {
           // 删除所有添加的路由
           // 恢复原始默认路由
           // 日志记录恢复结果
       }
   }
   ```

5. **平台隔离**：使用 `#[cfg(target_os = "macos")]` 条件编译，为 Linux 预留 `#[cfg(target_os = "linux")]` 的空实现骨架。

6. **TunManager 公开接口**：
   ```rust
   impl TunManager {
       pub async fn new(config: &TunConfig) -> Result<Self, TunError>;
       pub fn split(self) -> (TunReader, TunWriter);  // 分离读写端
   }
   ```
   其中 `TunReader` 和 `TunWriter` 分别封装 TUN 设备的读和写半部分，支持并发使用。

### 验收标准

- [ ] 以 root/sudo 运行时能成功创建 TUN 设备
- [ ] `ifconfig` 能看到创建的 utun 设备且 IP 配置正确
- [ ] `netstat -rn` 能看到添加的路由规则
- [ ] 进程正常退出或 `Ctrl+C` 后路由表恢复原状
- [ ] 进程 panic 后路由表也能恢复（验证 Drop 在 panic 时也会执行）

---

## Task 3：FakeDNS 引擎

### 目标
实现 FakeDNS 机制：拦截 DNS 查询请求，分配假 IP 地址并维护双向映射表，为后续的域名路由决策提供支撑。

### 技术要求

1. **fake_dns.rs** 核心结构：
   ```rust
   pub struct FakeDnsEngine {
       ip_to_domain: LruCache<Ipv4Addr, String>,
       domain_to_ip: LruCache<String, Ipv4Addr>,
       pool_start: Ipv4Addr,    // 198.18.0.0
       pool_end: Ipv4Addr,      // 198.19.255.255
       next_ip: u32,            // 内部递增计数器
   }
   ```

2. **FakeIP 池管理**：
   - 地址池范围：`198.18.0.0/15`（共 131,072 个地址）
   - 循环分配：用完后从头开始，覆盖最旧的映射
   - 跳过 `.0` 和 `.255` 地址（避免网络地址/广播地址混淆）
   - LRU 容量建议 65536（足够日常使用，内存占用约 4MB）

3. **DNS 协议解析与构造**：
   - 使用 `hickory-proto` 的 `Message` 类型解析 DNS 查询包
   - 仅处理 A 记录查询（`QueryType::A`），AAAA 返回空（MVP 阶段不支持 IPv6 FakeIP）
   - 构造 DNS 响应时设置 TTL=1（防止系统 DNS 缓存长期持有假 IP）
   - 正确设置 DNS 报文的 ID、flags（QR=1, RA=1, RCODE=NoError）

4. **公开接口**：
   ```rust
   impl FakeDnsEngine {
       pub fn new(pool_cidr: Ipv4Net, capacity: usize) -> Self;

       /// 处理 DNS 查询的原始 UDP payload，返回 DNS 响应的原始 bytes
       pub fn handle_dns_query(&mut self, query_payload: &[u8]) -> Result<Vec<u8>, DnsError>;

       /// 根据假 IP 反查域名
       pub fn lookup_domain(&self, fake_ip: &Ipv4Addr) -> Option<&str>;

       /// 判断一个 IP 是否在 FakeIP 池范围内
       pub fn is_fake_ip(&self, ip: &Ipv4Addr) -> bool;
   }
   ```

5. **线程安全**：`FakeDnsEngine` 本身不需要是 `Send + Sync`（会被包在 `Mutex` 或通过 channel 使用），但内部数据结构要支持单线程高效访问。

### 验收标准

- [ ] 单元测试：构造一个 `www.google.com` 的 A 记录 DNS 查询 payload，调用 `handle_dns_query` 后能拿到合法的 DNS 响应，且 answer 中的 IP 在 FakeIP 池范围内
- [ ] 单元测试：同一域名重复查询返回相同 FakeIP
- [ ] 单元测试：不同域名返回不同 FakeIP
- [ ] 单元测试：`lookup_domain` 能正确反查
- [ ] 单元测试：池满后循环分配不 panic，最旧的映射被淘汰
- [ ] 单元测试：AAAA 查询返回空 answer 的合法 DNS 响应

---

## Task 4：smoltcp 用户态 TCP/IP 栈集成

### 目标
将 smoltcp 接入 TUN 设备，实现原始 IP 包的接收、TCP 连接的握手与字节流提取、UDP 数据报的提取。这是整个系统中最核心也最复杂的模块。

### 技术要求

1. **stack.rs** 核心架构：

   ```rust
   /// 用户态协议栈管理器
   pub struct UserStack {
       interface: Interface,           // smoltcp 网络接口
       sockets: SocketSet<'static>,    // socket 集合
       device: TunSmolDevice,          // 桥接 TUN 设备与 smoltcp 的适配器
   }
   ```

2. **TUN ↔ smoltcp 桥接**：
   - 实现 `smoltcp::phy::Device` trait 来适配 TUN 设备
   - TUN 读到的是 IP 层包（无以太网帧头），smoltcp 需要配置为 `Medium::Ip`
   - 使用 ring buffer 作为收发缓冲区：`RxToken` / `TxToken` 的实现
   - 缓冲区大小建议：单包 MTU 1500 bytes，队列深度 256

3. **TCP 连接处理流程**：
   - smoltcp 接收 SYN 后自动完成三次握手（smoltcp 的 listen socket 机制）
   - 使用"万能监听"模式：smoltcp 监听所有端口（`0`），任何入站 SYN 都接受
   - TCP socket 建立后通过 channel 通知上层（发送事件：`NewTcpConnection { src, dst_ip, dst_port, socket_handle }`）
   - 上层拿到 socket handle 后读写字节流

4. **UDP 数据报处理**：
   - 监听所有 UDP 入站数据报
   - 区分 DNS 流量 (dst_port=53) 和非 DNS 流量
   - DNS 流量交给 FakeDnsEngine 处理，响应写回 TUN
   - 非 DNS UDP 流量在 MVP 阶段直接丢弃（后续 Phase TUN-2 处理）

5. **事件循环（核心 poll loop）**：
   ```rust
   pub async fn run(
       &mut self,
       tun_reader: TunReader,
       tun_writer: TunWriter,
       tcp_event_tx: mpsc::Sender<TcpEvent>,
       fake_dns: Arc<Mutex<FakeDnsEngine>>,
   ) -> Result<(), TunError> {
       loop {
           // 1. 从 TUN 读包 → 喂给 smoltcp
           // 2. smoltcp poll() 驱动协议栈
           // 3. 检查新建 TCP 连接 → 发送事件
           // 4. 检查 UDP 数据 → DNS 处理或丢弃
           // 5. smoltcp 输出的包 → 写回 TUN
           // 6. 计算下次 poll 时间 → sleep 或 select
       }
   }
   ```

6. **TCP 字节流接口**（供 SOCKS5 模块使用）：
   ```rust
   /// 封装 smoltcp TCP socket 的异步读写
   pub struct SmolTcpStream {
       socket_handle: SocketHandle,
       stack: Arc<Mutex<UserStack>>,  // 需要与 poll loop 共享
   }

   impl AsyncRead for SmolTcpStream { ... }
   impl AsyncWrite for SmolTcpStream { ... }
   ```

   注意：这里的难点在于 smoltcp 是非异步的（同步 poll 模型），需要桥接到 tokio 的异步世界。推荐方案：
   - 用一个专用 tokio task 跑 smoltcp poll loop
   - 通过 `tokio::sync::Notify` 或 channel 通知有新数据可读/可写
   - `SmolTcpStream` 的 `poll_read`/`poll_write` 内部通过 channel 与 poll loop 交互

7. **性能要点**：
   - 避免每个包都 lock mutex，考虑 batch 处理
   - smoltcp poll 间隔不要用固定 sleep，用 `Interface::poll_delay()` 返回的建议等待时间
   - TUN 读取使用尽可能大的缓冲区（一次系统调用读多个包，如果 OS 支持）

### 验收标准

- [ ] 集成测试：创建 TUN → smoltcp 初始化 → 从另一个进程 `ping 198.18.0.1`，smoltcp 能收到 ICMP 包（即使不回复）
- [ ] 集成测试：从另一个进程 `curl --resolve test.local:80:198.18.x.x http://test.local/`，smoltcp 能完成 TCP 握手并收到 HTTP 请求字节流
- [ ] `SmolTcpStream` 实现 `tokio::io::AsyncRead + AsyncWrite`
- [ ] UDP DNS 包能被正确识别并交给 FakeDnsEngine

---

## Task 5：SOCKS5 客户端实现

### 目标
实现一个轻量级 SOCKS5 客户端，将 smoltcp 提取出的 TCP 字节流通过 SOCKS5 协议转发给 proxy-local。

### 技术要求

1. **socks5.rs** 实现方式二选一：
   - **方案 A**（推荐）：使用 `fast-socks5` 库的客户端 API
   - **方案 B**：手写 SOCKS5 客户端（协议简单，约 100 行），避免额外依赖

2. **连接流程**：
   ```rust
   pub async fn proxy_tcp_stream(
       domain: &str,
       port: u16,
       mut app_stream: SmolTcpStream,
       socks5_addr: SocketAddr,
   ) -> Result<(), Socks5Error> {
       // 1. TCP connect 到 socks5_addr (127.0.0.1:1080)
       let mut proxy_stream = TcpStream::connect(socks5_addr).await?;

       // 2. SOCKS5 认证握手 (NO AUTH: 0x05, 0x01, 0x00)
       socks5_auth(&mut proxy_stream).await?;

       // 3. SOCKS5 CONNECT 请求
       //    版本: 0x05
       //    命令: 0x01 (CONNECT)
       //    地址类型: 0x03 (域名)
       //    目标: domain:port
       socks5_connect_request(&mut proxy_stream, domain, port).await?;

       // 4. 等待 SOCKS5 CONNECT 成功响应
       socks5_read_response(&mut proxy_stream).await?;

       // 5. 双向转发（zero-copy bidirectional copy）
       tokio::io::copy_bidirectional(&mut app_stream, &mut proxy_stream).await?;

       Ok(())
   }
   ```

3. **注意事项**：
   - SOCKS5 CONNECT 时**必须发送域名**（而非 IP），这样 proxy-local 能做正确的远端 DNS 解析
   - 域名从 FakeDNS 反查得到（通过 `FakeDnsEngine::lookup_domain(dst_ip)`）
   - 如果 FakeIP 反查失败（理论上不应该），fallback 为直接发送 IP 地址（SOCKS5 ATYP=0x01）
   - 连接 127.0.0.1:1080 走 loopback 天然不经过 TUN，无回环风险

4. **连接池（可选优化，MVP 可不做）**：
   - MVP 阶段每个 TCP 流对应一条 SOCKS5 连接，连接结束后关闭
   - 不需要连接池（SOCKS5 CONNECT 是有状态的，一条连接对应一条隧道）

5. **超时与错误处理**：
   - SOCKS5 握手超时：5 秒
   - CONNECT 响应超时：配置的 `health_check_interval` 时间
   - proxy-local 不可达时返回 `Socks5Error::Unreachable`，上层可决定是否走直连降级

### 验收标准

- [ ] 单元测试：与一个 mock SOCKS5 server 完成握手 + CONNECT + 双向数据传输
- [ ] 集成测试：连接真实的 proxy-local (127.0.0.1:1080)，SOCKS5 CONNECT 到 httpbin.org:80，发送 HTTP GET 并收到 200 响应
- [ ] 错误场景：proxy-local 未启动时快速返回错误，不 hang

---

## Task 6：路由引擎（基础版）

### 目标
实现基于域名后缀和 IP CIDR 的路由决策引擎，决定每个连接走代理、直连还是拦截。

### 技术要求

1. **router.rs** 核心结构：
   ```rust
   #[derive(Debug, Clone, PartialEq)]
   pub enum RouteAction {
       Proxy,   // 走 SOCKS5 → proxy-local
       Direct,  // 直连（bypass，从物理网卡出去）
       Reject,  // 丢弃（黑洞）
   }

   pub struct Router {
       rules: Vec<Box<dyn Rule + Send + Sync>>,
       default_action: RouteAction,
   }

   pub struct ConnectionInfo {
       pub src_ip: IpAddr,
       pub dst_ip: IpAddr,
       pub dst_port: u16,
       pub domain: Option<String>,
       pub protocol: Protocol,
   }

   pub trait Rule: Send + Sync {
       fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction>;
       fn name(&self) -> &str;  // 用于日志和调试
   }
   ```

2. **MVP 阶段实现以下规则类型**：

   - **DomainSuffixRule**：匹配域名后缀（如 `cn` 匹配所有 `.cn` 域名）
   - **DomainKeywordRule**：域名包含关键词（如 `google` 匹配 `www.google.com`）
   - **DomainFullRule**：精确匹配完整域名
   - **IpCidrRule**：IP CIDR 匹配（如 `192.168.0.0/16`）
   - **PortRule**：端口匹配

3. **规则匹配逻辑**：
   - 从配置文件加载规则列表，按配置中的顺序匹配
   - 第一个匹配的规则决定动作（first-match-wins）
   - 所有规则都不匹配时使用 `default_action`

4. **从 TOML 配置构建 Router**：
   ```rust
   impl Router {
       pub fn from_config(routing_config: &RoutingConfig) -> Result<Self, RouterError>;
       pub fn route(&self, info: &ConnectionInfo) -> RouteAction;
   }
   ```

5. **性能**：
   - 域名规则使用 `HashMap` 做精确匹配 + 后缀匹配（逐级查找 `com` → `google.com` → `www.google.com`）
   - IP CIDR 使用 `ipnet::IpNet::contains()` 匹配
   - 对于日常使用的数百条规则，匹配延迟应 < 1μs

### 验收标准

- [ ] 单元测试：`*.google.com` 规则匹配 `www.google.com` → Proxy
- [ ] 单元测试：`192.168.0.0/16` 规则匹配 `192.168.1.100` → Direct
- [ ] 单元测试：无域名时 fallback 到 IP 匹配
- [ ] 单元测试：规则优先级正确（先匹配到的优先）
- [ ] 从 TOML 加载规划文档中的示例配置并正确工作

---

## Task 7：路由回环防护

### 目标
确保 tun-adapter 自身发出的流量（连接 proxy-local、直连流量）不会被路由回 TUN 设备形成死循环。

### 技术要求

1. **macOS 方案**（排除路由 + 绑定接口）：
   ```rust
   pub struct BypassManager {
       original_gateway: IpAddr,
       physical_interface: String,  // 如 "en0"
       excluded_routes: Vec<IpNet>,
   }

   impl BypassManager {
       /// 获取当前默认网关和物理网卡名
       pub async fn detect_network() -> Result<(IpAddr, String), TunError>;

       /// 为直连流量的 socket 绑定物理网卡
       /// macOS: 使用 IP_BOUND_IF setsockopt
       pub fn bind_to_physical_interface(socket: &TcpSocket) -> Result<(), TunError>;

       /// 添加排除路由（proxy-remote IP、内网段）
       pub async fn setup_exclusions(&self) -> Result<(), TunError>;
   }
   ```

2. **关键措施**：
   - `127.0.0.1` 天然走 loopback 不经过 TUN（无需额外处理）→ SOCKS5 连接安全
   - 直连流量的 socket 需要绑定物理网卡接口（macOS 使用 `IP_BOUND_IF` setsockopt）
   - proxy-remote 的真实 IP 需要添加排除路由指向原网关
   - 内网段 (`10/8`, `172.16/12`, `192.168/16`) 添加排除路由

3. **macOS `IP_BOUND_IF` 实现**：
   ```rust
   #[cfg(target_os = "macos")]
   fn bind_socket_to_interface(fd: RawFd, interface_name: &str) -> io::Result<()> {
       let ifindex = unsafe { libc::if_nametoindex(CString::new(interface_name)?.as_ptr()) };
       if ifindex == 0 {
           return Err(io::Error::last_os_error());
       }
       let ret = unsafe {
           libc::setsockopt(
               fd,
               libc::IPPROTO_IP,
               libc::IP_BOUND_IF,
               &ifindex as *const u32 as *const libc::c_void,
               std::mem::size_of::<u32>() as libc::socklen_t,
           )
       };
       if ret != 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
   }
   ```

4. **直连 TCP 连接封装**：
   ```rust
   /// 创建一个绕过 TUN 的 TCP 连接（绑定物理网卡出口）
   pub async fn connect_bypass(addr: SocketAddr, bypass_mgr: &BypassManager) -> Result<TcpStream, TunError>;
   ```

### 验收标准

- [ ] TUN 启动后，tun-adapter 自身连接 proxy-local (127.0.0.1:1080) 不会回环
- [ ] 直连流量通过 `connect_bypass` 发出后不经过 TUN（可通过抓包验证）
- [ ] proxy-remote IP 的排除路由正确设置
- [ ] 系统仍能访问局域网设备（如路由器 192.168.x.1）

---

## Task 8：主事件循环与模块串联

### 目标
将前面所有模块串联起来，实现完整的数据流：TUN 读包 → 协议栈 → FakeDNS/路由 → SOCKS5 代理或直连。

### 技术要求

1. **main.rs 启动流程**（替换 Task 1 中的 todo）：
   ```rust
   #[tokio::main]
   async fn main() -> anyhow::Result<()> {
       // 1. 解析参数、加载配置、初始化日志
       let config = Config::load(&args.config)?;
       init_tracing(&config.log);

       // 2. 创建 TUN 设备
       let tun_mgr = TunManager::new(&config.tun).await?;
       let (tun_reader, tun_writer) = tun_mgr.split();

       // 3. 初始化 FakeDNS
       let fake_dns = Arc::new(Mutex::new(FakeDnsEngine::new(config.fakeip.range, 65536)));

       // 4. 初始化路由引擎
       let router = Arc::new(Router::from_config(&config.routing)?);

       // 5. 初始化回环防护
       let bypass_mgr = Arc::new(BypassManager::detect_and_setup(&config).await?);

       // 6. 启动健康检查（后台任务）
       let health_handle = tokio::spawn(health_check_loop(config.proxy.socks5_addr, ...));

       // 7. 启动用户态协议栈事件循环
       let (tcp_event_tx, tcp_event_rx) = mpsc::channel(1024);
       let stack_handle = tokio::spawn(stack_loop(tun_reader, tun_writer, fake_dns.clone(), tcp_event_tx));

       // 8. 启动连接处理循环（消费 TCP 事件，为每个新连接 spawn SOCKS5 转发任务）
       let conn_handle = tokio::spawn(connection_dispatcher(
           tcp_event_rx, fake_dns, router, bypass_mgr, config.proxy.socks5_addr,
       ));

       // 9. 等待 Ctrl+C 或任一核心任务异常退出
       tokio::select! {
           _ = tokio::signal::ctrl_c() => { tracing::info!("Shutting down..."); }
           res = stack_handle => { tracing::error!("Stack loop exited: {:?}", res); }
           res = conn_handle => { tracing::error!("Connection dispatcher exited: {:?}", res); }
       }
       // Drop Guard 自动恢复路由
       Ok(())
   }
   ```

2. **connection_dispatcher** 逻辑：
   ```rust
   async fn connection_dispatcher(
       mut rx: mpsc::Receiver<TcpEvent>,
       fake_dns: Arc<Mutex<FakeDnsEngine>>,
       router: Arc<Router>,
       bypass_mgr: Arc<BypassManager>,
       socks5_addr: SocketAddr,
   ) {
       while let Some(event) = rx.recv().await {
           match event {
               TcpEvent::NewConnection { dst_ip, dst_port, stream } => {
                   // 1. FakeIP 反查域名
                   let domain = fake_dns.lock().lookup_domain(&dst_ip).map(|s| s.to_string());

                   // 2. 路由决策
                   let info = ConnectionInfo { dst_ip, dst_port, domain: domain.clone(), .. };
                   let action = router.route(&info);

                   // 3. 根据决策 spawn 转发任务
                   match action {
                       RouteAction::Proxy => {
                           let target = domain.unwrap_or(dst_ip.to_string());
                           tokio::spawn(proxy_tcp_stream(&target, dst_port, stream, socks5_addr));
                       }
                       RouteAction::Direct => {
                           let addr = SocketAddr::new(dst_ip, dst_port);
                           tokio::spawn(direct_tcp_stream(stream, addr, bypass_mgr.clone()));
                       }
                       RouteAction::Reject => {
                           // 直接 drop stream，TCP RST
                           drop(stream);
                       }
                   }
               }
           }
       }
   }
   ```

3. **直连转发**：
   ```rust
   async fn direct_tcp_stream(
       mut app_stream: SmolTcpStream,
       target: SocketAddr,
       bypass_mgr: Arc<BypassManager>,
   ) -> Result<(), TunError> {
       let mut remote = connect_bypass(target, &bypass_mgr).await?;
       tokio::io::copy_bidirectional(&mut app_stream, &mut remote).await?;
       Ok(())
   }
   ```

4. **优雅关闭**：
   - 收到 SIGINT/SIGTERM 后停止接受新连接
   - 等待活跃连接完成（最多 5 秒超时）
   - Drop Guard 恢复路由

### 验收标准

- [ ] 端到端测试：`sudo cargo run -- --config config/tun.toml` 启动后
- [ ] 在同一台机器上 `curl https://www.google.com` 能通过代理链路成功返回（前提：proxy-local + proxy-remote 已运行）
- [ ] `curl https://www.baidu.com` 走直连规则，不经过代理
- [ ] 日志中能看到完整的流转路径：DNS → FakeIP → TCP → Route → SOCKS5/Direct
- [ ] `Ctrl+C` 退出后网络恢复正常

---

## Task 9：健康检查与降级

### 目标
实现 proxy-local 的可用性健康检查，在代理不可用时自动降级为全部直连，恢复后自动切回代理模式。

### 技术要求

1. **健康检查循环**：
   ```rust
   pub struct HealthChecker {
       socks5_addr: SocketAddr,
       interval: Duration,
       failure_threshold: u32,   // 连续失败多少次触发降级
       state: Arc<AtomicBool>,   // true = healthy, false = degraded
   }

   impl HealthChecker {
       pub async fn run(&self) {
           let mut consecutive_failures = 0u32;
           loop {
               tokio::time::sleep(self.interval).await;
               match self.check_once().await {
                   Ok(()) => {
                       if consecutive_failures > 0 {
                           tracing::info!("proxy-local recovered");
                       }
                       consecutive_failures = 0;
                       self.state.store(true, Ordering::Relaxed);
                   }
                   Err(e) => {
                       consecutive_failures += 1;
                       tracing::warn!(
                           "Health check failed ({}/{}): {}",
                           consecutive_failures, self.failure_threshold, e
                       );
                       if consecutive_failures >= self.failure_threshold {
                           self.state.store(false, Ordering::Relaxed);
                           tracing::error!("Entering degraded mode: all traffic direct");
                       }
                   }
               }
           }
       }

       async fn check_once(&self) -> Result<(), TunError> {
           let timeout = Duration::from_secs(3);
           let mut stream = tokio::time::timeout(timeout, TcpStream::connect(self.socks5_addr)).await??;
           // 尝试 SOCKS5 认证握手
           socks5_auth_handshake(&mut stream).await?;
           Ok(())
       }
   }
   ```

2. **降级行为**：
   - `connection_dispatcher` 在做路由决策前先检查 `health_state`
   - 如果 degraded，所有 `RouteAction::Proxy` 自动降级为 `RouteAction::Direct`
   - 降级期间每次健康检查成功立即恢复

3. **配置项**：
   ```toml
   [proxy]
   socks5_addr = "127.0.0.1:1080"
   health_check_interval = 5        # 秒
   health_failure_threshold = 3     # 连续失败次数
   ```

### 验收标准

- [ ] proxy-local 正常运行时 health_state 为 healthy
- [ ] 停止 proxy-local 后约 15 秒（3 次 × 5 秒间隔）后进入 degraded 模式
- [ ] degraded 模式下 curl 请求走直连仍然可达（如百度）
- [ ] 重新启动 proxy-local 后下一次健康检查通过即恢复代理模式
- [ ] 日志清晰记录状态切换

---

## Task 10：端到端集成测试

### 目标
编写自动化集成测试，验证完整数据链路的正确性。

### 技术要求

1. **测试环境搭建**（在 `tests/` 目录下）：
   - 启动一个本地 mock SOCKS5 server（用 `fast-socks5` 的 server 端）
   - mock server 收到 CONNECT 后连接真实目标（或本地 HTTP server）
   - 启动 tun-adapter 指向 mock SOCKS5 server

2. **测试用例**：
   ```rust
   #[tokio::test]
   async fn test_dns_interception() {
       // 验证 DNS 请求被 FakeDNS 拦截并返回假 IP
   }

   #[tokio::test]
   async fn test_tcp_proxy_flow() {
       // 完整流程：DNS → FakeIP → TCP → SOCKS5 → 目标
   }

   #[tokio::test]
   async fn test_direct_bypass() {
       // 配置为直连的域名/IP 不经过 SOCKS5
   }

   #[tokio::test]
   async fn test_route_guard_recovery() {
       // 进程退出后路由表恢复
   }

   #[tokio::test]
   async fn test_health_check_degradation() {
       // SOCKS5 server 停止后自动降级
   }
   ```

3. **注意**：TUN 设备需要 root 权限，集成测试可能需要标记为 `#[ignore]` 在 CI 中跳过，或使用 `sudo cargo test`。

4. **Benchmark（可选）**：
   - 使用 `criterion` 做 smoltcp 吞吐量基准测试
   - 目标：单核 ≥ 1Gbps（大包场景）

### 验收标准

- [ ] `cargo test` 单元测试全部通过（不需要 root）
- [ ] `sudo cargo test -- --ignored` 集成测试通过（需要 root 创建 TUN）
- [ ] 代码覆盖率：核心模块 > 70%

---

## 执行顺序建议

```
Task 1 (脚手架)
   │
   ├──→ Task 3 (FakeDNS) ──────────┐
   │                                 │
   ├──→ Task 6 (路由引擎) ──────────┤
   │                                 │
   ├──→ Task 5 (SOCKS5 客户端) ─────┤
   │                                 │
   └──→ Task 2 (TUN 设备) ──────────┤
        │                            │
        └──→ Task 7 (回环防护) ─────┤
                                     │
                                     ▼
                              Task 4 (smoltcp 集成) ← 依赖 Task 2
                                     │
                                     ▼
                              Task 8 (串联所有模块)
                                     │
                                     ▼
                              Task 9 (健康检查)
                                     │
                                     ▼
                              Task 10 (集成测试)
```

Task 1 完成后，Task 2/3/5/6 可以并行开发（互相独立）。Task 4 依赖 Task 2（需要 TUN 设备）。Task 7 依赖 Task 2。Task 8 是最终串联。

---

## 补充说明

### 给 AI 编码助手的提示

当你执行某个 Task 时，请遵循以下原则：

1. **完整性**：每个文件写完整，不要用 `// ...` 省略。所有 `pub` 函数都要有文档注释。
2. **错误处理**：不要用 `unwrap()`（测试代码除外）。用 `?` 传播错误，在最外层处理。
3. **日志**：关键操作前后都要有 `tracing::debug!` 或 `tracing::info!`，错误必须 `tracing::error!`。
4. **测试**：每个模块底部包含 `#[cfg(test)] mod tests { ... }` 单元测试。
5. **安全**：`unsafe` 代码必须有 SAFETY 注释说明为什么是安全的。涉及系统调用的必须检查返回值。
6. **跨平台**：用 `#[cfg(target_os = "...")]` 做平台隔离，不支持的平台给出编译错误提示。
