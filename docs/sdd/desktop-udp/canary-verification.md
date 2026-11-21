# 远端 UDP 灰度验收

日期：2026-11-21。依据：[需求](requirements.md)、[设计](design.md)、[灰度计划](canary-plan.md)。灰度时工作区基线 c3ef1d7；当时修复尚未提交，未发布或替换正式服务。随后按用户要求整理独立 review 分支，见 [提交范围](review-notes.md)。

结论：**已证实 Windows TUN → local → 远端能够代理真实 UDP，功能验收通过；重启恢复时间和突发丢包仍是发布限制，不能据此宣称容量验收通过或全量上线无风险。** 初次失败及其复测均保留。

## 环境与隔离

远端为 20.78.53.111 / Linux x64 / Java 17。使用独立目录、随机测试密钥、资源受限的临时 systemd 服务及独立 nginx 进程；未替换正式 jar、配置或重启正式服务。灰度 Java 单实例限 512 MiB 内存、一个 CPU 配额。

新公网 UDP 29990 未能从本机访问，具体限制位置未确定。公网 H3 改用已有 9090 入口，以**测试来源 IP、来源端口、目的 IP、目的端口精确匹配**的两条临时规则导向独立 nginx 29990，再转到灰度 QUIC 39990。校园 NAT 端口变化时仅刷新测试规则与对应探测连接的 conntrack 项，不刷新全局连接表。local QUIC 前有绑定物理网卡的透明 UDP 中继；公网段仍实际运行 QUIC。H2 使用 SSH TCP 转发到灰度 TCP 39991，不能与公网 H3 的测量作同路径性能比较。

远端 jar SHA-256：`f17638ba57f36ac1de17e7b89f09cbd44fef485f17e4dd1328b3d1b9d02d0074`，包含 Linux QUIC native 库。灰度发现的路由修复位于客户端，修复后已重新打包 local。远端继续使用该固定 jar，没有在运行中覆盖它。源码和本地构建指纹见 [灰度源码清单](evidence/canary-source-sha256.json) 和 [结构化结果](evidence/canary-result.json)。

## 实测覆盖

| 测试 | 结果与证据 |
|---|---|
| H2/H3 × chacha20、chacha20-poly1305-v2、aes-gcm-v2 | 6 组共 60 项全部通过：[matrix](evidence/canary-matrix.json) |
| 数据报边界 | 0、1、2、127、1024、1472 字节往返；1473 和 SOCKS FRAG 拒绝，后续合法包仍可用；同关联多目标通过 |
| 生产 TUN 包处理路径 | 每组均经真实 UdpManager、IP/UDP 校验与 FakeIP 映射到公网远端 echo；与真实网卡验证分开计数 |
| 公网 UDP DNS | 1.1.1.1 查询 example.com、www.bing.com、随机 .invalid，校验事务 ID、成功及 NXDOMAIN；这是 SOCKS UDP 案例，不把 TUN 专用 DNS 路径重复计为通用 UDP |
| 公网 STUN | stun.l.google.com:19302 Binding；返回公网映射 IP 为 **20.78.53.111** |
| 公网 NTP | time.cloudflare.com:123；响应模式、stratum、originate timestamp 验证通过，时钟偏差约 -3.36 秒 |
| 公网 HTTP/3 网页 | cloudflare-quic.com/cdn-cgi/trace 返回 200，ALPN h3，证书验证开启，网页报告出口 IP 为 **20.78.53.111** |
| 并发隔离 | 每组 16 关联 × 20 数据报，共 1920 包；另 H2/H3 各 32 关联 × 20，共 1280 包；均校验负载身份，无丢包、串包或损坏 |
| TCP/TLS 回归 | 每组 128 KiB TCP echo、Bing HTTPS 200；未替换的正式公网 H3 亦通过相同 TCP/TLS 回归 |
| 来源与畸形包 | 两种承载均拒绝外来端点、畸形输入，原合法关联继续工作 |
| 建立取消 | 两种承载各 40 次；FD 分别 26→28、23→25，按最多 2 个预热缓存的容差通过，不能宣称零增长或长期无泄漏 |
| 目标侧故障注入 | 每种承载发送 84 包，受控目标故意丢 12 包，收到 72 个唯一包、3 个重复，观察到乱序，0 损坏；未对正式网卡施加全局 netem |
| 空闲回收 | H3 实际等待 123.06 秒，FD 24→23，新关联可用 |
| 应用级大文件 | 远端独立、证书验证开启的 HTTP/3 测试站，H2/H3 各下载 1 MiB，SHA-256 均为 fbbab289f7f94b25736c58be46a994c441fd02552cc6022352e3d86d2fab7c83 |

大文件通过生产 TUN 包桥接器和真实 WAN，但目标应用是远端 loopback 上的可控 HTTP/3 站点。H2 用时 28.141 秒，H3 11.859 秒；这仅证明本次文件传输完整性，不是带宽基准。公网网页另有上述独立实测。公网 speed.cloudflare.com 的 1 MiB 案例未通过，见下文，不计成功。

## 真实 Wintun

使用管理员权限创建独立的 `SP-UDP-Canary-16160` 网卡，仅添加四条 198.19.254.x/32 测试路由。应用发包经**真实 Wintun 收发、生产 UdpManager、FakeIP、SOCKS、远端 H3**完成：

- 0、1、1472 字节远端 echo，核对回复来源仍为 FakeIP。
- 真实 STUN、NTP 与证书验证开启的 HTTP/3 网页，远端出口与上表一致。
- 检查临时网卡和四条路由已删除，默认路由、DNS 与测试前一致。

共 6 项通过：[Wintun 结果](evidence/canary-wintun.json)。这验证真实网卡 UDP 数据面；未替换已安装桌面端，也没有执行普通 TUN 主程序接管默认路由、系统 DNS 的完整用户流程，不能将它等同于安装包验收。macOS 实机未执行。

## 灰度发现并修复的问题

首轮真实远端 echo 暴露 DU-03 缺陷：入口已选择 PROXY，旧 RouterFilter 却将私网目标短路为 direct 成功响应，UDP_CONNECT 没有到达 remote。原端到端测试直接调用 invoker，漏掉了生产过滤链。

修复：Socks5UdpAssociation 为所有远程 UDP 消息携带内部 `route=proxy`；RouterFilter 保留入口的最终决策。增加 ExplicitUdpRouteTest，覆盖 UDP_CONNECT、DATA、DISCONNECT，保留无显式决策的旧 TCP 直连行为；UdpEndToEndIntegrationTest 的六组矩阵启用真实客户端过滤链，并用私网目标验证。修复后完整 Java 回归 **246 项，242 通过、4 条件跳过、0 失败/错误**；TUN Rust **84 单元/组件 + 2 集成通过，4 既有手工项 ignored**。本轮未将 ignored 计为通过，也未把 Android 并行改动纳入验收。

## 失败、限制与发布判断

1. **H3 节点重启恢复慢。** 首轮以 35 秒为限的恢复测试失败，紧随其后的空闲预检也因旧连接未释放而失败。延长观测后，同一 SOCKS 控制关联在 **61.312 秒**恢复，与现有默认约 60 秒 QUIC 空闲检测一致；H2 为 2.656 秒。没有为了得到通过结果临时降低超时，也没有加入业务 UDP 重放。默认故障发现速度不适合要求快速切换的业务，后续应单独设计和验收。原始 [生命周期结果](evidence/canary-lifecycle.json) 与 [恢复复测](evidence/canary-recovery.json) 均保留。
2. **瞬时突发明显丢包。** 无节流一次送入 512 包时，H2 收到 104、H3 收到 59；无损坏、测试后正常包可用。有界保护检查通过，**无损吞吐验收未通过**。尚未逐跳定位全部丢包，不能把损失全归于某个队列。不得用低速并发通过替代突发容量保证。
3. **外部大文件端点失败。** speed.cloudflare.com 在两条代理通道均出现 QUIC 握手 ConnectionError；[远端同工具直接访问该站也失败](evidence/canary-direct-baseline.json)，而直接访问 cloudflare-quic.com 成功。说明该端点失败不只发生在代理路径，尚未确认具体原因；不计通过。受控 1 MiB 站点完整性验证通过是另一项测试。
4. **能力范围。** 首期仍限制 IPv4、UDP payload ≤1472、不支持 IP/SOCKS 分片。UDP 通过可靠 H2/H3 流封装，存在重传等待。本次为有限时长和有限负载灰度，没有覆盖所有公网目标、长时间浸泡、macOS 实机、全量容量或所有断网/漫游情形。

建议保留小规模灰度，在故障恢复时间与突发丢包目标明确并满足前，不把本轮结果作为全量放量依据。

## 日志、清理与证据边界

独立 nginx 仅有 7 条错误：4 条 recv 与 3 条 sendmsg，均为故障注入重启窗口的 errno 111（上游拒绝），**未发现 buffer overflow / too small / busy buffer**。这是灰度 nginx 日志结论，不代表对正式 nginx 全部历史日志作出判断。没有采集完整内核 UDP 计数起始快照，不能声称主机级 RcvbufErrors 增量为零。

清理后灰度服务 PID 全部为 0，专属端口、两条 NAT 规则、远端测试密钥和配置均消失；本地中继、SSH 转发和测试客户端退出，临时 Wintun 网卡和路由消失。正式服务仍 active，**PID 292611、NRestarts 13** 与初始相同，nginx 配置 SHA-256 始终为 `152a8deaa534f13f17549c7f7c74bf755500c82926ebdafc9975c6f62ca26de9`。起始检查漏记了以 -cp 启动的正式 jar/config 哈希，因此不能宣称对这两者完成了前后哈希对比；本轮没有对其写入。

本机原始脚本/日志位于 `.build/udp-canary-20261121/`；凭据不进入文档或证据。仓库证据保存脱敏结果与日志摘要，原始文件 SHA-256 见结构化结果。matrix 的 HTTP 状态字段已从同名 status 纠正为 http_status，未改变请求实测内容或隐藏失败记录。转发中断导致的本地启动超时亦保留在原始日志，恢复测试通道后才进行大文件复测。
