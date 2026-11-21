# 桌面 UDP 提交范围

分支：`feat/desktop-udp-sdd-canary-20261121`。基线：`c3ef1d7d4bc0d27f53029cd52b6f4d3d03cac0b7`。

本次提交实现 Windows/macOS 桌面 TUN 的 IPv4 UDP 数据面、local SOCKS5 UDP ASSOCIATE、共享 UDP 协议及服务端 UDP 出站，包含边界、生命周期、队列限制、回归测试、CI 探针接入和 SDD 验收记录。灰度发现的入口 PROXY 决策被旧私网规则覆盖的问题已修复，完整客户端过滤链加入集成测试。

建议 review 顺序：

1. requirements.md → design.md：协议、路由、资源所有权与限制。
2. ProxyMessage / exchange / H2/H3 client：建流准入、receiver 所有权、数据报边界与在途上限。
3. remote OutboundConnector / OutboundSession 与 local Socks5UdpAssociation：建立取消、来源校验、空包、回收与背压。
4. tun-adapter/src/udp.rs、direct.rs、stack.rs：FakeIP、四元组、物理出口与数据面集成。
5. 测试、verification.md 与 canary-verification.md：自动化、真实公网和实际 Wintun 的证据边界。

已有验证：Java 246 项中 242 通过、4 既有条件跳过；TUN Rust 84 单元/组件及 2 集成通过，4 既有手工项 ignored；远端六组矩阵 60 项、真实 Wintun 6 项通过。初始失败及复测均保留。

提交前另外导出暂存区独立源码快照，在排除既有诊断代码后复跑 UdpEndToEndIntegrationTest 六组矩阵与 ExplicitUdpRouteTest，7 项全部通过；记录见 `evidence/review-snapshot-tests.json`。

发布限制尚未解决：H3 灰度节点重启恢复约 61 秒；512 包无节流突发时 H2/H3 分别仅收到 104/59 包，尚未逐跳定位全部丢失；公网 speed.cloudflare.com 的 QUIC 握手在代理及远端直连均失败。macOS 实机、安装后的完整桌面 TUN 接管流程、长时间浸泡和全量容量没有验收。此分支供代码 review，不代表已满足全量上线标准。

提交不包含原有 Android plane-core 并行改动、旧设计文档、update-server 证据变更、SPI 换行差异，也不包含先前已存在的 Http3Client 34 行诊断新增；它们保留在用户本地工作区。本次 Http3Client 的 UDP 建流/限额/关闭语义改动单独入库。

原 verification / canary 源码 SHA-256 清单记录的是测试执行时的完整工作区，其中 Http3Client 包含上述既有诊断代码；原清单作为历史证据保留。`evidence/review-source-sha256.json` 单独记录本次提交实际收录的实现、测试及构建脚本（Git blob 内容，统一为 LF），避免将两种快照混为一谈。本地 .build、密钥、依赖缓存和构建产物均不在本次提交中。

入库日志统一为 UTF-8 / LF，并去除行尾空白；内容未删减。原始文件保留在本地 .build，转换前后指纹见 `evidence/log-normalization.json`。
