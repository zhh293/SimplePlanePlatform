# 设计

在 ProxyMessage 枚举末尾增加 UDP_CONNECT（ordinal 6），保留所有现有编号。每条 HTTP 业务流表示一个固定目标的 UDP 关联，UDP_CONNECT.data = `udp-v1`，成功 CONNECT_RESPONSE.data 必须回显 `udp-v1`，客户端校验后才发送数据。旧端未知类型或未回显能力时显式失败，不降级 TCP。DATA 的一条 ProxyMessage 即一个 UDP 数据报（可以为空），DISCONNECT 释放关联。使用现有加密可靠流承载；不承诺 QUIC DATAGRAM 的丢包/时延特性。

服务端复用会话的注册、关闭和回包路径；UDP 关联以业务 Channel ID + logical streamId 隔离。UDP 出站使用 NioDatagramChannel，只收固定目标响应，空闲 60 秒回收。限制 1024 个 UDP socket 和 1472 字节 payload，写缓冲不可写时丢弃并记录原因。UDP ACK 表示出站关联已创建，不保证无握手的 UDP 目标在线。

Netty 和本机 JDK 17 的 connected DatagramChannel 写零字节不会发出空包。并且发送后重新 connect 可能清除已经到达的回包。因此服务端只在初始化时借助 Bootstrap.connect 完成解析和取得固定 peer，在 ACK 前断开底层 UDP connect。随后保持同一个已绑定端口的 unconnected socket，所有上行都显式写到这个固定 peer，所有下行都验证来源；空包用 JDK unconnected send 发送，非空包使用 Netty DatagramPacket。每个包之后都不再重连 socket。真实 socket 测试覆盖 101 个连续空包、固定源端口、空包后继续发送、接收以及不同来源注入。

plane-core 新增独立 UDP 管理器，TUN 栈将合规 IPv4 UDP 包（DNS 保留原路径）交给它；按四元组建立有界关联队列，异步路由和建连，回包经有界队列回到同一 TUN writer，保留 FakeIP 作为回包源。DIRECT 的原生 UDP fd 先 protect 再 connect。关联任务监听停止信号、空闲计时及隧道关闭，结束时关闭代理流；重建只由新的应用报文触发，不重放旧数据报。

每关联上行队列 32 个数据报，TUN 回包队列 128 个包，最多 1024 个关联（含建连中的关联）。HTTP/2 按实际 flow-control credit 分片发送加密块，接收端按原协议重新组帧，不在 h2 内无限堆积；HTTP/3 使用已有异步写背压。单次发送阻塞超过 60 秒结束关联。TUN select 使用公平调度，处理 UDP 后继续驱动既有 TCP 栈。
