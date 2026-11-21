# UDP 三端设计

依据：[需求](requirements.md)。参考现有桌面 UDP 方案及独立 `feat/android-udp-forwarding` 分支的服务端实现；参考不等于当前分支已实现。以当前 TUN 的共享路由、物理出口和网络恢复设计为准。

## 数据路径

应用 IPv4/UDP → TUN 校验、DNS 特殊路径、FakeIP 反查、UDP 路由。
DIRECT：适配器使用物理出口 UDP socket / 物理 DNS；PROXY：SOCKS5 UDP ASSOCIATE → loopback relay → UDP_CONNECT / DATA → 现有加密 H2/H3 → remote 固定目标 UDP socket。
响应沿关联返回，TUN 用原四元组反向构建 IP/UDP 包，统一通过栈 writer 写回。

## 协议与所有权

UDP_CONNECT 枚举只追加为 6，不改布局。请求与成功应答 data 精确为 ASCII udp-v1。关联只证明 UDP 出站准备完成，不证明目标在线。一条 DATA 对应一次 UDP 发送，包括零字节。
TUN flow key 为原始源/目标 IPv4 和端口；每 flow 一个 SOCKS 控制关联。local association 按控制 channel 隔离，内部按目标区分 session。remote 按传输 client + streamId 隔离。receiver 表只做回包分发，不增加节点绑定表。
接收器须先登记，再发送建立请求；ExchangeHandler 以 ownedStreams 限制推送与关闭影响范围。关闭幂等，迟到 ACK 只清理，未知 DATA 不建流。节点切换沿既有 current / client 退役流程关闭旧关联。

## 本地接入与路由

标准 SOCKS5 认证与命令累积解析；UDP relay 只绑定 loopback，并校验控制连接来源和客户端 UDP 端点。ATYP 支持 IPv4、域名；首期拒绝 IPv6 UDP 与 SOCKS 分片。一个控制关联允许多个目标。
TUN 已决策为 PROXY 的请求沿既有 127.0.0.2 来源约定进入 local，避免再次执行 DIRECT 造成 FakeDNS 回环。普通 SOCKS UDP 客户端按 local UDP RouteEngine 决策，DIRECT 使用异步真实 UDP 出站，REJECT 不创建远程关联。

灰度验证补充：local 为远程目标发出的 UDP_CONNECT、DATA、DISCONNECT 均携带内部 `route=proxy` attachment；RouterFilter 遇到该最终决策直接调用后继 invoker，不再执行旧私网直连短路。attachment 不改变线上消息格式。未携带该决策的旧调用保留原行为。端到端集成必须启用真实客户端过滤链，以免绕过生产路由问题。

## 状态与边界

目标状态 NEW → OPENING → ACTIVE → CLOSED。OPENING 有界缓存，能力确认后释放。空闲默认 120 秒，所有关闭事件释放控制/relay/目标 socket、receiver、pending 和 future。TUN 会话任务由 JoinSet 持有，退出/网络恢复时整体取消。
首期 UDP payload 上限 1472；TUN 最多 512 flow，每 flow 输入队列 64 包，统一回包队列 256 包。local 最多 512 SOCKS 关联，每关联 64 目标，全进程 1024 目标，OPENING 最多 64 包且不超过 64 KiB。remote 最多 1024 UDP socket。
H2/H3 每条 UDP 流最多 64 个在途 DATA 写任务；remote 上行/回包各最多 64 个写任务，Future 完成后释放额度。限制覆盖跨线程任务排队，不能只依赖 isWritable 的异步水位变化。超限丢弃，不重放旧业务包。
收到不支持的分片、非法来源或超限包时丢弃并保留诊断日志。
复用服务端已验证的 unconnected UDP socket + 固定 peer 校验方案，支持空包，避免 disconnect/connect 导致回包丢失。

物理 DIRECT UDP 支持 Windows 的 IP_UNICAST_IF 与 macOS 的 IP_BOUND_IF，同时固定源 IPv4。macOS 无法发现物理出口时保留代理路径，但 DIRECT UDP 失败关闭；其他平台没有验证物理出口时同样拒绝 DIRECT，不能跟随默认路由进入 TUN。Linux CI 用于无管理员权限的代理包注入测试。

## 验证设计

单元测试覆盖 IP/SOCKS 编解码、FakeIP、校验和、空包、畸形输入和上限。localhost 集成覆盖真实 UDP echo、SOCKS 控制生命周期、多目标/客户端隔离、路由、建立失败与迟到结果。隧道矩阵为 H2/H3 × 现有三套加密；验证 TUN 包注入到真实全链路的响应。运行原有 Java、TUN 和桌面相关回归。真实 Wintun 验证独立记录，不能由内存包注入替代。
