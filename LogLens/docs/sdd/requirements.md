# LogLens 第一版需求规格

状态：实施基线 v1（2026-10-12）。来源：`../loglens-architecture.md` 第 1–15 节。用户授权依据架构完成整个项目。SDD 在本仓库指规格驱动开发：规格、设计、任务、实现、验证全程可追踪。架构冲突以源架构为准，新增约束须记录设计决策。

## 功能与非功能需求

| ID | 需求与验收条件 | 架构来源 |
|---|---|---|
| R01 | Java 17/21 的 premain Agent；单 JVM 单会话；普通 Java 与 Spring Boot 无须改日志调用；独立可执行产物 | §1,2,14 |
| R02 | Logback 分发边界一次采集；多 Appender、继承/additivity 不重复；原级别和 TurboFilter 仍有效；发布经实测的版本兼容矩阵 | §4,14 |
| R03 | observe 保留全部原输出；replace-console 仅抑制指定直接 ConsoleAppender；Async 包装目标回退 observe；reset 后仍正确；缓冲失败保留当前原输出 | §4 |
| R04 | 同步复制格式化消息、模板、有限参数、事件时间、线程、MDC、异常及可选 caller；限制大小并标记截断；不跨线程保留应用对象；重入 finally 清理 | §4.3,10 |
| R05 | bootstrap JDK-only 独立桥；隔离运行时及第三方依赖；插件 API 共享身份；不污染应用依赖 | §5 |
| R06 | MULTI Disruptor、2 的幂容量、单消费者、LOG/TICK/STOP/INVALID；DROP_NEWEST 默认，显式 BLOCK；finally 提交与消费清引用；有界停机 | §6 |
| R07 | 外部插件按路径排序；ServiceLoader 指定 loader；重复 API/providerId、多整链冲突明确；默认链显式回退；两种 SPI 示例 | §5,7 |
| R08 | 独立游标，process 最多 proceed 一次；不得异步保留 continuation；flush 从下游开始；异常输出节点入口快照并标失败，可配置 DROP；诊断计数/耗时 | §7.2 |
| R09 | 默认 FieldExtract → Template → RepeatFold → Correlation → Highlight；规则预编译、MDC 映射、模板归一化、有界首次模板缓存 | §7.3 |
| R10 | 固定单调时钟折叠窗口、logger/level/template/exceptionType 分组、可配置关联键；每条先保存脱敏 captured；有限样本；ERROR 立即输出；tick/后续输入/容量/STOP flush；FINAL NORMAL/AGGREGATE 含源引用 | §8 |
| R11 | YAML 启动一次加载；schema/未知字段/ID/provider/regex/range/path/order 完整校验；不可变快照；路径相对 YAML；修改/删除文件不改变运行状态，重启重置状态；configHash 与安全摘要 | §9 |
| R12 | 脱敏先于队列、captured、模板、异常、MDC 与输出；只保存脱敏副本；配置摘要不泄露明文规则值 | §3,4,8,9 |
| R13 | 独立会话目录、session.json、diagnostics.log、combined/精确分级文本、captured/processed JSONL、index.sqlite；先文件后索引后广播；独立 capture/record sequence | §10 |
| R14 | 按日期/大小滚动、期限/磁盘上限清理关闭 segment 与索引；不删除当前片段；索引可重建，忽略末尾不完整行；部分输出失败标降级；明细过期明确；十万行分页且内存有界 | §10,14 |
| R15 | 只读 HTTP API：session、records 游标/筛选、record detail、sources 分页、events SSE、loaded config、health；loopback，无 CORS；限制查询与连接 | §11 |
| R16 | SSE 客户端独立有界队列、gap/断开、序号补齐；订阅水位+历史合并去重，历史/实时切换无遗漏；慢客户端不阻塞消费者 | §11 |
| R17 | 深浅主题，顶栏/筛选侧栏/虚拟列表/详情/健康，暂停跟随继续接收并提示新记录，URL 筛选、订单镜头、栈/MDC/规则/重复明细；文本渲染防 XSS；核心筛选、主题、列表详情可用键盘操作且焦点清晰 | §11.1 |
| R18 | 可执行只读历史 viewer 复用 UI/query，不运行 Agent 或改变旧结果；安全自包含 HTML 导出，防 script 逃逸，限制导出/DOM 大小；异常结束展示 captured 与未完成说明 | §8,11 |
| R19 | 启动顺序遵循架构；默认初始化失败禁用 Agent 保留原日志；STRICT 终止启动；退出先停采集/接管，有限排空/flush/关闭线程与 loader；超时/强杀不得标完整 | §13 |
| R20 | 缓冲满、磁盘/索引故障、慢 SSE、插件异常/阻塞有计数与诊断；磁盘故障解除接管，无无界等待；正常退出/强杀差异验证 | §13,14 |
| R21 | api/bootstrap/agent/adapter/runtime/processors/config/storage/web/ui/viewer 分模块；提供普通 Java、Spring Boot 和插件演示，配置、构建/运行说明 | §2,12 |
| R22 | 固定种子日志发生器与可控时钟窗口测试；对比 baseline/observe/replace 的延迟、吞吐、CPU、GC、内存、磁盘、丢弃；性能目标由实测给出，不臆造 | §14 |
| R23 | 不实现配置中心、Attach、配置/插件热加载、多 JVM 集中、任意 stdout 捕获或完整调用图 | §1 |
| R24 | 开发、依赖缓存、临时文件与构建输出不写 C 盘；使用 IDEA 配套工具 | 用户约束 |

## 验收门禁

- G1：规格覆盖源架构，设计和任务已追踪，然后才开始业务实现。
- G2：API/配置契约及校验测试通过，bootstrap 无第三方依赖。
- G3：Agent 切点与隔离类加载通过真实子 JVM 测试。
- G4：流水线、SPI、存储和故障测试通过。
- G5：网页、历史/实时衔接、历史 viewer/导出与安全测试通过。
- G6：Java 17/21、Logback/Spring Boot 兼容性和性能结果已记录。
- G7：逐需求最终审计，未完成/未验证条目为零才可宣称整个项目完成。

初始化失败、降级与丢弃均允许架构规定的行为，但必须有证据验证；不承诺零丢失、全版本兼容或无性能开销。

## 连接监控扩展（2026-10-18）

| ID | 需求与验收条件 | 架构来源 |
|---|---|---|
| R25 | LogLens Agent 在 proxy-remote JVM 中以 Java 17/21 运行；服务端业务源码无需修改；只通过 premain 参数启用；Agent 初始化失败遵循现有 observe/strict 策略 | 本次融合目标；docs/loglens-architecture.md §2,5,13 |
| R26 | Agent 应记录每个入站代理连接的稳定会话内 ID、远端 socket 地址、传输方式、建立/断开时间和关闭原因；HTTP/2 连接与逻辑 Stream 必须区分；不把来源 IP 称为用户账号 | 本次融合目标 |
| R27 | Agent 应记录逻辑代理会话的 stream ID、关联入站连接、目标 host/port、建立结果、状态和结束原因，并统计客户端到目标站点及目标站点到客户端的字节数 | 本次融合目标 |
| R28 | 流量按有界时间间隔汇总；采集过程中不保留、不解密、不落盘代理 payload，不逐数据包创建持久化事件；每个会话关闭时写出最终计数 | 本次融合目标 |
| R29 | 日志与代理监控事件进入同一个 LogLens Agent、同一个有界采集队列、同一会话存储和同一 Web 控制台；事件维持强类型区分，监控事件不经过日志折叠/日志插件链 | 本次融合目标；D09 |
| R30 | 监控事件应支持有界 JSONL/SQLite 历史查询、活动连接快照、按时间/IP/传输/目标/状态筛选，以及带序号的实时 SSE；历史与实时衔接须无遗漏或明确给出 gap | 本次融合目标；R13–R16 |
| R31 | UI 应提供入站连接、逻辑代理会话、上下行流量和关闭/失败详情视图，并可从会话关联查看同一 Agent session 中的日志 | 本次融合目标；R17 |
| R32 | 新增管理数据默认只监听 loopback、无 CORS、只读；不扩大现有 Web 的网络暴露范围；远程访问通过 SSH/VPN 隧道或受保护反向代理 | 本次融合目标；R15 |
| R33 | 采集及事件发布不得阻塞 Netty IO 或代理业务线程；活动状态、事件队列及每连接计数有明确上限；满载时按可观测的 drop 策略降级，不影响代理转发 | 本次融合目标；R20 |
| R34 | Java 17 与 Java 21 均须用真实 proxy-remote 测试子 JVM验证；若服务端以 Java 8 启动，Agent 应清晰禁用监控并报告兼容性错误，不得静默宣称已监控 | 本次融合目标；R01,R19 |
| R35 | Docker 部署必须在同一 Java 17+ Proxy Remote JVM 中启用 LogLens Agent；Agent 配置与历史数据可外部挂载/持久化；面板维持 loopback 绑定且宿主机发布仅为 loopback；默认不将代理服务端口或面板公开到公网，公网代理端口必须显式覆盖配置 | 本次容器化融合目标；R29,R32,R34 |
| R36 | 搜索索引后端通过配置选择；SQLite 是默认后端且保留当前 schema、过滤、分页、captured/source/telemetry 查询、JSONL 重建与保留行为；启用 Elasticsearch 不得要求删除或迁移 SQLite 会话格式 | 本次搜索后端扩展；R13,R14,R30 |
| R37 | Elasticsearch 后端须提供与 SQLite 相同的在线 `RecordRepository` 查询契约；索引按应用/会话隔离，搜索结果可从权威 JSONL 定位并恢复完整脱敏 DTO；不得将 Elasticsearch 可用性作为 JSONL 归档完整性的替代 | 本次搜索后端扩展；R13,R14,R15 |
| R38 | 搜索后端切换不得改变责任链、RepeatFold 合并结果、文本日志和 typed SSE 的载荷/序号契约；processed JSONL 仍先于索引和 SSE，索引未成功时不得广播未完成持久化的 FINAL 记录 | 本次搜索后端扩展；R08–R10,R13,R16 |
| R39 | Elasticsearch endpoint、index prefix、连接/请求超时和可选 API key 环境变量均需受限校验；不允许任意非 HTTP(S) URL 或将 API key 写入配置摘要/session.json；HTTP 错误、超时和部分写入须使会话状态可观测且不宣称完成 | 本次搜索后端扩展；R11,R15,R20 |
| R40 | SQLite 与 Elasticsearch 的文本/地址子串过滤保持大小写敏感的字面匹配；分页按唯一递增序号做有界 keyset，不使用深偏移；给定 through 水位时后续写入不得改变该次补拉范围；稀疏 capture 序号不得因区间跨度导致漏查或无界枚举 | 搜索正确性强化；R30,R36,R37 |
| R41 | ES 索引写入可合并为有界 Bulk 请求；处理 FINAL 或 telemetry 时，写入批次须在对应 SSE 广播前成功可见；bulk 部分失败不能被当作成功，重试必须幂等；SQLite 写入顺序与行为不变 | ES 写入吞吐优化；R37,R38 |
| R42 | 搜索结果从 JSONL 指针恢复时，同一 segment 的一页结果复用文件句柄，返回顺序、DTO、错误行为和保留时可删除文件的语义不变 | 查询 hydration 优化；R13,R36,R37 |
| R43 | Docker Agent 配置显式保留 SQLite 默认搜索后端，并允许运维切换到 Elasticsearch；ES endpoint 必须从容器网络可达，可选 API key 只通过宿主机环境变量传入，不随镜像/配置文件提交；默认编排不得额外启动或公开 Elasticsearch | Docker 搜索后端配置；R35,R36,R39 |
