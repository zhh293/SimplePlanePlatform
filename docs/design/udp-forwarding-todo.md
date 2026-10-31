# 待解决：代理支持桌面 TUN 模式和安卓端 UDP 数据包转发

- 状态：待解决 / 未开始实现
- 记录日期：2026-10-31
- 范围：桌面 TUN、Android VPN 数据面、共享代理协议及服务端 UDP 出站
- 本文性质：需求与问题记录；具体方案需在开发前按 SDD 补齐设计、任务和验收记录。

## 1. 问题与目标

目前代理的目标出站主要面向 TCP，尚未形成通用 UDP 数据报转发闭环。需要让桌面 TUN 模式和安卓 VPN 模式下的 UDP 流量能够按路由规则经服务端转发，并将响应送回原应用。

桌面客户端已有 TUN 模式，安卓端已有 VpnService/TUN 接入；本需求是补齐 UDP 数据面，不是新增 TUN 模式。已有 DNS 特殊处理也不等于支持通用 UDP。

客户端与服务端之间使用 HTTP/3 / QUIC，只说明隧道底层使用 UDP，不代表已经能够代理应用的 UDP 数据报。

## 2. 当前代码依据

| 模块 | 现状与相关代码 |
| --- | --- |
| 桌面 TUN | `tun-adapter/src/stack.rs` 已有 TCP 数据面和 UDP DNS 特殊处理，未形成通用 UDP 代理路径 |
| 本地 SOCKS5 | `proxy-local/.../handler/Socks5ConnectHandler.java` 只接受 CONNECT，不支持 UDP ASSOCIATE；`tun-adapter/src/socks5.rs` 目前使用 CONNECT |
| 安卓入口 | `android-app/app/src/main/java/com/proxy/android/PlaneVpnService.kt`、`NativeBridge.kt` 已有 VPN 接入及 socket protect 回调 |
| 安卓数据面 | `plane-core` 已有 TUN、DNS、TCP 和出站基础；需沿实际运行的数据面补齐 UDP，不能只修改桌面 tun-adapter |
| 共享协议 | `ProxyMessage` 当前未显式区分目标 TCP / UDP，需增加协议语义或另设兼容的数据报消息 |
| 服务端 | `proxy-remote/.../outbound/OutboundConnector.java` 使用 NioSocketChannel，`OutboundSession` 面向目标 TCP 字节流 |

服务端不能依靠 payload 嗅探可靠判断 TCP / UDP。当前链路也并非把原始 IP 包完整透传给服务端；客户端必须提供明确的 UDP 类型、目标地址、数据报边界和回包关联信息。

## 3. 待完成范围

- [ ] 桌面 TUN：解析 UDP 数据报，建立源应用流与代理关联，处理响应并重新构造 IP/UDP 包写回 TUN。
- [ ] 安卓 VPN：在 plane-core 的实际运行路径中实现相同能力，接通 JNI/配置及生命周期；相关出站 socket 必须遵守 VPN protect 机制，防止流量回环。
- [ ] 本地代理接入：确定桌面 UDP 如何进入 proxy-local；若沿用 SOCKS5 接口，需要实现 UDP ASSOCIATE，而非继续使用 TCP CONNECT。
- [ ] 共享协议：明确 UDP 消息类型、关联 ID、目标地址、长度/数据报边界、响应及错误语义，制定版本与能力协商方案。
- [ ] 服务端：增加 UDP socket 收发、关联表、响应路由和空闲超时回收，不直接套用 TCP 连接与 EOF 语义。
- [ ] 路由与 DNS：明确 PROXY / DIRECT / REJECT 的 UDP 行为，以及 FakeIP 到真实域名/地址的转换与 DNS 特殊路径关系。
- [ ] 资源与异常：限制关联数量、队列和报文大小，定义超时、拥塞、丢包、节点切换、断线与停止后的清理行为。
- [ ] 可观测性：记录 UDP 关联创建/回收、丢弃原因和转发统计，避免正常超时清理产生误导性告警。

## 4. 开发前需要明确的设计决策

1. **接入方式**：桌面沿用 SOCKS5 UDP ASSOCIATE，还是提供单独的本地 UDP 接口；安卓复用协议语义，不要求走桌面 SOCKS5 路径。
2. **隧道承载**：先通过现有可靠流承载带边界的数据报，还是使用 QUIC DATAGRAM；前者存在可靠有序传输的等待，后者需核实库支持、能力协商和最大报文限制。此处尚未选型。
3. **HTTP/2 兼容**：采用可靠流承载还是明确返回 UDP 不支持；不允许静默丢包并显示为成功。
4. **地址与大小边界**：定义 IPv4 / IPv6 支持范围、分片/重组策略、MTU、校验和、空数据报和超大数据报行为，不默认承诺全部支持。
5. **关联模型**：定义以源/目标地址端口、协议及客户端身份隔离关联，防止不同应用或客户端串包；规定允许哪些来源地址的响应。
6. **兼容策略**：旧客户端和旧服务端的 TCP 行为保持兼容，不支持 UDP 的对端须能被识别并反馈。

## 5. 验收与测试清单

- [ ] 协议单测：数据报边界、空 payload、非法长度、地址类型、版本及能力协商。
- [ ] 服务端真实 UDP echo：双向收发、多报文不合并不拆错、并发关联隔离及不同客户端隔离。
- [ ] 桌面端到端：真实 TUN → 本地接入 → 隧道 → 服务端 → UDP 目标，并将回包送达原应用。
- [ ] 安卓端到端：真机/模拟器 VPN 下完成同一闭环，验证 socket protect 和无回环。
- [ ] 路由/DNS：验证 PROXY / DIRECT / REJECT、FakeIP 和 DNS 的预期行为。
- [ ] 异常与回收：目标无响应、丢包/乱序、大小边界、关联超时、节点切换、网络重连及客户端退出后无资源泄漏。
- [ ] 回归：现有 TCP、HTTP CONNECT、SOCKS5 CONNECT、DNS 及桌面/安卓生命周期测试继续通过。
- [ ] 按最终设计覆盖支持的 HTTP/2 / HTTP/3 承载，明确真实环境测试与 CI 测试的范围，记录结果。

## 6. 本次不包含

本次仅登记待解决项，未实现 UDP 转发，也未确定具体承载方案。普通 HTTP/HTTPS 代理入口的 UDP 扩展、ICMP 和完整三层 IP 隧道不在当前需求范围内。
