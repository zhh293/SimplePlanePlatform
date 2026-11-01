# Android UDP 转发

范围：Android VpnService → plane-core TUN → 路由 → 加密 HTTP/2 或 HTTP/3 → Java proxy-remote → UDP 目标 → 原应用。桌面 SOCKS5 UDP 不在本次范围内。

- 保持 UDP 数据报边界，包括空数据报；按源/目标四元组隔离关联，关联固定目标，不能串包。
- 复用 FakeDNS 域名映射及 UDP 路由规则；DIRECT socket 在任何发送前经过 protect，REJECT 不出站。
- 显式协商 UDP 能力，旧服务端不得把 UDP 当成 TCP 并报告成功。
- 有界关联数、队列、报文大小；空闲回收、断线及停止时释放资源。既有 TCP 不变。
- 本次支持 IPv4 TUN、无 IP 分片、单包不超过 VPN MTU 1500（UDP payload 1472）；不支持的包明确丢弃并记录原因。IPv6 TUN 延续现有限制。
- 以真实 Java 服务端和真实 UDP socket 测试上下行、空包、并发隔离、FakeIP、DIRECT/REJECT、异常回收及 TCP 回归。
- 用户当前无安卓设备，允许先完成实现及可运行测试。Unix socketpair 替代 TUN 设备验证实际栈循环，不宣称完成手机 VPN/protect 实测。
