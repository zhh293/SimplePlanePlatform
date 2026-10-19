# 实现设计与契约

状态：设计基线 v1；按实现证据持续修订。源架构 `../loglens-architecture.md`。

## 模块依赖

`api` 仅 JDK；`bootstrap` 仅 JDK。`config` → api + SnakeYAML；`processors` → api；`storage` → api/config + Jackson/SQLite；`web` → storage/config + JDK HttpServer；`runtime` → api/config/processors/storage/web + Disruptor；`adapter-logback` → api/config/bootstrap/runtime（用反射立即快照不同应用 loader 的事件）；`agent` → runtime/adapter/bootstrap + Byte Buddy；`viewer` → storage/web。`ui` 提供纯 HTML/CSS/JS 资源，嵌入 web，无远程资源。

构建为 Maven reactor，Java release 17。固定依赖版本，不使用动态版本。Agent 外壳仅 premain/隔离加载器；将 shaded runtime JAR 与 bootstrap bridge JAR 作为嵌套资源。外壳把 bridge 加入 bootstrap search，runtime 用 platform parent 的 URLClassLoader 加载，应用类只能引用 bridge 的 JDK 类型。API 只有运行时一份；插件 loader API 父优先，其他类可自行加载，禁止插件 JAR 打包 API 类。

premain 参数为 `config=<path>`，可追加 `,strict=true` 用于配置本身无法解析时明确要求终止；路径中的逗号通过 config 占据参数尾部解决（strict 在 config 前）。外壳提取资源到 Agent JAR 同目录 `.loglens-runtime/<UUID>`，不使用默认系统 tmp。隔离加载器 platform 父级仍能看到 bootstrap CaptureBridge。嵌套资源名为 `loglens-bootstrap.jar` 和 `loglens-runtime.jar`；打包检查必须验证此布局。默认启动失败诊断后应用继续，strict 参数失败则抛出。

## 事件与 SPI

- CapturedLog 是不可变 record，字段包含 sessionId/eventId/captureSequence、eventTime/capturedAt、monotonicNanos、level/logger/thread、template/message、parameters、MDC、exception/caller、truncated。ExceptionSnapshot 只含字符串/字符串列表。
- LogContext 是消费者私有可复制状态，含不可变 captured、fields/tags/decisions、汇总计数/首尾时间/SourceRefs。链入口和输出分别创建快照。
- SourceRefs 用组键/时间和首尾 sequence 查询持久化明细，不在内存保留全部 ID；有限样本单独保存。分组键为各字段长度前缀编码，避免拼接碰撞。
- NodeDependencies 提供 SourceIndex 回调，在 RepeatFold 接收事件时为 captured 索引登记实际语义分组（含归一化模板与提取字段）。来源查询按实际分组与 sequence 范围，避免原模板和归一化模板不一致导致漏明细。
- ProcessedRecord 包含独立 recordSequence/id、FINAL 状态、类型、上下文快照和源引用。
- LogProcessor/Factory/ChainProvider/Continuation/Emitter 按架构名字发布；ChainBuildContext 含已加载 pipeline 定义，NodeDependencies 含可控双时钟与独立诊断接口。
- process continuation 只允许同步、当前消费者线程、调用一次。flush emitter 同样限定调用期间和线程，每次从当前节点的下一个位置进入。失败使用进入节点前的副本，避免失败中间状态泄漏或重复链。
- 未调用 continuation 的正常返回必须记录该节点的 DROP 或 BUFFER 决策。默认视为 DROP 并诊断缺失原因；RepeatFold 显式 BUFFER，避免暂存计入永久丢弃。运行时 processorDropped 包含正常丢弃与异常 DROP。
- LogProcessor 增加默认 beforeProcess(FlushContext, LogContext, EventEmitter) 回调。运行时在该节点 process 前调用，用于输入触发的过期/容量 flush。这样 flush 可输出多条，process 仍严格最多 proceed 一次；不把容量驱逐伪装为多次 continuation。

## 配置

根配置覆盖架构 YAML。每层严格白名单，节点 config 由对应工厂额外校验（未知插件参数归插件工厂负责）。重复 YAML key 拒绝；禁止任意对象构造与 YAML alias 放大。所有数字有上限，application 禁止路径分隔符。端口 0 可用于测试动态分配，生产示例 9527。web.host 仅 loopback。pipeline nodeId/provider 非空且唯一 ID；内置节点相对次序校验，用户整链有自主顺序但必须通过工厂和依赖校验。

配置用嵌套不可变 record、递归不可变 node config；预编译 Pattern 保存在运行时规则对象。configHash 为原 YAML UTF-8 字节 SHA-256；API 摘要只展示配置结构、节点 ID/provider、数值设置与规则 ID，不返回敏感表达式/replacement。所有变更必须重启。

配置扩展：capture 增加 maxMdcEntries/maxMdcValueChars/maxParameterChars/maxParameters/maxExceptionChars；runtime 增加 processorFailurePolicy=OUTPUT_FAILED|DROP、slowProcessorMillis；web 增加 maxClients/maxQueryLimit；这些字段有默认值，以满足架构有界要求。STRICT 在 agent.failurePolicy 中选择。

内置节点 config 契约：FieldExtract 为 rules[{field,pattern,group=value}] 与 mdcMapping{目标字段: MDC键}；Template 为 preferOriginalTemplate=true 和 replacements[{id,pattern,replacement}]；RepeatFold 为 windowMillis/maxGroups/maxSamplesPerGroup/bypassLevels/groupBy；Correlation 为 fields；Highlight 为 firstOccurrence/maxSeenTemplates/keywords。每个工厂拒绝未知参数，正则/替换引用和范围在 create 时校验。fields/tags/decisions 最终输出有运行时大小上限，插件不得绕过外围脱敏。

## 捕获和接管

Byte Buddy 对 Logback Logger.callAppenders 入口/出口插入 bridge enter/exit，不替代整个方法。入口立即快照并发布，bridge ThreadLocal 保存本次是否成功及目标名称；仅对直接 ConsoleAppender 的 doAppend 分支 Advice skip。检测包装 AsyncAppender 含目标时整个事件以 observe 回退并报告。原 Appender 的过滤器仍负责自身输出，TurboFilter/Logger level 由原 Logger 在 callAppenders 之前执行。Appenders 的不同输出过滤器无法定义统一全局日志级别，以事件分发边界为采集语义。

捕获范围检查在快照/申请槽位前；桥有 finally 清理重入；快照始终当前应用日志线程；不持久化/缓存原事件/Throwable/参数对象；诊断避免输出未脱敏消息。接管 active 同时要求成功发布、存储健康、运行时未停止。reset 后 Advice 仍作用新 ConsoleAppender；版本/结构不兼容诊断并禁用捕获。

## 消费、折叠和生命周期

Disruptor MULTI + BlockingWaitStrategy，线程 daemon；输入 LOG 已脱敏，消费者先 captured 再 process；TICK/STOP 只触发 runtime flush。申请槽位 finally 发布 INVALID 兜底，消费 finally clear。DROP_NEWEST 用 tryNext；BLOCK 明确阻塞，停机时禁止新生产者进入并等待在途生产者有限完成。

RepeatFold 用 LinkedHashMap 保存有界组，单调时钟固定窗口，每组一个独立摘要和有限样本；后续日志先刷新到期组，TICK 刷到期，STOP 全刷；CAPACITY 刷最早组；ERROR 不暂存。来源在 captured 索引重建可获得。首尾时间由捕获墙钟展示，不用墙钟判断窗口期限。

停机先 disable bridge，停止 tick，消费者 STOP flush 后关闭节点/存储；协调线程有限排空，超时 metadata incomplete，不能与写入并发关文件。初始化资源按逆序清理；默认 premain 捕获失败并诊断，STRICT 抛出终止。磁盘/索引错误置 output unhealthy，立即 disable takeover，消费者不建立内存重试队列。

## 存储和查询

SQLite 存索引和 source group/query 字段，JSONL 是事实依据。UTF-8 byte offset 和 length；文件追加 → 索引事务 → SSE。text 精确等级匹配。按 UTC 日期或字节阈值滚动；仅关闭 segments 可删除，同时删除索引位置引用。历史目录只读查询，不修改旧 index；缺索引/损坏索引使用工作区临时可重建索引。重建逐行流式扫描，末尾不完整行忽略并标记恢复状态，不能伪造旧 FINAL。

会话元数据 segments 为现存滚动日志/JSONL 文件清单，含 file/bytes/active；会话结束后 active=false。查询实时元数据扫描当前会话目录，结束写入 session.json 固定清单；片段清理后不保留已删除文件引用。

为完整重建 normalized template / extracted field 的 source_groups，追加 `groups-NNNN.jsonl` 语义分组日志，字段为 captureSequence/groupBy/groupValues。其写入先于组索引，属于结构化 segment；删除组片段同步清除关联索引，使无法恢复的来源显式标过期。 Source group security invariant: apply the same configured-key masking and message redaction to groupBy/groupValues before writing groups JSONL or SQLite and before persisting FINAL SourceRefs, so the query key remains consistent and raw plugin-derived values never reach disk.

SQLite native DLL 使用显式配置的 E 盘 `.build/native`（测试）或输出根 `.native`（运行），不放在会话或单次恢复目录中。Windows 在 JVM 退出前锁定已加载 DLL，避免它阻止会话删除或只读恢复目录清理；任何原生解压都不使用默认 C 盘 tmp。

records 用 recordSequence 的 keyset cursor，limit 1..配置上限；level/logger/tag/traceId/orderId/q 同步过滤，不使用 OFFSET 深分页。sources 分页用 captureSequence 游标与持久化组键/范围过滤。JSONL 行/导出/HTTP 请求有大小上限。

## Web/UI/viewer

HTTP bind loopback，固定 bounded executor 与连接上限；GET only、无 CORS、Host/Origin 检查；SSE 每客户端 bounded queue + 水位 handshake，连接建立后发送 ready 水位，客户端查历史 <= 水位、合并实时序号去重。断线查询 > lastSequence 补齐，gap 文字提示。客户端缓慢队列满则 disconnect/gap，不阻塞 append。

运行时创建 EventHub 并将存储成功广播接入，再启动 LocalWebServer；端口冲突须关闭消费者、存储与插件后传播初始化失败。退出时停止采集并排空，最后关闭 HTTP 和订阅。web.enabled=false 不创建服务；实际动态端口通过 runtime.webPort 暴露给集成测试。

UI 工业仪表台风格：炭黑/暖白双主题、琥珀强调、等宽日志、紧凑侧栏和细分隔线。虚拟列表固定行高，载入数据数量有上限，历史游标翻页；详情全部 textContent。URLSearchParams 存筛选；暂停只暂停自动滚动。viewer 复用 web，离线导出把 JSON 的 `<`/`>`/`&` 等编码，纯文本呈现，无外部资源、限制行数和字节数。

键盘可达控件保留原生按钮/表单语义；虚拟日志行使用 button role、可见名称和 Tab 焦点，Enter/Space 打开详情。按钮、输入框、选择框和日志行在 `:focus-visible` 下显示主题强调色轮廓。浏览器验收以真实 Chrome 键盘事件验证焦点、激活与详情状态。

## 验证设计

JUnit 5 单元测试：配置错误矩阵/不变性/路径、可控时钟窗口、责任链异常/continuation、插件冲突、segment 滚动/保留/恢复、来源与游标。真实子 JVM：Agent 打包、无 API 污染、多 Appender/reset/过滤器、捕获与原日志结果、Java 17/21、Spring Boot nested JAR + 外部插件。HTTP/SSE 集成：水位、溢出、查询限制和历史只读。浏览器自动化：虚拟列表、详情、主题、关注、暂停、安全导出。性能固定种子对照三模式，所有数据标环境与样本规模。

## 决策记录

| ADR | 决策 | 理由 |
|---|---|---|
| D01 | 先落规格、设计、任务与验证模板 | 用户明确要求严格 SDD，仓库没有既定流程 |
| D02 | Maven 缓存/临时/用户设置均工作区隔离 | 不写 C 盘，使用 IDEA 工具 |
| D03 | 反射 Logback 适配器 + bootstrap bridge | 适配应用自带框架及 Spring Boot loader，避免双份 Logback |
| D04 | 嵌套隔离 runtime 及独立 bridge | 应用依赖隔离；只共享 JDK callback |
| D05 | 显式有界配置与 keyset 查询 | 满足长消息、十万行、慢连接的内存上限 |
| D06 | `package-release.ps1` 统一构建后复制 Agent、Viewer 与 API JAR，并检查入口/manifest | 减少手动发布遗漏；测试由发布前单独的 `verify` 门禁负责 |
| D07 | 基准程序固定 seed=20261012、100,000 次参数化日志、64 logger buckets，对照 baseline/observe/replace-console；不设想定性能阈值 | 记录实测吞吐、调用分位数、进程 CPU、GC、采样峰值堆、磁盘字节、丢弃与接管回退，再据此制定目标 |
| D08 | 将日志行详情和核心操作纳入键盘可访问验收 | R17 的虚拟行是自绘交互控件；只有鼠标处理会使键盘用户无法查看详情，也无法辨认当前焦点。补充原生 button 语义、Enter/Space 激活与可见焦点，避免改变数据/查询契约 |

## Proxy Remote 监控集成（D09）

### 目标与边界

LogLens 仍以单个 premain Agent 启动。Logback 采集器与 Proxy Remote 监控适配器共享 bootstrap bridge、ProcessingRuntime、有界 Disruptor、会话目录、只读 HTTP/SSE 与 UI。监控数据是强类型 TelemetryEvent，不伪装成 CapturedLog，不进入 LogProcessor 折叠链。二者共享一个全序 captureSequence；ProcessedRecord 继续使用独立 recordSequence。

监控只观察结构和长度，不读取、保存或解密数据内容。客户端身份显示为 socket peer 与 streamId；无协议 userId 时不得推断账号。Remote API 继续 loopback bind，不为远程访问增加公网监听。

### Byte Buddy 切点

在 AgentRuntime 中安装独立、可 reset 的 ProxyRemoteTransformer，与 LogbackTransformer 同生命周期、同一 Instrumentation。当前 matcher 按精确类名和签名增强：SessionManager.register 的双参数入口登记逻辑会话；OutboundSession.setOutboundChannel 记录会话激活，close 记录关闭；forward(byte[]) 与 writeBack(byte[]) 仅读取数组长度并累计两个方向的字节。目标 host/port、session key 和 stream id 从注册会话的公开访问器快照，不额外织入 CONNECT 处理器。物理连接由 NettyServer$ 与 Http3Server$ 的 initChannel 切点观察；适配器据 channel 结构筛选物理 HTTP/2 channel 或 QUIC channel，并从 closeFuture 观察断开。实际 matcher 以 ProxyRemoteTransformer.java 和对应集成证据为准。

Advice 只向 bootstrap CaptureBridge 发送 JDK-only 的不可变 DTO（字符串、数字、枚举名）；不得把 Channel、Invocation、ProxyMessage、byte[] 或应用类对象传给后台线程。所有 advice 捕获异常并隔离，不得改变原方法返回值、异常或控制流。应用在 Java 8 上运行时 transformer 明确报告不兼容并禁用监控；目标矩阵为 Java 17/21。

### 事件、计数与有界性

新增 `TelemetryEvent` 和 `TelemetryKind`，类型至少包括 CLIENT_CONNECTED、CLIENT_DISCONNECTED、SESSION_OPENED、SESSION_CONNECT_FAILED、SESSION_CLOSED、TRAFFIC_SAMPLE。事件含 captureSequence、wall/monotonic time、connectionId、streamId、peerAddress、transport、target、status/closeReason、uplinkBytes/downlinkBytes。缺失字段使用 null/明确状态，不伪造。

活动连接与会话登记于有界 `ProxyRemoteMonitor`。每个会话使用 LongAdder 累计 `forward` 上行和 `writeBack` 下行长度；采样调度器按配置间隔生成累计值，以便前端恢复快照。会话关闭时立即生成最终事件并移除活动条目；重复关闭幂等。达到容量时拒绝新增跟踪并递增 drop 计数，不阻塞业务线程。Advice 只通过 bootstrap bridge 发布 JDK-only 数据，遥测与日志共用既有有界 ring buffer；遥测满载时 telemetryDropped 递增，日志 overflow 配置语义不变。

CaptureBridge 增加 JDK-only TelemetryCallback 注册点；Runtime callback 负责 DTO 校验、统一 sequence 分配和 tryPublish。Disruptor Envelope 扩展 TELEMETRY 类型；消费时日志走 captured→processor chain，TelemetryEvent 走 telemetry storage→统一 EventHub。消费 finally 清空两个类型引用。shutdown 顺序先禁用所有 bridge 回调、撤销 transformers，再停止 ticker/telemetry sampler，之后排空队列并关闭存储。

### 存储/API/UI

事实文件新增轮转 `telemetry-NNNN.jsonl`，SQLite 新增 telemetry_events 表及时间、connectionId、streamId、peerAddress、target、transport、kind 索引；元数据和保留清理同时管理新 segment。append JSONL→SQLite transaction→EventHub 的一致性顺序遵循现有日志存储。活动连接快照从 registry 获取并与历史事件分开返回。

只读 API 增加 `GET /api/telemetry/connections` 返回当前活动连接快照，`GET /api/telemetry/sessions`、`GET /api/telemetry/traffic` 和 `GET /api/telemetry/events?cursor=&through=&filters=` 查询历史事件；SSE 在现有 `/api/events` 追加 `telemetry` 事件，载荷带 captureSequence，复用 ready watermark/gap/补齐协议。查询限额、loopback、Host/Origin 检查与慢客户端策略沿用现有 Web 契约。

UI 增加“连接”页：物理连接列表、会话明细、目标和状态、上传/下载累计曲线、时间/IP/传输/目标过滤；从 streamId/connectionId 查询相关日志（以日志 MDC/字段中可验证的 sessionKey 关联，不匹配则不伪造关联）。虚拟列表/分页和 textContent 安全策略沿用日志页。历史 Viewer 复用同一 UI 与 Repository，read-only session 也能查遥测历史。

### 决策记录

| ADR | 决策 | 理由 |
|---|---|---|
| D09 | 日志与遥测共用 Agent、队列、存储会话和控制台；遥测保留独立强类型与持久化表，绕过日志语义处理链 | 避免维护第二个采集进程，同时防止高频字节计数被日志聚合/脱敏语义破坏 |
| D10 | 字节计数在 forward/writeBack 方法边界累计，并按间隔发累计快照 | 避免每数据包写事件；边界直接对应上/下行代理 payload 长度 |
| D11 | Agent 适配目标 Java 17/21；不承诺 Java 8 运行时兼容 | LogLens 全模块以 Java 17 API 编译；代理模块字节码目标为 Java 8 不代表 Agent 可运行于 Java 8 |
| D12 | Docker Remote 镜像在 Java 17+ 中以 premain 启动 LogLens；Web 保持容器 loopback，由共享网络命名空间 relay 转发到宿主机 loopback；9090 默认仅 compose 内网可见，外部接入通过单独 compose override 显式启用 | 容器网卡发布不能直接访问应用 loopback；保持 R32 Web 安全契约，同时让 SSH 隧道可以访问；避免默认暴露代理端口 |
| D13 | 将在线结构化索引抽象为 SearchIndex 策略，默认 `sqlite`，可选 `elasticsearch`；JSONL 仍是归档事实源，SQLite 历史 Viewer/恢复路径仍固定使用私有 SQLite 重建索引；现有存储写入与 SSE 顺序对 SQLite 保持不变 | 搜索引擎仅替换在线查询索引，避免破坏离线会话与历史恢复；通过接口隔离后端和配置选择，不改责任链/折叠/SSE 语义 |
| D15 | Docker Agent YAML 显式保留 SQLite 默认值并提供 Elasticsearch endpoint/index 配置；Compose 仅透传可选 API key 环境变量，不在默认编排中启动 Elasticsearch | 支持容器部署切换搜索索引，同时不增加默认资源开销/网络暴露面，也不把凭据写入仓库 |

### 可选搜索索引（D13）

`SessionStorage` 继续拥有 session JSONL/text/metadata/retention 生命周期，在线索引通过 `SearchIndex` 接口注入。`SqliteIndex` 实现该接口并作为默认策略；`ElasticsearchIndex` 使用 Java 17 `HttpClient` 和 Elasticsearch REST JSON API，不引入额外 Agent 客户端类加载依赖。每个应用 session 使用独立 index 名；索引文档只保存查询字段与受校验的 JSONL segment/offset/length 指针，查询命中后仍从 JSONL 读取完整 DTO。captured、processed、telemetry、source group、按序号分页、watermark、删除段清理都纳入同一接口，不能只替换日志列表查询。

在线处理仍按 JSONL append → 已选 SearchIndex 更新 → 文本输出 → processed SSE 广播；SQLite 模式维持既有事务/失败行为。Elasticsearch 写入使用 REST 文档 API 和 `refresh=wait_for`，保证已广播 FINAL 记录可立即通过历史查询读取；这会把 ES 往返延迟放在唯一消费者上，部署前必须对目标日志速率做端到端基准，不承诺仅因采用 ES 就提升整体吞吐。Elasticsearch 配置包含 backend、HTTP(S) endpoint、index prefix、连接/请求超时和环境变量名形式的可选 API key。未选择 Elasticsearch 时不发起网络请求；离线 HistoryRepository 和 JSONL 重建仍使用本地 SQLite 临时索引，不连接 ES。RepeatFold 与责任链不感知后端。

查询一致性与分页（D13 补充）：SQLite `instr` 的筛选语义在 ES 使用 `wildcard` 字段和转义后的 `*literal*` 查询复现，保持大小写敏感和字面 `*`/`?`/反斜线匹配。captured、processed、telemetry 索引在创建时分别按唯一递增 `seq` 做升序 index sort，source group 按 `captureSequence` 排序；每页仍使用严格 `seq > cursor`、固定 `seq <= through`、`size = limit + 1`，不使用随页数增长的 `from`。有界 `through` 是 SSE ready watermark，索引只追加同序号文档，后续写入不会插入其范围；段保留删除属于有意过期。此契约与 SQLite API 游标兼容，并避免 HTTP API 增加 PIT 生命周期/不透明游标。ES 需为 7.10+，因为字面子串使用 wildcard query；深页表现仍需在目标集群按实际 shard/数据量测量。官方说明建议深页避免 `from/size`，在需要跨刷新稳定快照时用 PIT + `search_after`；当前实现依靠追加序号、水位上界及 index sort，不提供跨保留删除的快照保证（[分页 API](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results)、[Index sorting](https://www.elastic.co/docs/reference/elasticsearch/index-settings/sorting)、[Wildcard query](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-wildcard-query)）。

### 写入与 hydration 优化（D14）

ES adapter 将 captured 索引动作先放入有界内存批次（最多 100 项且最多 8 MiB）；单条待提交动作或体积超限时使用单文档 PUT，避免为单文档构造 Bulk 包。processed FINAL 动作与待提交的 captured/source-group 动作通过一次 `_bulk?refresh=wait_for` 提交，并且仅在 ES 返回所有 item 成功后才允许 `SessionStorage` 继续文本/SSE；telemetry 也在其 typed SSE 广播前提交。失败、item 部分失败或响应未确认完整批次时保留失败批次、将写入失败反馈给既有 storage health/complete 状态；Bulk 使用确定性 `_id`，重放部分已成功的 index 动作是幂等的。查询入口会先提交 pending 批次，维持 read-after-write。单消费者仍串行化写入，不创建无界后台队列或异步 fire-and-forget。

SQLite 与 ES 的分页 hydration 共用 `SegmentReader.readMany`：先按 segment 分组，一页内每个 segment 只打开一个只读 FileChannel，按原始命中顺序读取指定字节范围后立即关闭，不缓存长期文件句柄，避免 Windows retention 删除被锁文件。共享 ES 索引、n-gram 和自动压缩暂不采用：前者改变会话隔离/清理边界，n-gram 扩大磁盘与写入成本，压缩对当前较小的投影文档未证明收益；待真实集群基准后再决定。

### Docker 中的可选 Elasticsearch（D15）

容器挂载的 Agent YAML 明确配置 `output.search`，默认 `backend: sqlite`；切换 ES 时运维需提供容器可达的 endpoint。Compose 不捆绑或自动公开 Elasticsearch，避免给默认 Docker 部署增加 JVM、shard 与持久化卷运维成本。API key 仅从宿主机 `LOG_LENS_ELASTICSEARCH_API_KEY` 环境变量透传到 Agent 容器；配置只保存环境变量名，不保存密钥。ES/JSONL/SQLite 的归档、历史 Viewer 与失败语义不因 Docker 配置改变。
