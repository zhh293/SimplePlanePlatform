# SimplePlanePlatform

基于 Dubbo 微内核思想设计的高性能加密隧道代理框架，支持 SOCKS5/HTTP CONNECT 代理模式和 TUN 全局透明代理模式，并提供基于系统 VpnService 的 Android 客户端。全链路 SPI 可插拔，集群容错、负载均衡、加密算法、传输层实现均可独立扩展替换。

## 项目定位

将 Dubbo 经典的微内核 + 插件化架构应用到网络代理场景。支持三种使用形态：

- **代理模式（SOCKS5 / HTTP CONNECT）**：应用程序主动通过代理端口转发流量，适合浏览器/命令行等可配置代理的场景。
- **TUN 模式（全局透明代理）**：通过虚拟网卡劫持系统全部流量，配合 FakeDNS 实现应用程序无感知的全局代理，无需逐个配置。
- **Android 客户端**：基于系统 `VpnService` 建立 TUN，经 JNI 调用 Rust 数据面（`plane-core`）完成 FakeDNS、域名路由与 ChaCha20 加密隧道，与桌面端服务器（proxy-remote）协议、加密完全互通。

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
├── plane-core              Android 数据面：Rust JNI 库（libplane_core.so），用户态栈 + FakeDNS + 出站加密隧道
├── android-app             Android 客户端：Kotlin VpnService + JNI 桥接，gradle 构建 APK
├── scripts/build-rust.sh   cargo-ndk 交叉编译 plane-core 为各 ABI 的 .so 并写入 jniLibs
├── dashboard               Web 管理面板：可视化管理服务启停、配置编辑、日志查看
├── start-tun.sh            TUN 模式一键启动脚本 (macOS)
├── start-tun.ps1           TUN 模式一键启动脚本 (Windows PowerShell)
├── start-tun.bat           Windows 双击启动器（自动提权）
└── restore-dns.sh          TUN 异常退出后的 DNS 恢复脚本 (macOS)
```

## 快速开始

### 环境要求

| 组件 | 要求 | 用途 |
|------|------|------|
| JDK | 1.8+ | 编译运行 proxy-local / proxy-remote |
| Maven | 3.6+ | Java 项目构建 |
| Rust | stable（1.70+） | 编译 tun-adapter / plane-core |
| Node.js | 14+ | 运行 Web Dashboard（零外部依赖，无需 npm install） |
| macOS | 10.15+ | TUN 模式（需要 root 权限） |
| Windows | 10 1903+ | TUN 模式（需要管理员权限，WinTUN 驱动随 tun2 crate 内置） |
| 云服务器 | 公网 IP | 部署 proxy-remote |
| Android | 7.0+（API 24） | Android 客户端（构建需 JDK 17 + Android SDK/NDK + cargo-ndk） |

### 编译打包

```bash
git clone https://github.com/zhh293/SimplePlanePlatform.git
cd SimplePlanePlatform

# Java 部分（proxy-local + proxy-remote）
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

# 启动系统代理（在 proxy-local 目录下执行）
java -Dproxy.dns.nameservers=114.114.114.114,223.5.5.5 \
     -jar ./target/proxy-local-1.0.0-SNAPSHOT.jar
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
# 【必填】代理服务器真实 IP，必须与 proxy.yml 中 remoteServers[].host 完全一致
proxy_remote_ips = ["YOUR_PROXY_REMOTE_IP"]
# 不进入 TUN 的内网网段
extra_cidrs = ["10.0.0.0/8", "11.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]

[intranet_dns]
# 内网 DNS 服务器地址
servers = ["11.11.11.11", "11.11.11.12"]
# 需要走内网 DNS 的域名后缀
domains = ["sankuai.com", "meituan.com", "sankuai.info", "neixin.cn", "dianping.com", "meituan.net"]
```

> ⚠️ **重要：`proxy_remote_ips` 必须填写真实的 proxy-remote 服务器 IP，且与 `proxy.yml` 里的 `host` 保持一致。**
> TUN 模式会用 `0.0.0.0/1` + `128.0.0.0/1` 两条路由劫持整个 IPv4 网段。如果不把 proxy-remote 的 IP 排除（bypass）出去，proxy-local 连接 proxy-remote 的流量会被 utun9 重新劫持回 tun-adapter，形成**路由环路**，表现为"TUN 启动成功但完全无法上网"。这是该模式最常见的失效原因。

### 一键启动

**macOS：**

> 启动 TUN 模式前请先关闭系统代理（代理模式与 TUN 模式不要同时开启），再运行脚本。

```bash
# 0. 进入项目目录
cd /Users/zhanghonghao/Desktop/SimplePlanePlatform

# 1.（强烈建议）先记录当前原始 DNS，作为人工对照基线
networksetup -getdnsservers Wi-Fi

# 2. 一键启动 TUN 模式（脚本已封装：启动 proxy-local → 启动 tun-adapter → 就绪检测）
sudo -v          # 先缓存 sudo 凭证，避免后台进程卡在密码输入
./start-tun.sh

# 3. 停止：在运行 start-tun.sh 的那个终端按 Ctrl+C
#    脚本会自动关掉两个进程并恢复 DNS/路由
```

脚本自动完成以下步骤：

1. 检查上次是否异常退出，如是则先恢复 DNS
2. 编译 proxy-local（如有需要）并启动，等待 1080 端口就绪
3. 编译 tun-adapter（如有需要）并以 sudo 启动
4. 创建 utun9 虚拟网卡、配置系统路由、设置 DNS 分流
5. 输出运行状态信息

启动成功后所有流量自动分流，无需手动配置。按 `Ctrl+C` 停止，脚本会自动恢复所有系统设置。

**Windows：**

```powershell
# 方式一：双击 start-tun.bat（会自动请求管理员权限）

# 方式二：以管理员身份打开 PowerShell
.\start-tun.ps1
```

Windows 脚本自动完成：编译 tun-adapter → 编译 proxy-local → 启动 Dashboard。TUN 使用 WinTUN 驱动（随 tun2 crate 内置，无需额外安装），通过 `route add` 设置路由、NRPT (Name Resolution Policy Table) 实现 DNS 分流。

### 手动启动（高级）

如果需要分步调试：

**macOS：**

```bash
# 终端 1：启动 proxy-local（带 DNS bypass 参数）
java -Dproxy.dns.nameservers=114.114.114.114,223.5.5.5 \
     -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar

# 终端 2：启动 tun-adapter（需要 root 权限）
cd tun-adapter
sudo ./target/release/tun-adapter -c config/tun.toml
```

**Windows（管理员 PowerShell）：**

```powershell
# 终端 1：启动 proxy-local
java -Dproxy.dns.nameservers="114.114.114.114,223.5.5.5" `
     -jar proxy-local\target\proxy-local-1.0.0-SNAPSHOT.jar

# 终端 2：启动 tun-adapter（管理员权限）
cd tun-adapter
.\target\release\tun-adapter.exe -c config\tun.toml
```

### 验证 TUN 模式

**macOS：**

```bash
# 检查 TUN 设备是否创建
ifconfig utun9

# 验证内网域名解析走真实 DNS（应返回 10.x.x.x 内网 IP）
nslookup your-intranet-domain.com

# 验证外网走代理
curl --max-time 10 -I https://www.google.com
```

**Windows（管理员 PowerShell）：**

```powershell
# 检查 TUN 设备是否创建
Get-NetAdapter | Where-Object { $_.Name -like "*SimplePlane*" }

# 验证路由
route print | Select-String "198.18"

# 验证外网走代理
curl.exe --max-time 10 -I https://www.google.com
```

### 异常恢复

**macOS：** 如果 tun-adapter 异常退出（kill -9、终端意外关闭等）导致网络异常：

```bash
sudo ./restore-dns.sh
```

该脚本会读取 `/tmp/tun-adapter-dns-backup.conf` 备份文件，自动恢复 DNS 设置、删除 `/etc/resolver/` 配置、刷新 DNS 缓存。原始 DNS 为「自动获取」时会还原为自动获取（macOS 使用 `networksetup -setdnsservers <服务> Empty`，注意 `Empty` 是 macOS 的合法值，`DHCP` 不是）。

如果备份文件已丢失、脚本无法恢复，可手动把 DNS 重置为自动获取：

```bash
sudo networksetup -setdnsservers Wi-Fi Empty   # 将 Wi-Fi 换成你的网络服务名
sudo rm -f /tmp/tun-adapter-dns-backup.conf
sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder
```

**Windows：** tun-adapter 退出时会自动清理路由和 NRPT 规则。如仍有网络异常，可在管理员 PowerShell 中手动恢复：

```powershell
# 清除 NRPT 规则
Get-DnsClientNrptRule | Where-Object { $_.Comment -like "*tun-adapter*" } | Remove-DnsClientNrptRule -Force

# 恢复 DNS（从备份文件恢复，或设为自动获取）
Set-DnsClientServerAddress -InterfaceAlias "Wi-Fi" -ResetServerAddresses

# 清理残留路由
route delete 198.18.0.0
```

## 使用方式三：Android 客户端

> ⚠️ **暂未开放**：Android 客户端仍在内部调试中，当前阶段请勿使用，请使用上面的代理模式或 TUN 模式。以下内容仅作开发记录。

Android 客户端把整套加密隧道带到手机端：基于系统 `VpnService` 建立 TUN，应用全部流量经虚拟网卡进入用户态栈，由 Rust 数据面（`plane-core`，编译为 `libplane_core.so`）完成 FakeDNS 解析、域名路由判断与 ChaCha20 加密，再通过 HTTP/2 隧道转发到 proxy-remote。与桌面端共用同一套 ProxyMessage 协议与 ChaCha20-Poly1305 加密格式，两端完全互通。

### 架构

```
┌──────────────┐  全部流量   ┌──────────────────────────────────────────────┐
│  Android App  │ ─────────→ │     PlaneVpnService (Kotlin, VpnService)     │
│  (任意应用)   │   TUN fd   │  establish() → detachFd() 移交 native        │
└──────────────┘            └───────────────────┬──────────────────────────┘
                                                 │ JNI (NativeBridge)
                                                 ▼
                            ┌──────────────────────────────────────────────┐
                            │       plane-core (Rust, libplane_core.so)    │
                            │  用户态栈 → FakeDNS → 域名路由 → ChaCha20     │
                            │  回调 protect(fd) 把出站 socket 排除出 TUN    │
                            └───────────────────┬──────────────────────────┘
                                                 │ HTTP/2 加密隧道
                                                 ▼
                            ┌──────────────────────────────────────────────┐
                            │            proxy-remote (远程服务器)          │
                            └──────────────────────────────────────────────┘
```

Kotlin 侧 `PlaneVpnService` 负责申请 VPN 授权、配置 TUN（地址/路由/DNS/MTU）并把 fd 移交给 native；`NativeBridge` 是 JNI 桥接，向上调用 `nativeStart` / `nativeStop`，向下接收 Rust 的 `protect`（防回环，把出站 socket 排除出 TUN）与 `onStatus`（状态上报）回调。

### 构建环境

| 组件 | 要求 |
|------|------|
| JDK | 17（Gradle / AGP 要求，注意与服务端的 JDK 8 区分） |
| Android SDK | API 34（compileSdk） |
| Android NDK | r26+（cargo-ndk 交叉编译需要） |
| Rust target | `aarch64-linux-android` / `armv7-linux-androideabi` / `x86_64-linux-android` |
| cargo-ndk | `cargo install cargo-ndk` |

首次准备工具链：

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
# 并通过 ANDROID_NDK_HOME 或 sdkmanager 安装 NDK r26+
```

### 本地构建 APK

```bash
# 进入 Android 工程，Gradle 的 preBuild 会自动调用 scripts/build-rust.sh
# 用 cargo-ndk 把 plane-core 交叉编译进 jniLibs，再打包进 APK。
cd android-app

# Debug APK
./gradlew assembleDebug

# Release APK（Rust 以 release profile 编译）
./gradlew assembleRelease -PrustRelease
```

产物位置：

- `android-app/app/build/outputs/apk/debug/app-debug.apk`
- `android-app/app/build/outputs/apk/release/app-release-unsigned.apk`

若已手动跑过 `scripts/build-rust.sh`，可加 `-PskipRustBuild=true` 跳过 Gradle 内的 Rust 构建步骤。也可单独交叉编译数据面：

```bash
scripts/build-rust.sh release   # 产出各 ABI 的 libplane_core.so 到 jniLibs
```

### 签名与 Release 打包

默认 `assembleRelease` 在没有签名材料时产出 **unsigned** APK（无法直接安装）。配置签名后会自动产出已签名、可安装的 `app-release.apk`。

**1. 生成 keystore（一次性）：**

```bash
keytool -genkeypair -v -keystore release.jks \
  -alias simpleplane -keyalg RSA -keysize 2048 -validity 10000
# 按提示设置 keystore 密码、key 密码与证书信息，妥善保管 release.jks（勿入库）
```

**2. 本地签名打包**：在仓库根目录创建 `keystore.properties`（已被 `.gitignore` 排除，不会入库）：

```properties
storeFile=release.jks
storePassword=你的keystore密码
keyAlias=simpleplane
keyPassword=你的key密码
```

然后正常执行 `cd android-app && ./gradlew assembleRelease -PrustRelease`，产物为 `app-release.apk`。也可用环境变量 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` 替代该文件（环境变量优先）。

**3. CI 签名打包**：在 GitHub 仓库 Settings → Secrets and variables → Actions 配置以下 4 个 secrets，`android-build` job 会自动还原 keystore 并产出已签名的 release APK：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i release.jks`（macOS）或 `base64 -w0 release.jks`（Linux）的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key 别名（如 `simpleplane`） |
| `ANDROID_KEY_PASSWORD` | key 密码 |

未配置这些 secrets 时（如 Fork 或未设置的分支），CI 仅产出 Debug APK，签名步骤自动跳过，不会失败。

### CI 自动打包

仓库已配置 `.github/workflows/android-core.yml`，当 `plane-core/**`、`android-app/**`、`scripts/build-rust.sh` 等路径变更并推送时自动触发：

- `rust-test`：`cargo fmt --check` + `cargo clippy -D warnings` + `cargo test`（强制门禁）；覆盖率为信息性输出。
- `android-build`：cargo-ndk 交叉编译三 ABI 的 `.so` → `assembleDebug` → 把 Debug APK 上传为 artifact（`simpleplane-debug-apk`，保留 14 天）。若仓库配置了签名 secrets（见上节），还会额外 `assembleRelease` 并上传已签名的 release APK（`simpleplane-release-apk`）。

推送后在 GitHub Actions 对应运行的 `android-build` job 的 Artifacts 中即可下载 APK。

## Web 管理面板（Dashboard）

> ⚠️ **暂未开放**：Web 管理面板仍在内部调试中，当前阶段请勿使用，请直接通过上面的命令行方式启动代理模式或 TUN 模式。以下内容仅作开发记录。

Dashboard 提供可视化界面来管理整个代理平台，包括一键启停服务、实时编辑配置、查看运行日志等。**零外部依赖，无需 npm install**。

### 环境要求

- Node.js 14+（推荐 18+）
- 确保 Java 和 Cargo 已安装（Dashboard 会调用 `mvn` 和 `cargo` 进行编译）
- macOS 或 Windows 10+（TUN 模式跨平台支持）

### 启动 Dashboard

```bash
cd dashboard
node server.js
```

看到 `✓ SimplePlane Dashboard running at http://localhost:3000` 即启动成功。在浏览器中打开 [http://localhost:3000](http://localhost:3000) 访问面板。

**macOS 注意**：Dashboard 必须在真实终端中启动（如 Terminal.app、iTerm2），不能在受限的沙盒环境中运行，否则 TUN 模式的 `sudo` 调用会失败。

**Windows 注意**：Dashboard 必须以管理员身份启动（右键 CMD/PowerShell → 以管理员身份运行），否则 TUN 模式无法创建虚拟网卡和修改路由表。

可通过环境变量修改监听端口：

```bash
DASHBOARD_PORT=8080 node server.js
```

### TUN 模式权限配置（首次使用必须）

TUN 模式需要 root 权限。为了让 Dashboard 能够免密启动/停止 tun-adapter，需要**先执行一次权限配置脚本**：

```bash
cd dashboard
chmod +x setup-tun-permissions.sh
./setup-tun-permissions.sh
```

脚本会要求输入一次 Mac 管理员密码，然后在 `/etc/sudoers.d/simpleplane-tun` 写入免密规则。配置完成后 Dashboard 就能直接启停 TUN 了。

**注意**：每次重新编译 tun-adapter（`cargo build --release`）后，如果二进制路径未变则无需再次配置。如果迁移了项目目录需要重新运行此脚本。

### Dashboard 功能概览

#### 控制面板

主页面展示所有服务的运行状态，提供以下操作：

| 操作 | 说明 |
|------|------|
| 启动/停止/重启 proxy-local | 管理本地 SOCKS5/HTTP 代理服务 |
| 启动/停止/重启 tun-adapter | 管理 TUN 全局透明代理 |
| 编译 | 一键编译 proxy-local（mvn）或 tun-adapter（cargo） |
| 一键代理模式 | 启动 proxy-local + 开启 macOS 系统代理 |
| 一键 TUN 模式 | 启动 proxy-local + 启动 tun-adapter |
| 全部停止 | 停止所有服务 + 关闭系统代理 |
| 系统代理开关 | 切换 macOS Wi-Fi 的 SOCKS/HTTP 代理设置 |

#### 代理配置（proxy.yml 可视化编辑）

无需手动编辑 YAML 文件，所有 proxy-local 参数均可在界面中修改：

| 配置项 | 界面位置 | 说明 |
|--------|----------|------|
| 监听端口 | 代理配置 → 监听端口 | SOCKS5/HTTP CONNECT 共用端口，默认 1080 |
| 集群策略 | 代理配置 → 集群策略 | failover / failfast / forking / failback |
| 负载均衡 | 代理配置 → 负载均衡 | roundrobin / random / leastactive / consistenthash |
| 超时 | 代理配置 → 超时 (ms) | 请求超时毫秒数，默认 30000 |
| 每节点连接数 | 代理配置 → 每节点连接数 | HTTP/2 多路复用连接数，1-2 即可 |
| HTTP CONNECT | 代理配置 → HTTP CONNECT 开关 | 是否同时支持 HTTP 代理协议 |
| 远程服务器 | 代理配置 → 远程服务器 | 可添加/删除/编辑多个服务器节点 |

远程服务器每个节点可配置：Host（地址）、Port（端口）、Cipher（加密算法）、Key（密钥）、SSL（是否启用 TLS）。

修改后点击左下角「保存配置」按钮，或按 `Ctrl+S` / `Cmd+S` 快捷保存。

#### TUN 模式配置（tun.toml 编辑器）

提供 `tun-adapter/config/tun.toml` 的文本编辑器，支持直接修改 TOML 配置。可点击「保存并重启」一键应用变更。

#### 路由规则

可视化编辑域名分流规则：

| 配置项 | 说明 |
|--------|------|
| 默认路由 | `direct`（默认直连）或 `proxy`（默认走代理） |
| 代理列表 | 走远程代理的域名，每行一条，支持 `*` 通配符 |
| 直连列表 | 强制直连的域名（优先级最高），每行一条 |

路由优先级：直连列表 > 代理列表 > 默认路由。

#### 运行日志

实时查看 proxy-local 和 tun-adapter 的运行日志，支持：

- 切换查看不同服务的日志
- 自动滚动到底部
- 一键清空显示

### Dashboard 配置文件路径

Dashboard 直接读写以下文件：

| 文件 | 路径 | 说明 |
|------|------|------|
| proxy.yml | `proxy-local/src/main/resources/proxy.yml` | proxy-local 主配置 |
| remote.yml | `proxy-remote/src/main/resources/remote.yml` | proxy-remote 服务端配置 |
| tun.toml | `tun-adapter/config/tun.toml` | TUN 适配器配置 |

在 Dashboard 中修改配置后会直接写入对应文件，修改完需要重启对应服务才能生效。

### Dashboard 实时通信

Dashboard 通过 SSE（Server-Sent Events）实现状态实时推送：

- 服务启停状态变化会自动刷新面板
- 新产生的日志会实时推送到日志页面
- 配置文件变更会通知前端刷新

### 配置预设

可以将当前配置保存为预设方便切换（如「公司网络」「家庭网络」等不同场景）。预设保存在 `dashboard/presets/` 目录下，格式为 YAML。

## TUN 模式配置参考（tun.toml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| tun.name | utun9 (macOS) / SimplePlane (Windows) | TUN 设备名称 |
| tun.address | 198.18.0.1 | TUN 设备 IP 地址 |
| tun.netmask | 255.254.0.0 | TUN 设备子网掩码 |
| tun.mtu | 1500 | MTU 大小 |
| tun.enabled | true | 是否启用 TUN 设备 |
| fakeip.range | 198.18.0.0/15 | FakeDNS 虚拟 IP 分配范围 |
| fakeip.capacity | 65536 | FakeDNS 映射表容量 |
| proxy.socks5_addr | 127.0.0.1:1080 | 上游 SOCKS5 代理地址 |
| proxy.health_check_interval | 5 | 健康检查间隔（秒） |
| proxy.health_failure_threshold | 3 | 连续失败几次判定代理不可用 |
| routing.default_action | proxy | 默认路由动作（proxy/direct） |
| routing.rules[] | — | 域名/IP 路由规则（见下方） |
| bypass.proxy_remote_ips | — | 代理服务器 IP（bypass 路由，必须配置） |
| bypass.extra_cidrs | — | 额外 bypass 网段 |
| bypass.dns_bypass_ips | — | DNS bypass IP（如 114.114.114.114） |
| intranet_dns.servers | — | 内网 DNS 服务器 |
| intranet_dns.domains | — | 内网域名后缀列表 |
| log.level | info | 日志级别（支持模块级别如 `info,tun_adapter::socks5=debug`） |
| log.format | pretty | 日志格式 |

路由规则类型支持：`domain_suffix`（域名后缀匹配）、`domain_keyword`（域名关键词匹配）、`ip_cidr`（IP 段匹配）。

示例路由规则：

```toml
[[routing.rules]]
type = "domain_suffix"
value = "google.com"
action = "proxy"

[[routing.rules]]
type = "domain_keyword"
value = "baidu"
action = "direct"

[[routing.rules]]
type = "ip_cidr"
value = "10.0.0.0/8"
action = "direct"
```

## 配置参考

### proxy-local（proxy.yml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| localPort | 1080 | 本机代理监听端口 |
| remoteServers[].host | — | 远程服务端地址 |
| remoteServers[].port | 9090 | 远程服务端端口 |
| remoteServers[].cipher | none | 加密算法，需与服务端一致 |
| remoteServers[].cipherKey | — | 加密密钥，需与服务端一致 |
| remoteServers[].ssl | false | 是否启用 TLS |
| cluster | failover | 集群容错策略 |
| loadBalance | roundrobin | 负载均衡策略 |
| timeoutMs | 30000 | 请求超时（ms） |
| connectionsPerNode | 1 | 每节点 HTTP/2 连接数 |
| httpProxyEnabled | true | 是否支持 HTTP CONNECT |
| route.defaultRoute | direct | 默认路由（proxy/direct） |
| route.proxyList | [] | 走代理的域名列表（支持通配符） |
| route.directList | [] | 强制直连的域名列表（支持通配符） |
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

## Docker 部署

项目提供 docker-compose 一键编排，两端在同一 compose 网络内通信：

```bash
docker compose up -d --build    # 构建并启动
docker compose logs -f          # 查看日志
docker compose down             # 停止
```

容器模式下 proxy-local 的 1080 端口映射到宿主机，通过服务名 `proxy-remote` 连接服务端。加密配置见 `docker/proxy.yml` 和 `docker/remote.yml`。

## 开启加密

客户端和服务端设置相同的 cipher 和 cipherKey 即可：

```yaml
# proxy.yml（客户端）
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    cipher: "aes-gcm"
    cipherKey: "your-secret-key"

# remote.yml（服务端）
cipher: aes-gcm
cipherKey: your-secret-key
```

支持的算法：`none`（无加密）、`aes-gcm`（推荐 x86，Intel AES-NI 硬件加速）、`chacha20`（推荐 ARM）、`aes-ctr-hmac`（经典组合）。

> **传输层分帧说明**：加密数据在 HTTP/2 隧道中传输时，DATA 帧边界不保证与发送侧一一对应（受 maxFrameSize、流控、合帧影响），大流量下单个密文块可能被跨帧切分。为此收发两端统一在每个密文块前写入 4 字节大端长度前缀，接收侧据此做密文层字节级累积、集齐整块后再解密，避免 AEAD 认证标签校验失败导致的连接中断。Java（`CipherEncodeHandler`/`CipherDecodeHandler`）与 Rust（`plane-core`）两端实现完全对称，`chacha20` 算法的密文格式跨语言一致（由 `docs/design/crypto-vectors.json` 测试向量锁定）。

## 完整使用流程（从零开始）

以下是一个完整的从零开始的部署示例：

### 1. 部署远程服务端

```bash
# 在云服务器上
scp -i your-key.pem proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar user@your-server:~/
ssh -i your-key.pem user@your-server
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
```

### 2. 配置本地客户端

编辑 `proxy-local/src/main/resources/proxy.yml`，填入远程服务器 IP，设置加密密钥。

### 3. 选择使用模式

**代理模式**（简单，适合浏览器）：
```bash
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar
# 然后设置系统代理或浏览器代理为 127.0.0.1:1080
```

**TUN 模式**（全局，推荐）：
```bash
# 方式一：Dashboard 面板操作（推荐）
cd dashboard && node server.js
# 打开 http://localhost:3000 → 点击「一键 TUN 模式」

# 方式二：命令行一键启动
./start-tun.sh
```

### 4. 验证

```bash
curl --max-time 10 -I https://www.google.com
```

## 常见问题

**启动报 `Address already in use`** — 端口被占用，`kill $(lsof -ti :1080)` 后重试。

**连接远端失败 `Connection refused`** — 确认远程端已启动、nginx 正常运行、安全组已放行端口、proxy.yml 中 IP 正确。

**TUN 模式「启动成功但完全无法上网」** — 最常见原因是 `tun.toml` 中 `bypass.proxy_remote_ips` 没填或填错（如保留了占位符 `your-remote-server-ip`）。它必须等于 `proxy.yml` 里的远端 `host`。否则 proxy-local 连接 proxy-remote 的流量会被 utun9 重新劫持回来形成**路由环路**，导致全网不通。修正后重启即可。

**TUN 模式下内网无法访问** — 确认 `intranet_dns.domains` 包含了所有内网域名后缀，且 `intranet_dns.servers` 填写了正确的内网 DNS 地址。同时确认 `bypass.extra_cidrs` 覆盖了内网 IP 段。

**TUN 异常退出后 DNS 坏了 / 恢复时报 `DHCP is not a valid IP address`** — macOS: 运行 `sudo ./restore-dns.sh` 恢复，或手动执行 `sudo networksetup -setdnsservers Wi-Fi Empty && dscacheutil -flushcache`。注意把 DNS 重置为「自动获取」的合法值是 `Empty` 而非 `DHCP`（旧版本曾误把 `DHCP` 写入备份文件，新版已修复并向后兼容）。Windows: 在管理员 PowerShell 中运行 `Set-DnsClientServerAddress -InterfaceAlias "Wi-Fi" -ResetServerAddresses`。

**Dashboard 中 TUN 启动报 EPERM** — macOS: Dashboard 必须从真实终端启动（不能从 IDE 内置终端或沙盒环境启动），同时确认已执行过 `setup-tun-permissions.sh`。Windows: 确认 Dashboard 以管理员身份运行。

**Dashboard 中 TUN 卡在「启动中」** — 检查 Dashboard 日志中是否有 `sudo: a password is required`，如有则说明 sudoers 配置未生效，重新运行 `setup-tun-permissions.sh`。

**想一次性排查 TUN 启动链路** — 在系统终端运行 `cd dashboard && ./diagnose-tun.sh`，它会逐项检查二进制、配置、免密 sudo、并实测能否以 root 创建 utun 设备，最后给出明确结论。

**访问超时无报错** — 检查域名是否在 proxyList 中（代理模式），或 routing.rules 中是否配置了对应的 direct 规则（TUN 模式）。

**cargo build 报错** — 确保已安装 Rust 工具链：`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 1.8+ | 代理客户端/服务端 |
| Netty | 4.1.108 | NIO 网络框架、HTTP/2 |
| Rust | stable | TUN 适配器 / Android 数据面（plane-core） |
| Kotlin | 1.9 | Android 客户端 |
| Android Gradle Plugin | 8.x (compileSdk 34, minSdk 24) | Android 工程构建 |
| cargo-ndk | — | 把 plane-core 交叉编译为各 ABI 的 .so |
| smoltcp | 0.11 | 用户态 TCP/IP 协议栈 |
| tokio | 1.x | Rust 异步运行时 |
| tun2 (crate) | 4.x | 跨平台 TUN 设备操作 (macOS/Windows/Linux) |
| BouncyCastle | 1.70 | ChaCha20-Poly1305 |
| SnakeYAML | 2.2 | YAML 配置解析 |
| SLF4J + Logback | 1.7.36 / 1.2.11 | 日志 |
| Node.js | 14+ | Web 管理面板 |
| Nginx | 1.24+ | TCP 四层反向代理 |
| Docker | — | 容器化部署 |

## 扩展指南

得益于 SPI 架构，扩展任何层只需两步：实现对应接口 → 在 `META-INF/proxy/{接口全限定名}` 注册。无需修改框架已有代码，符合开闭原则。

TUN 适配器的路由规则同样支持扩展，在 `tun.toml` 的 `[[routing.rules]]` 中添加新规则即可生效。

## License

MIT
