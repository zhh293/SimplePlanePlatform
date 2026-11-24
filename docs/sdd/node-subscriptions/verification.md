# 节点订阅验证记录

2026-11-23，Windows 桌面和远端隔离环境验证。正式公网客户端的发布验收尚有缺口。

| 场景 | 必须收集的证据 | 结果 |
|---|---|---|
| 公网 IP 与 OIDC | 浏览器正常验签、登录、callback | 专用 Firefox 信任库、TLS 1.3、准确 IP SAN、SDK 回调和授权管理页通过。公开受信证书和续期延期。 |
| 扫码和同机授权 | 设备码和确认页、允许/拒绝、重复兑换 | 从真实桌面 Canvas 解码二维码，核对短码并确认。拒绝、一次兑换及过期已测；物理手机摄像头与移动浏览器延期。 |
| 权益 | 账号隔离、节点组、设备名额和解绑 | 事务测试覆盖另一账号、并发争用和设备解绑；远端 MySQL 验证开通、按组签发和解绑后拒签。 |
| 节点同步 | 真实客户端、重复导入和退出 | Windows 原生界面、系统凭据、后台签名、重复同步和退出保留手工节点通过；Android 仅验共享核心。 |
| 转发 | TCP/UDP 往返、证书拒绝、活动连接关闭 | 隔离网络真实 Java 客户端与节点往返通过；公网 UDP 443 两个来源均未到达灰度主机网卡。 |

### T1 本机真实握手

Windows，2026-11-23。Maven 离线构建 proxy-remote 完整依赖；Http3LoopbackIntegrationTest 8 项通过，0 失败、0 跳过。Windows 沙箱内原生 quiche 在创建配置时发生访问冲突，在正常本机运行环境复跑同一套用例通过。

脚本 `python scripts/test-subscription-mtls.py --java <Java 可执行文件> --jar <proxy-remote shaded jar>` 启动临时回环 JVM 服务，使用 aioquic 1.8.0 和 cryptography 50.0.2 作为独立客户端。在完整 JBR 21.0.6 下，真实 POST /proxy 对合法设备返回 200，拒绝无证书、其他节点 CA、过期证书，8 秒临时证书到期后现存 QUIC 连接在两秒容差内关闭，另一有效设备仍可访问。此检查仅覆盖接入层，真实业务流、客户端同步和公网灰度另行验收。

首次使用已安装桌面裁剪 JRE 启动服务时，解析 EC 私钥因缺少 EC KeyFactory 失败。按修正后的 Windows 打包步骤使用现有 Corretto 17.0.14、jdeps 和 jlink 生成了精简运行时。实际模块清单含 `jdk.crypto.ec@17.0.14`；用该运行时重跑真实握手，合法、无证书、其他 CA、过期和活动连接到期断开全部符合预期。签名安装包仍待验。

### T1 远端接入专项

2026-11-23 在 212.64.14.155 校验上传的代理 jar SHA-256 为 `a1781ae3412ee6376fa5ab148788110e3e295c1991f37f8802ec2e8bba65e926`，通过脚本的 `--java-image eclipse-temurin:17-jre-jammy` 在 256 MiB 限额、1 CPU 的独立容器执行。Linux 回环实测接受合法设备，拒绝无证书、其他节点 CA 和过期证书，8 秒证书在时限内关闭活动连接，随后合法设备仍可连接。容器和临时上传目录清理。此阶段没有对公网开放监听，浏览器完整灰度待执行。

### T2–T6 浏览器、设备和数据面

2026-11-23，北京时间 21 时。灰度主机 212.64.14.155，节点 ID 为 `canary-a`。公网浏览器使用独立 Firefox 证书库，OIDC 使用部署过的 SSO 镜像、SDK、隔离的 MySQL 库和 Redis 实例。管理服务 jar 的 SHA-256 为 `71d987916bc0339a1798466dbac52cb7cdbb4a6e58d6cabb8744786600d61c91`，此轮转发节点为 `3206ab816feef8bc1e22a6558d5381001f162c4c04f3b730174f88f660ce8efa`，Java 客户端为 `ef1cb1ebeb7f24fa24ae5a159fabaceb891a4cfadb9596b67b5ed41a6cd0370f`。

| 检查 | 实测结果 |
|---|---|
| 身份、权限与确认 | 管理员通过 Firefox 表单和真实 OIDC 回调开通权益。普通账号看不到其他账号的设备，管理员页返回 403，跨账号解绑返回 404。无权益时确认设备返回 403，设备仍处于待确认状态。 |
| 兑换和发证 | 拒绝请求无法获权。确认后兑换一次，重复兑换无法再次领取设备 ID。清单仅含测试组节点，设备证书公钥与本机生成的短期公钥一致。重放签发请求被拒绝。 |
| Windows 桌面 | Tauri 调试客户端使用独立配置和 Windows 系统凭据库。从渲染后的 Canvas 解码二维码，Firefox 核对短码并确认。重复同步仍为一个订阅节点。管理员设置当天过期后客户端续签返回 403，已有完整配置逐字段一致；恢复权益后再次成功。退出保留原手工节点及其选择。最终桌面调试构建 SHA-256 为 `986e26884ec2263d25d0b6064f48906d95448b9da9450fbc80bb514de0433f70`。 |
| 业务与撤销 | 真实 Java 客户端使用设备凭证，在灰度命名空间内完成 QUIC 握手。SOCKS5 TCP 和桌面 TUN 分帧 UDP 各发送独立的 32 字节随机数，返回内容和标记逐字节一致。TCP 日志核对为代理路由。另起一条持续心跳的 QUIC 连接，浏览器解绑后续签返回 403，已有连接在五分钟证书到期后 0.09 秒关闭。 |
| 公网 UDP | 从 Windows 测试机、现有转发机分别发送 TCP 443 对照请求及四个 UDP 443 数据报。两次均在灰度机 eth0 观察到 TCP，均计到零个 UDP。Windows 公网 QUIC 超时。阻断位置尚未定位到具体云规则或网络设备。 |
| 自动化回归 | 订阅管理服务 3 项事务集成测试通过，Windows 桌面 17 项通过，Java HTTP/3 原入口 8 项通过。Android 共用 Rust 核心 118 项通过、2 项已有外部探测忽略。新增用例调用生产连接分发与持钥回调，检查缺失和畸形凭据拒绝、有效凭据建立 TCP/UDP 流。 |
| 运行状态 | 认证、管理、转发、Redis 四个灰度容器均为 0 次重启，OOM 标志均为 false。原 891 MiB 转发机仅做轻量探测，正式服务为 active/running，累计重启数 2，MemoryHigh 384 MiB、MemoryMax 480 MiB。 |
| 清理复核 | 远端灰度标签下容器数、TCP/UDP 443 监听数、两套测试库及其 SQL 账号数均为 0。灰度目录已删除。既有 MySQL 仍运行，灰度前已退出的原认证容器仍为 exited。管理机清理后可用内存 1212 MiB。本机临时浏览器、私钥、桌面测试配置及专用系统凭据已清理。 |

设备入口脚本为 `scripts/test-subscription-browser.py`。基础模式验证登录、确认、清单和解绑，`--desktop-port` 额外验证真实桌面 UI，`--remote-key --observe-expiry` 可验证灰度 Java 客户端转发及完整撤销窗口。测试依赖隔离账户、可信测试证书库及灰度回显目标。正式签名安装包、Android 系统浏览器、Keystore 和 VPN 的真机连通性仍待验证。

### Android 手机体验包（2026-11-24）

复用本机的 Android SDK 34、Build Tools 35 和 NDK 28.2，将本次订阅代码编译为可与旧版并存的 arm64 调试包（`com.proxy.android.subscriptiontest`，Android 7.0 及以上）。使用 Gradle 8.7 构建，24 项 Android 单元测试全部通过。在最终 APK 内核实了登录 Activity、订阅协议、Keystore 代码、指定 HTTPS 身份服务地址和 arm64 JNI 库。Android 官方工具验证 APK v2 签名及 16 KiB ZIP 对齐通过。

产物 `SimplePlane-subscription-arm64.apk` 大小 13,285,113 字节，SHA-256 为 `b9d0c5e0e214ead962b6297349deb34f91d08eb3a2733c0a7697778fe6a3daad`。同目录的 `SimplePlane-CA.cer` 为公开 CA 证书，SHA-256 为 `1d3cdd2357607bc3c38964d229c53a6e067e33326ae95c2cce255a8cdc42f4b7`。验证该 CA 可用于在线上设备页建立 TLS 连接并收到登录跳转。构建时 adb 未连接手机，真机浏览器授权、Keystore 签名和 VPN 数据流尚待验收；正式节点发布状态见[常驻部署记录](deployment.md)。
