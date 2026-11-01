# Android UDP 验证与复现

2026-11-01。实现包含 plane-core、共享协议、Java proxy-remote；`proxy-local` 无改动，也没有部署或替换现有远端服务。

## 已验证的链路

```text
完整 IPv4/UDP 包
  → Unix SOCK_DGRAM socketpair（替代 Android TUN fd，保持包边界）
  → AndroidTun + 生产 stack_loop_with_udp
  → FakeIP / UDP 路由 / 关联管理
  → 加密 HTTP/2 或 HTTP/3（真实 TLS/QUIC，测试 CA）
  → Java HeaderExchanger / ExchangeHandler / DispatchInvoker
  → 真实服务端 UDP socket
  → 真实 UDP echo 目标
  → 同一路径返回，重建完整 IP/UDP 包写回 TUN fd
```

测试在 WSL Ubuntu/Linux 本机回环网络执行，使用生产 Rust、Java 数据路径。TUN fd 的系统读写封装也在测试内；**没有执行 Android 系统 VpnService、JNI 回调、真实 VPN protect、Wi-Fi/移动网络或真实手机应用测试**。保护器在主机测试中检查调用及失败行为，不能据此宣称手机无回环。Android ABI 交叉编译、APK 打包、本次新增设备探针的手机运行均未执行。

## 功能覆盖

- HTTP/2、HTTP/3 各覆盖 chacha20、chacha20-poly1305-v2、aes-gcm-v2，共六组真实跨语言链路。
- 每组 33 个代理关联、92 个回包逐字节比较；包含 33 个空数据报、1 字节、17 字节、1472 字节及连续不同长度报文。
- FakeIP 域名在服务端解析，回包保留 FakeIP；校验 IP/UDP 地址、端口、长度及校验和。
- 同一目标的多个应用源端口并发隔离；Java 单独验证两个客户端业务流使用相同 logical streamId 不会替换或串包。
- 每组停止后检查 Java SessionManager 为 0；客户端 active_connections 停止后为 0。
- 真实 TUN 栈 DIRECT、REJECT、保护失败、FakeDNS 查询、过大响应丢弃后继续收发、空闲 60 秒后新包重建。
- 1024 个关联（包括 pending open）和 32 个上行报文队列上限；HTTP/2 小窗口且对端停止读取时发送必须等待信用，不得无限缓存。
- 服务端真实 UDP socket 验证空包、最大包、过大上行丢弃、不同来源响应过滤、idle 事件关闭及会话移除。
- 旧端即使返回 HTTP 200 / 成功 CONNECT_RESPONSE，也必须回显 `udp-v1` 才被当成 UDP 可用；两种传输都有能力确认反例测试。

最终测试计数和日志见 [evidence/result.json](evidence/result.json)。日志的秒数包括编译和进程启动，不是网络延迟或吞吐量数据。

## 复现命令（Linux / WSL）

需要 Rust/Cargo、JDK 17、Maven、Python 3、OpenSSL。正常环境不需要指定工作区缓存参数。

```bash
cargo fmt --manifest-path plane-core/Cargo.toml -- --check
cargo clippy --locked --manifest-path plane-core/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path plane-core/Cargo.toml --lib
cargo test --locked --manifest-path plane-core/Cargo.toml --test udp_data_plane

mvn -pl proxy-remote -am package '-Dtest=*,!BaiduProxyTest' -Dsurefire.failIfNoSpecifiedTests=false
python3 scripts/test-android-udp.py
```

最后一条命令会创建仅监听 loopback 的 Java 服务端、一次性 TLS 证书和 UDP 目标，自动跑六组链路并检查清理，任一失败返回非零。产物在 `.build/android-udp/run-*/`，包含每组 Rust / Java 日志及 results.json。脚本退出会终止自己启动的 Java 进程。已有离线依赖时可以加 `--offline`，也可以用 `--transports http3 --ciphers aes-gcm-v2` 缩小诊断范围。

本次最初执行未排除项的 Java 回归时，`BaiduProxyTest` 在发出 `Connection: close`、收到百度 HTTP 响应后，仍断言服务端会话保持 1，实际为 0，因此失败。没有修改该外网测试，也没有把它计入通过项；最终回归命令明确排除这一项。其余测试结果见证据。需要外部环境/显式启用的其他测试仍按原项目条件跳过。

## 后续手机验证

先在隔离的测试服务端部署本次 proxy-remote，提供可访问的 UDP echo 目标，再编译并安装包含本次 plane-core 的 APK。现有旧服务端不具备 UDP 能力，仅更新手机不能实现代理 UDP。

手机允许 USB 调试和 VPN 后，使用 shell UID 运行探针（不要在 VPN 排除的应用 UID 下运行）：

```bash
cd plane-core
cargo ndk -t arm64-v8a -P 24 build --release --locked --example android_udp_probe
adb push target/aarch64-linux-android/release/examples/android_udp_probe /data/local/tmp/android-udp-probe
adb shell chmod 755 /data/local/tmp/android-udp-probe
adb shell /data/local/tmp/android-udp-probe udp-echo.example.test:9001
```

将示例地址替换成真实测试 UDP echo 地址。探针并发 8 个 socket，各测试 1、0、127、1472 字节回包。必须核对 FakeDNS 地址、应用中 `UDP association opened` 的 PROXY 路由、服务端 UDP 收发和停止后会话回收，才能确认实际经过 VPN；单看 echo 成功不足以证明没有绕过 VPN。再覆盖 DIRECT / REJECT、protect 失败、节点不可用、断开重连、Wi-Fi/移动网络切换和持续运行。

## 支持边界

当前支持无分片的 IPv4 TUN UDP，payload 最大 1472 字节（IP 包最大 1500），空包合法。不支持的分片、错误长度/校验和、超过限制的包和队列满场景会丢弃并记录原因。IPv6 TUN、IP 分片重组、QUIC DATAGRAM、性能压测、长时间稳定性及桌面 SOCKS5 UDP 未包含在已完成验证中。

桌面接入由用户另行实现。除 `proxy-local` 外，本次也保留了 Java `Http3Client` 的原有建流行为；后续桌面发送 UDP_CONNECT 时，需要同步让 Java 客户端为该类型创建 HTTP/3 业务流。安卓使用独立的 Rust HTTP/3 实现，已经完成这一步。
