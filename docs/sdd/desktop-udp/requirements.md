# 桌面 TUN / local / remote UDP 需求

日期：2026-11-20。基线：`fix/desktop-0.3.2-network-recovery`。
流程：requirements → design → tasks → implementation → verification。用户授权完成三端实现及测试，不自动部署、发布或覆盖安装目录。

| ID | EARS 需求与验收条件 |
|---|---|
| DU-01 | 收到完整、合法、未分片的 IPv4 UDP 包时，TUN 应保留四元组及数据报边界；非法长度、校验和、分片、超限明确丢弃；不影响已有 TCP / DNS。首期 IP MTU 1500，UDP payload 0–1472 字节。IPv6 通用 UDP 不在首期支持范围。 |
| DU-02 | 目标为 FakeIP 时应反查域名；映射不存在时丢弃；响应仍以原 FakeIP / 端口返回原应用，IP / UDP 长度与校验和正确。真实 IP 不依赖 FakeDNS。 |
| DU-03 | TUN 按现有共享路由策略处理 UDP 的 DIRECT / PROXY / REJECT；DIRECT 绕开 TUN、使用真实 DNS 与物理出口；PROXY 失败不自动转 DIRECT。 |
| DU-04 | local 应提供标准 SOCKS5 UDP ASSOCIATE：支持拆包认证与命令、真实 relay 地址、每包目标、来源绑定、多目标、FRAG=0、loopback relay；控制连接结束时清理关联。TCP CONNECT 回归通过。 |
| DU-05 | remote 应使用 UDP socket 按固定目标关联收发；新增枚举尾项 UDP_CONNECT=6，保持 TCP 编号与布局；成功应答必须匹配 udp-v1；不支持的对端失败关闭，不降级 TCP。 |
| DU-06 | HTTP/2 / HTTP/3 及既有加密套件应保留每条 UDP 消息边界、空包、并发客户端隔离；回包不得写进 SOCKS 控制 TCP；旧 DATA 不可创建已关闭的流。 |
| DU-07 | 建立失败、超时、取消、控制关闭、节点切换、连接断开、空闲和停机时应回收 socket / receiver / future / queue；迟到回调不得复活旧关联或污染其他客户端。 |
| DU-08 | 所有入口应有大小、数量和队列上限；不得在 EventLoop / TUN poll 中阻塞 DNS、等待网络建立或无限排队；正常空闲清理不报故障。 |
| DU-09 | 提供可重复的真实 UDP socket / SOCKS / 加密隧道集成测试、TUN 包注入测试与全模块回归；记录工具、命令、结果、限制，实机 Wintun / 远端测试与内存测试分开报告。 |

不包含 Android 数据面变更、QUIC DATAGRAM、ICMP、通用 IP 分片重组、完整 IPv6 UDP、应用层自动重试。可靠流承载 UDP 存在重传等待，不保证原生 UDP 时延语义。

2026-11-21 灰度补充：用户授权远端隔离部署及真实 Windows Wintun 验收，详见 [灰度计划](canary-plan.md)。DU-03 中入口已作出的 PROXY 决策须在完整客户端过滤链中保持，不能被旧私网直连规则覆盖；验证覆盖 UDP_CONNECT、DATA、DISCONNECT，且保留未显式决策的旧 TCP 行为。
