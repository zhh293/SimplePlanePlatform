# 桌面 UDP-over-TCP / HTTP/3 验收记录

2026-11-22，基线 `1312fb8`。从干净的独立工作树构建 local 和 remote。桌面 UDP 功能、真实 Wintun 和节点切换通过下列功能用例。**公网灰度期间共享主机发生全局 OOM，正式服务重启一次。本轮无法给出生产容量或发布通过结论。** [协议与关闭时序](../../design/desktop-tun-udp-over-tcp-http3.md)

## 测试结果

| 环境 | 用例 | 实测结果 |
|---|---|---|
| Windows，Java 全 reactor | Maven verify，随后对最终测试集执行 test，启用生产 Rust TUN 包探针 | 最终 249 项，245 通过，4 条件跳过，0 失败、0 错误。保留原标准 SOCKS UDP 测试；H2/H3 × 三种加密的 6 组端到端用例全部通过 |
| Windows，TUN | Rust 全套、examples 构建、clippy --all-targets | 84 单元/组件和 2 集成通过，4 项原有手工测试 ignored；clippy 成功并报告已有提示 |
| Windows，桌面 | Rust、tun-status、updater-ui、updater-installer | 16 项 Rust 测试通过，三个前端/安装回归脚本通过 |
| WSL，Android 共享核心 | plane-core 完整 Rust 回归 | 117 通过，2 项依赖外部环境的测试 ignored |
| HTTP/3 生命周期专项 | 开启 http3.lifecycle.probe，流额度设为 4，10 种结束方式各跑 12 次 | 120 次完成；各组结束时两端流数、服务端会话数和活动目标连接数均为 0 |
| 单向首包与竞态 | 首发时退役、OPENING 时关闭后才返回 socket、建立期间排队超过上限 | 首包无 DefaultFuture；旧 TCP 通道关闭，迟到 socket 被关闭且无写出；被接纳的数据报按入队顺序提交 |
| 旧版 H3 remote | 加载仅含 UDP_CONNECT 的旧 jar，新 local 发送带载荷的扩展命令 | 本机通道关闭，受控 UDP 目标收到 0 包 |
| 真实 Wintun，本机两个 H3 节点 | 同一应用 UDP socket、同一 FakeIP 目标，0/1/1472 字节，手动 A → B → A，再停止 A 触发自动切换 | 数据报与来源元组正确，旧本机 TCP 收到 EOF，新数据报成功；默认超时配置下实测自动切到 B 并恢复用时 65.468 秒 |
| Windows 经公网 H3 到 Linux 灰度 | AES-GCM-v2、证书校验、生产 Rust UdpManager 包注入、远端真实 UDP echo | 0/1/2/127/1024/1472 字节往返，超长帧被拒绝。手动 A → B → A，旧连接 EOF、新连接可用 |
| 公网并发 | 16 个流分别发送 20 个带身份的数据报 | 首轮 320 包逐包校验通过。复测在等待回包时超时，未获完整第二轮结果；未定位到具体丢包环节 |

首次真实网卡探针把网卡本身设为 FakeDNS 首个测试地址，Windows 自动生成的主机路由与测试路由冲突。修正探针地址后完成上述 Wintun 验证，临时网卡及四条测试路由已移除。远端公网测试使用 Rust 包注入，真实 Wintun 的切换测试使用本机双节点。Java 常规套件的四个条件跳过为向量生成、H3 生命周期探针和两个系统代理测试；H3 生命周期另行启用执行。测试 JVM 缺少 java.instrument，运行时禁用了 JaCoCo。

## 公网灰度与正式服务影响

在同一台 Linux 主机上启动独立的 A、B 两个临时 Java 服务和 UDP echo，使用专用证书、随机应用密钥和测试 jar。新的公网 UDP 高端口未建立连接，实际通过已开放的 UDP 9090 匹配两个测试来源四元组，仅将测试流量导向灰度端口。正式 jar 和配置均未替换。

主机物理内存约 891 MiB，无 swap。灰度采用两个独立的 384 MiB 服务上限；这个配置没有为正式服务保障足够的主机级内存余量。**北京时间 17:13:50，内核记录全局 OOM 并终止正式 Java 进程 PID 292611；17:13:52 systemd 重新启动该服务，PID 变为 322521，NRestarts 由 13 增至 14。** 已确认此次服务中断，无法仅据内核记录判定所有内存增长的来源。两实例灰度在该主机上不再继续。

清理后已检查两个灰度服务、echo、清理定时器均已停止，灰度端口释放，两条临时 NAT 规则和远端私有目录已删除。正式服务为 active，nginx 配置指纹与开始时一致。另从公网直接验证了正式服务的 QUIC 握手、TLS 证书和 ALPN h3。后续容量复测应使用与正式节点隔离的主机。

| 灰度构建物 | SHA-256 |
|---|---|
| proxy-local jar | `8ad196f30a7c61a0b487ad0c56df7be45fa753382cbbe0334e416431e35791fb` |
| proxy-remote jar（上传后校验） | `1242f910dd5a36fac6bdab7ca2511c7887d4ea5fbf988c8e0acb70d841f545d6` |

## 复测入口

运行 `python scripts/test-desktop-udp.py` 可构建 Rust 包探针并运行 Java 全链路矩阵。H3 流额度专项额外启用 `-Dhttp3.lifecycle.probe=true -Dhttp3.lifecycle.cycles=12 -Dhttp3.lifecycle.limit=4`。真实网卡用例使用 `udp_wintun_probe` example 和四条临时 /32 路由。代码内的回归断言见[端到端矩阵](../../../proxy-local/src/test/java/com/proxy/local/UdpEndToEndIntegrationTest.java)、[单向首包归属](../../../proxy-exchange/src/test/java/com/proxy/exchange/header/ConnectionOwnershipTest.java)和[远端建立取消](../../../proxy-remote/src/test/java/com/proxy/remote/integration/UdpAssociationIsolationTest.java)。
