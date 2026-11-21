# UDP 验证记录

日期：2026-11-20～21。状态：**三端实现及 Windows 自动化验收通过；随后完成远端灰度与真实 Wintun UDP 数据面验收。** 新结果及恢复时间、突发丢包限制见 [灰度验收](canary-verification.md)。下文保留最初本地验收记录，其“未部署/未建真实网卡”仅描述当时阶段。

流程按 [requirements](requirements.md) → [design](design.md) → [tasks](tasks.md) → 实现 → 本记录执行。基线 `c3ef1d7`，改动保留在当前分支工作区。

## 已实现

- 协议尾部追加 UDP_CONNECT=6；使用 udp-v1 能力应答，旧 TCP 编号、消息布局与套件不变。
- remote 固定目标 Netty UDP socket、来源过滤、空包、1472 字节上限、空闲回收、关联隔离及在途写任务上限。
- local 标准 SOCKS5 UDP ASSOCIATE、认证/命令累积解码、多目标、端点绑定、UDP 路由、独立 receiver、建立取消和节点退役清理。
- TUN 合法 IPv4 UDP 解析、数据报边界、FakeIP 反查/原地址回包、共享路由、物理出口 DIRECT、有界 flow 与单 writer 回包。DNS 继续原有专用路径。
- H2/H3 建流准入、UDP 回包所有权与迟到事件隔离；客户端取消向下传播；两种承载的 UDP 写任务均有上限。

## 环境与可重复命令

本机 Windows 11 x64；Rust stable 1.98.1；Maven 3.9.9（IDEA 内置）；编译 JVM 为本机 JDK 11.0.24，测试 JVM 为 Temurin 17.0.20.1。实际命令与各测试类计数见 [result.json](evidence/result.json)。本地精简 JRE 没有 java.instrument，因此本轮关闭 JaCoCo，**未因此关闭任何测试**；CI 使用完整 JDK 17 保留覆盖率。

通用入口：

```text
python scripts/test-desktop-udp.py
```

脚本顺序运行 TUN cargo test、构建生产 UDP 包处理探针，再运行 Java 全 reactor 测试。可用 --offline、--maven、--maven-repo、--test-jvm 适配本地工具。macOS 先准备已有 TUN 约定的 127.0.0.2 loopback 别名。生产代码不增加测试 CLI；探针放在 Rust examples。

## 结果

| 检查 | 实际结果 |
|---|---|
| Java 全 reactor / 48 测试类 | 245 项：241 通过，0 失败，0 错误，4 条件跳过 |
| TUN Rust | 84 单元/组件 + 2 集成通过，4 项既有手工集成 ignored |
| 三端矩阵 | HTTP/2、HTTP/3 × chacha20、chacha20-poly1305-v2、aes-gcm-v2，共 6 组全部通过 |
| 矩阵中的生产 TUN 探针 | 每组均执行 IPv4 包注入 → FakeIP → SOCKS → 加密隧道 → remote → 真实 UDP echo → 原四元组/校验和验证 |
| 矩阵中的连接重建 | 每组均在保持 SOCKS 控制关联时关闭旧 client、安装新 current、重新转发，并拒绝旧 streamId DATA 建流 |
| SOCKS 控制补测 | 6 项再次通过，包含逐字节拆包及认证/命令同包输入；原有回显、路由、取消断言保留 |
| Socket 清理收尾补测 | 共享 socket 创建/绑定/初始化失败时立即关闭资源；最终再跑 remote 4 项 + 三端矩阵 6 项，10 项通过 |
| desktop Rust | 16 项通过 |
| 桌面 UI / 更新安装准备 | tun-status、updater-ui、updater-installer 全部通过 |
| Rust clippy --all-targets | 命令成功，保留既有 config/stack/main 的 5 类提示；新增 UDP 模块无提示 |
| 静态检查 | udp.rs / direct.rs / udp_probe.rs 的 rustfmt 检查、Python 编译、两个 workflow YAML 解析、git diff --check 通过 |

四个 Java 跳过来自既有 CryptoVectorGenTest（1）、Http3OutboundIntegrationTest（1）、SystemProxyManagerTest（2），不是新 UDP 测试。TUN 四个 ignored 分别需要实际 TUN、路由环境或已部署代理；本次没有将它们算作通过。

## 需求追踪

| 需求 | 主要证据 |
|---|---|
| DU-01 | udp::tests::ip_round_trip_preserves_empty_maximum_and_fake_ip、rejects_truncation_fragments_checksums_and_oversize、原有 stack/TCP 回归 |
| DU-02 | fake_ip_flow_passes_socks_and_restores_original_tuple_and_closes_on_drop、reject_and_expired_fake_ip_create_no_association、六组生产包探针 |
| DU-03 | direct::udp_tests::pinned_udp_preserves_empty_and_maximum_datagrams_without_socks、TUN REJECT、local DIRECT/REJECT、既有共享 RoutePolicy/物理 DNS 测试 |
| DU-04 | Socks5UdpTest 六项：编解码、拆包/同包/认证、真实直连空包、来源/多目标/控制关闭、取消、REJECT |
| DU-05 | UdpAssociationIsolationTest 的能力/目标拒绝与客户端隔离；UdpEndToEndIntegrationTest 六组成功 ACK |
| DU-06 | 六组矩阵的 0/1/1472/1000/17 字节往返；UdpReceiverOwnershipTest 的空包、跨 client 与旧 receiver 关闭隔离 |
| DU-07 | TUN 取消关闭控制 TCP、local OPENING 取消、remote IdleState 清理、六组连接替换、原有 NetworkRecovery/节点切换回归 |
| DU-08 | TUN opening_queue_is_bounded_and_cancellation_closes_control；remote writeTasksAreBoundedInBothDirectionsAndRecoverWhenConsumed；H2/H3 UDP 64 写任务准入 |
| DU-09 | [结构化结果](evidence/result.json)、[测试摘要](evidence/verification-summary.log)、[源码 SHA-256](evidence/source-sha256.json)、可重复脚本及 CI 接入 |

## 回归中修正的测试前提

H2 原桩测试允许不存在的 streamId 由 DATA/DISCONNECT 隐式创建流。这会使旧 UDP 数据在新 client 上复活。本次与 H3 一致，仅 CONNECT/UDP_CONNECT 可以新建流。原 ClientServerIntegrationTest 改为先 CONNECT，再执行原有回显与 100 并发断言；另加未知 DATA/DISCONNECT 不建流的断言，没有删除或减弱原断言。

首次受限沙箱运行时，既有 QUIC 测试在 native 配置创建处发生崩溃；同一测试及最终全 reactor 在沙箱外通过。更新安装准备测试在沙箱内失败，未改安装脚本，沙箱外重跑通过。保留失败原始日志用于定位环境差异，不把失败记录隐藏为跳过。

## 证据及验收边界

完整本地日志保存在 `.build/desktop-udp/final-verification.log`，其 SHA-256 已记录在 result.json；最终 socket 收尾补测另见 [socket-followup.log](evidence/socket-followup.log)，没有重复累加到 245 项总数。正常 UDP 主动关闭使用 debug，最终矩阵中没有相应的流失败警告。仓库中同时保存 SOCKS 控制、桌面 UI、静态检查和源码摘要。测试 workflow 与 Windows/macOS release workflow 已接入包探针，但本次未推送触发 GitHub Actions。

本次 localhost 矩阵使用真实 TCP/UDP socket、真实 H2/H3/加密栈与生产 TUN 包处理代码；**没有创建真实 Wintun 网卡，也没有替换当前安装版或部署远端服务**。Wintun/UAC 下系统路由、实际网络抖动/吞吐、macOS 实机仍需独立验收。物理 DIRECT 代码支持 Windows/macOS；Linux CI 只验无特权代理包路径。

既有未提交文档、SPI 换行及 Http3Client 诊断代码已保留。过程中另观察到 plane-core 的安卓 UDP 并行修改，本轮未写入、回滚或纳入桌面测试结论。安卓 UDP 交付须依据其自身验收记录。

客户端与服务端需要同时构建更新才能使用 UDP。首期不支持通用 IPv6 UDP、IP/SOCKS 分片或 QUIC DATAGRAM；可靠流承载存在重传等待。
