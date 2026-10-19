# 实施任务与追踪

状态必须由实际文件/验证证据更新。`[ ]` 待实施；`[~]` 实施中；`[x]` 完成且有验证。

| 任务 | 状态 | 依赖 | 内容 | 需求 |
|---|---|---|---|---|
| T00 | [x] | — | SDD 基线、约定、设计、验收追踪 | 全部 |
| T01 | [~] | T00 | IDEA 工具链、E 盘缓存、Maven reactor 与构建脚本 | R21,R24 |
| T02 | [~] | T01 | JDK-only API、不可变模型、SPI 与 bootstrap bridge | R04,R05,R07,R08 |
| T03 | [~] | T02 | YAML validation, immutable config and security summaries; plugin-derived field/source-index redaction is now covered end-to-end, remaining redaction channels need audit | R11,R12 |
| T04 | [~] | T03 | 默认处理器、可控时钟折叠与有界状态；新增处理器规则边界测试已通过，默认生产链组合仍待最终验收 | R08,R09,R10 |
| T05 | [~] | T03 | SPI loader/冲突检查与链运行时 | R05,R07,R08 |
| T06 | [~] | T04,T05 | 结构化/文本/SQLite、segment 保留与恢复查询；captured/index 故障和 processed 事务回滚后 JSONL 历史重建已验证 | R13,R14 |
| T07 | [~] | T06 | MULTI/BLOCK saturation, blocked-publisher exit, STOP drain, storage-stage and web-stage reverse cleanup (including cleanup callback failure), SQLite partial-transaction fallback, and stuck-plugin deferred cleanup verified; other init phases, other persistent faults and forced-kill behavior pending | R06,R19,R20 |
| T08 | [~] | T07 | Agent 打包/真实 Logback/Boot/直接控制台/reset 已有阶段证据；Java17/复杂 Appender/故障与 loader 生命周期待验收 | R01–R05,R12 |
| T09 | [~] | T06,T07 | HTTP/SSE runtime integration, bounded client recovery and real non-reading TCP slow-client isolation verified; publisher timing excludes deliberate 5 ms pacing; browser reconnect/deduplication across concurrent history batches pending | R15,R16 |
| T10 | [~] | T09 | UI 虚拟流/详情/筛选/主题/关注/暂停；键盘语义、Enter/Space 与焦点样式已实现，新增浏览器验收待运行 | R17 |
| T11 | [~] | T09,T10 | 历史 viewer、恢复/导出/不完整会话展示 | R14,R18 |
| T12 | [~] | T08,T11 | Java/Spring Boot/demo、两种插件、运行说明 | R01,R07,R21 |
| T13 | [~] | T12 | 全栈兼容性、故障测试与安全测试 | R01–R20 |
| T14 | [~] | T13 | Three fixed-seed 100k performance runs; 100k history recovery and all 100 pages verified; variance control and performance target remain | R14,R22 |
| T15 | [~] | 全部 | 最终逐需求审计、验证报告、交付文档 | 全部 |

## 阶段门禁

- G1 已建立规格/设计/任务文件，后续实现须对应任务。
- G2–G7 尚未验证。不得标为完成。

详细未完成项与证据边界见 [逐需求审计](audit.md)，不得仅依据已有测试数量勾选任务。

## Proxy Remote 监控扩展任务

| 任务 | 状态 | 依赖 | 内容 | 需求 |
|---|---|---|---|---|
| T16 | [x] | T00 | 补充 D09–D11 与 R25–R34 的逐项映射、兼容性和威胁边界；规格、设计和任务追踪已写入 docs/sdd | R25,R29,R32,R34 |
| T17 | [x] | T02 | JDK-only TelemetryEvent/TelemetryKind DTO、CaptureBridge 回调与有效转发状态过滤；桥接测试通过 | R26–R30,R33 |
| T18 | [x] | T17 | 有界连接/会话注册、上下行累计、周期快照、关闭事件与并发容量/drop 测试通过 | R27,R28,R33 |
| T19 | [x] | T18 | 打包 Agent 在 JBR 21 与 Corretto 17 子 JVM 加载 SimplePlane 实际编译的 SessionManager/OutboundSession；Netty parent-child EmbeddedChannel 产生 open/active/close，累计 uplink=12/downlink=7；精确 FQCN fixture 覆盖 Netty init Advice | R25–R28,R34 |
| T20 | [x] | T17,T18 | 共用有界 Disruptor 测试验证日志占用一个槽时遥测填满其余 1023 槽；BLOCK 策略下溢出调用快速返回并计数；关闭后拒绝新事件且排空全部已接受事件 | R29,R33 |
| T21 | [x] | T20 | 遥测 JSONL/SQLite、只读 keyset 查询、JSONL 重建及专属保留测试通过；过期关闭段文件和 SQLite 行删除，活动段保留 | R30,R33 |
| T22 | [x] | T21 | 遥测查询 API、活动快照和带水位 typed SSE 已接线；容量为 1 的慢订阅溢出后明确 gap/关闭，新订阅水位界定范围并通过历史 API 补回缺失事件 | R30,R32 |
| T23 | [x] | T22 | 连接/会话/流量 UI、过滤、Stream 日志检索和 SSE 更新已由 Playwright 浏览器 smoke 验收；历史 Viewer 共用只读 Repository | R31 |
| T24 | [x] | T19,T23 | 最终 Agent 在 Java 17/21 实际 ProxyRemoteServer 中完成启动及 100 个 loopback 连接开关烟测（生命周期事件全量落盘）；实际 OutboundSession 字节计数、队列满载/排空、保留、gap 恢复、只读/loopback 安全和 Java 8 禁用守卫均有测试；部署说明已写入 `docs/proxy-remote-monitoring.md` | R25–R34 |
| T25 | [x] | T24 | 将发布 Agent 接入实际 systemd proxy-remote.service，验证 Java 17 启动、面板健康和服务 MainPID 持有 loopback Web 端口；通过本地 SSH 隧道验证 UI/API，并记录脱敏部署证据与运维步骤 | R25,R31,R34 |
| T26 | [~] | T24 | 更新 Docker Remote 至 Java 17+，构建并注入 LogLens Agent；为配置/历史数据增加挂载，为 loopback 面板增加受限 relay 与 SSH 访问文档；默认关闭公网 9090 发布并提供显式 override；需完成 compose 解析与镜像/容器验收 | R29,R32,R34,R35 |

## 可选搜索后端扩展

| 任务 | 状态 | 依赖 | 内容 | 需求 |
|---|---|---|---|---|
| T27 | [x] | T00,T06 | 增加 R36–R39、D13 与接口/失败/历史兼容设计；新增 backend 配置并保持默认 SQLite | R36,R39 |
| T28 | [x] | T27 | 抽象在线 SearchIndex 策略；SQLite adapter 完整保留 captured/processed/source/telemetry、恢复和保留契约 | R36,R38 |
| T29 | [x] | T28 | 实现 Elasticsearch REST adapter、会话隔离索引、查询/索引/删除操作和安全配置；真实 Elasticsearch 集群验收未做 | R37,R39 |
| T30 | [x] | T29 | HTTP mock 集成验证 SQLite 回归、ES 查询与失败、JSONL hydration、SSE/聚合契约；Java 17/21 均通过；真实 ES 集群和性能对比未纳入本任务证据 | R36–R39 |
| T31 | [x] | T30 | 对齐 SQLite 字面子串语义；ES 按 seq index sort 并使用固定水位 keyset 分页；稀疏 source 序号直接检索真实 captured 行；Java 17/21 测试通过，未运行真实 ES | R40 |
| T32 | [x] | T31 | ES bounded Bulk（最多 100 项、8 MiB）在 FINAL/telemetry SSE 前提交并检查逐项失败；SQLite 与 ES page hydration 按 segment 复用单页 FileChannel；确定性文档 ID 支持安全重放；Java 17/21 全量回归通过 | R41,R42 |
| T33 | [~] | T32,T26 | Docker Agent YAML 保持 SQLite 默认并提供 Elasticsearch endpoint/index 配置；Compose 仅透传可选 API key 环境变量；部署说明已更新；Compose/容器验收受当前环境无 Docker CLI 限制 | R35,R43 |
