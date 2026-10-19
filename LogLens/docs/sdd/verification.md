# 验证记录

本文件只记录实际证据，不把预计结果当作通过。

## 环境

- 工作目录：E:\SimplePlanePlatform\LogLens。
- 已发现 IDEA：D:\IntelliJ IDEA 2024.3.2。
- 配套 Maven：plugins\maven\lib\maven3\bin\mvn.cmd。
- 配套 JDK：JBR 21.0.6；配套 Maven 3.9.9 已执行验证；Java release 17。
- 所有写入须定向工作区；不可使用默认 C 盘 Maven 仓库/用户临时目录。

## 实际验证

| 日期 | 检查 | 结果/证据 |
|---|---|---|
| 2026-10-12 | 仓库基线 | 初始无源代码/构建；只有 README、架构与用户文件；架构已存在用户修改，保留 |
| 2026-10-12 | G1 | requirements/design/tasks/verification 已建立，R01–R24 覆盖源架构与用户环境约束 |
| 2026-10-12 | 知识库 health | curl 失败；停止后续知识库请求；实现依据仓库架构与官方机制文档 |

## 待验证

Agent、Web/UI、Java 17 实际运行、Spring Boot、全故障矩阵、性能与最终审计仍待验证。

## 2026-10-12 模块验证补录

实际命令：`powershell -NoProfile -File scripts/build.ps1 test -pl loglens-runtime -am`。
结果：BUILD SUCCESS；38 个测试（api 3、config 10、processors 8、storage 6、runtime 11），失败/错误/跳过均为 0。证据位于对应模块 `target/surefire-reports/TEST-*.xml`。

- 配置：未知字段、重复键、不安全 YAML、非法范围/模式/路径/正则/替换引用；修改和删除配置后原快照保持不变。
- 节点：单调时钟窗口、不同异常类型、ERROR 绕过、容量驱逐、有界样本、MDC/正则字段与高亮。
- 存储：UTF-8 offset、精确分级文件、筛选游标、来源分组、写入失败健康回退、日期/大小滚动。
- 历史：独立索引恢复、截断尾行、旧文件逐字节不变、captured-only 会话不伪造汇总。
- SPI/链：API 重打包与 provider 冲突、多整链冲突、单次同步 continuation、失败入口快照、flush 下游绑定。
- ProcessingRuntimeTest：真实 Disruptor 发布三条敏感日志，STOP 排空并生成一次 repeatCount=3 FINAL；captured/processed 不含明文，session complete=true，关闭后拒绝发布。

这些证据仅证明上述模块场景，不证明整个项目完成；G3–G7 仍未通过。

## Web 初步验证

新增 EventHub 与 LocalWebServer；T09 实施中。EventHubTest 的 3 项测试通过：订阅水位、慢客户端有界溢出隔离、连接上限/序号约束。

LocalWebServerTest 使用真实 loopback HTTP 服务通过：读取已加载会话/配置，修改磁盘 YAML 不影响 API；拒绝 POST、跨域 Origin、重复/未知参数、非法游标及超大分页；缺失记录返回 404，响应不启用 CORS。命令采用 PowerShell 显式 MavenArguments 数组，避免 dotted `-D` 参数被外层 PowerShell 拆分。证据：`loglens-web/target/surefire-reports/TEST-io.loglens.web.LocalWebServerTest.xml`。

尚未验证 SSE HTTP 长连接/补齐端到端、运行时 Web 接线及 UI；不得据此标记 R15/R16 全部完成。

## 运行时 Web 接线验证

ProcessingRuntime 已按 web.enabled 创建 EventHub/HTTP，并将成功存储记录广播接入；Web 初始化失败执行运行时清理，标记会话 incomplete；退出关闭订阅和服务。SSE 空闲时发送健康事件。

实际命令：`& ./scripts/build.ps1 -MavenArguments @('test','-pl','loglens-runtime','-am','-Dtest=RuntimeWebTest','-Dsurefire.failIfNoSpecifiedTests=false')`。结果 BUILD SUCCESS，RuntimeWebTest 两项通过，0 失败/错误/跳过。

- 真 loopback SSE 连接收到 ready 水位 0，ERROR 入队后收到 recordSequence=1；历史 through=1 能查到同一已保存记录，cursor=1 不重复返回。
- 真实端口占用导致 BindException，初始化失败会话 complete=false；SQLite 文件能在 Windows 上改名，证明连接已关闭。

证据：`loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.RuntimeWebTest.xml`。客户端重连跨批补齐、健康推送断言、强制慢 socket、UI 与 Agent 尚待验证；T09 仍实施中。T02–T07 状态同步为实施中，不能因为已有模块测试宣称全面验收完成。

## Logback 快照适配器初步验证

EventSnapshotter 使用无缓存反射同步读取事件；不保存 Class/Method/原事件引用。参数转为有限字符串，MDC 复制；异常图设总节点数、总帧数、消息字符预算、深度及 suppressed 上限，并检测循环。caller 默认不读取。

命令：显式 MavenArguments 执行 `test -pl loglens-adapter-logback -am -Dtest=EventSnapshotterTest -Dsurefire.failIfNoSpecifiedTests=false`。BUILD SUCCESS，1 项测试通过：MDC 修改不影响快照，消息截断，参数 toString 异常隔离，caller 关闭，事件时间和线程保留。证据：`loglens-adapter-logback/target/surefire-reports/TEST-io.loglens.adapter.logback.EventSnapshotterTest.xml`。

该测试用事件替身验证复制契约；真实 Logback 版本、ThrowableProxy、Spring Boot loader、控制台接管与 Agent 包装尚待验证，不宣称 R02/R04 全面通过。

## 真实 Logback 事件验证

新增仅 test scope 的 Logback Classic 1.5.18 依赖，不进入适配器运行时产物。RealLogbackSnapshotTest 两项通过：实际 LoggingEvent 参数格式化、原模板、MDC、线程、ThrowableProxy cause/suppressed/堆栈；异常图共享总帧预算并标记截断。测试最初误用 LoggerContext 为 AutoCloseable，编译失败后修正为 finally stop，再运行 BUILD SUCCESS，0 失败/错误/跳过。

命令：`test -pl loglens-adapter-logback -am -Dtest=RealLogbackSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`，使用 scripts/build.ps1 显式参数数组。证据：`loglens-adapter-logback/target/surefire-reports/TEST-io.loglens.adapter.logback.RealLogbackSnapshotTest.xml`。这证明 1.5.18 的事件快照行为，尚不能证明 Agent 切点、多 Appender 或发布版本范围。

## 采集回调与接管资格

LogbackCapture 已连接 bootstrap Callback 与 ProcessingRuntime：范围外事件不发布；成功发布且 replace-console 才返回接管资格；输出不健康或快照失败保持原路径。每次检查有效 Logger 祖先的 Appender 图，不缓存应用对象；包装器包含指定控制台或指定目标不是直接 ConsoleAppender 时回退 observe。

LogbackCaptureTest 使用真实 Logback ConsoleAppender/AsyncAppender 验证直接控制台资格与 Async 包装回退，1 项通过。已有快照 3 项复测通过。构建使用 IDEA Maven/JDK，输出在 E 盘。证据：`loglens-adapter-logback/target/surefire-reports/TEST-io.loglens.adapter.logback.LogbackCaptureTest.xml`。尚未安装 Advice，因此此证据不证明实际控制台抑制/原文件保留；T08 保持实施中。

## Byte Buddy Advice 编译与行为验证

新增 DispatchAdvice、ConsoleAdvice 和 LogbackTransformer。分发入口/出口只调用 bootstrap CaptureBridge；AppenderBase/UnsynchronizedAppenderBase 的 doAppend 按实际 console 回调资格跳过，其他分支保留。Agent 模块编译通过。

AdviceTest 用 Byte Buddy 真实生成和加载字节码，两项通过：正常/异常分发均清理 ThreadLocal，事件采集一次；控制台分支仅在成功接管作用域内跳过，作用域外原方法执行。命令为显式参数数组 `test -pl loglens-agent -am -Dtest=AdviceTest -Dsurefire.failIfNoSpecifiedTests=false`，BUILD SUCCESS，0 失败/错误/跳过。证据：`loglens-agent/target/surefire-reports/TEST-io.loglens.agent.AdviceTest.xml`。

尚缺 premain 外壳、bootstrap 安装、隔离 runtime 打包及真实子 JVM Logback 多 Appender 验证；此阶段不宣称可发布 Agent。

## Agent 运行时启动入口

新增 AgentRuntime：配置加载 → ProcessingRuntime → Transformer → CaptureBridge → shutdown hook；退出先禁用桥，再移除 Transformer 并排空运行时。安装失败逆序清理。模块编译通过；AgentRuntimeTest 确认非法 schema 在 Instrumentation 操作和输出目录创建之前失败，1 项通过。命令：显式 MavenArguments 的 `test -pl loglens-agent -am -Dtest=AgentRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false`，BUILD SUCCESS。证据：`loglens-agent/target/surefire-reports/TEST-io.loglens.agent.AgentRuntimeTest.xml`。

入口目前为隔离加载器反射调用准备；premain 外壳/嵌套 JAR 产物仍未完成，不能按 javaagent 启动。T08 继续实施。

## premain 外壳代码

新增 JDK-only LogLensAgent：从嵌套资源提取 bootstrap/runtime 到 Agent 同目录，bootstrap search 安装桥，platform-parent URLClassLoader 加载 AgentRuntime。默认失败禁用，显式 strict 参数抛出；参数支持路径空格与逗号。LauncherTest 1 项通过，BUILD SUCCESS；证据 `loglens-agent/target/surefire-reports/TEST-io.loglens.agent.LauncherTest.xml`。

尚未构建嵌套资源布局与 Manifest，不能把外壳代码视为可运行产物；YAML STRICT 的失败传播、隔离 loader 关闭、提取目录生命周期及真实 javaagent 测试待补齐。

## 隔离 Agent 打包

Agent Maven shade 生成 runtime classifier，排除 bootstrap artifact，合并 SPI 服务资源，过滤签名与 module-info。`scripts/package-agent.py` 生成 `dist/loglens-agent.jar`：外层只有 Manifest、JDK-only launcher class、独立 bootstrap JAR、隔离 runtime JAR。

实际执行 `package -pl loglens-agent -am -DskipTests` BUILD SUCCESS，随后 Python 打包与布局断言通过：bootstrap class 不在 runtime 重复、AgentRuntime 存在、Windows SQLite DLL 保留。该步骤显式跳过测试，不计为功能测试；先前单元测试证据独立保留。产物位于 E 盘。

真实 javaagent 启动、应用依赖隔离与发布兼容性仍待验证；不能以 ZIP 布局检查替代运行验收。

## 打包 Agent 真实子 JVM 首次采集

新增无 LogLens 依赖的 LogbackDemo；用 IDEA JBR 21 启动 `-javaagent:dist/loglens-agent.jar=config=.build/agent-smoke/config.yaml`，observe 模式真实采集三条日志。captured 保存两个 INFO 和一个 ERROR，原控制台继续输出；消息/原模板/token MDC 已脱敏，异常快照保留。证据目录：`.build/agent-smoke/logs/smoke/1791784789666-7358c562-6311-4847-94ad-9f705bf39f96`。

首次手工 classpath 拼接错误导致 LoggerFactory 缺失，随后误纳入 SLF4J 1.7/2.0 两版导致 NOP；最终显式选择应用 SLF4J 2.0.17 + Logback 1.5.18 后采集成功。这是测试启动 classpath 的修正，不是放宽功能规格。缓存/临时/user.home 显式在 E 盘。

需继续把运行命令固化为可重复集成脚本，验收 replace-console、多 Appender、additivity、reset、Java 17/Spring Boot 与故障矩阵；整个项目仍未完成。

## observe/replace-console 双模式可重复验收

新增 `scripts/verify-agent.py`，真实子 JVM 使用固定 Logback 1.5.18/SLF4J 2.0.17 classpath，配置同时启用直接 ConsoleAppender 与 FileAppender。实际执行两模式均通过：每模式 captured=3（无多 Appender 重复），processed=2（repeatCount 1/2），会话 complete=true，captured/processed 无明文 private；原 FileAppender 保存两条含原 token 的日志；observe 控制台保留，replace-console 控制台抑制。退出码均 0。

证据：`.build/agent-verification/250c5f9b-f758-438b-8e60-c523eb32d440/results.json` 与两模式 stdout/stderr/session 文件。脚本有 30 秒子进程期限，所有输出及 JVM tmp/user.home 在 E 盘。该验收覆盖直接双 Appender，不证明 additivity/reset/Async 故障和 Java 17/Spring Boot 兼容性。

## 历史 viewer 入口实施

新增 ViewerMain 命令入口，复用 HistoryRepository/LocalWebServer，仅 loopback、只读历史，不启动 Agent。scratch 默认置于会话父目录，拒绝位于历史会话内；端口有范围校验。初次模块 compile BUILD SUCCESS。退出 hook 显式关闭服务与恢复仓库，仓库关闭新增幂等保护；这次生命周期修改尚待重新编译与真实 viewer 进程验证。UI/可执行打包/导出仍待完成，T11 不能标完成。

## 离线 HTML 导出实现

新增 HtmlExporter：keyset 分页读取 FINAL 记录，最多 10000 条/64 MiB，CREATE_NEW 避免覆盖旧报告；自包含样式与脚本，DOM 每次最多呈现 100 条。日志用 textContent，JSON 中 `< > &` 与行分隔符安全编码防止 script 逃逸。HtmlExporterTest 编码边界测试通过（BUILD SUCCESS，1 项）；后续把累计字节统计改为增量避免反复复制数据，该小改动待重新测试。尚需 CLI 接线、实际历史导出、浏览器安全/布局验证和大小边界测试，不能标 R18 完成。

## 历史导出文件验证

ViewerMain 接入 `export <session-directory> <output.html> <scratch-directory>`；拒绝导出目标/缓存位于原会话内。HtmlExporterTest 两项通过（含增量字节统计复测）：实际存储与恢复历史导出受 maxRecords 限制，恶意 `</script><img...>` 安全编码，输出使用 textContent，既有报告拒绝覆盖，超出总字节限制不创建文件。BUILD SUCCESS，0 失败/错误/跳过；证据 `loglens-viewer/target/surefire-reports/TEST-io.loglens.viewer.HtmlExporterTest.xml`。真实浏览器和可执行 viewer 产物仍待验证。

## 可执行 viewer 打包与实际导出

Maven shade 为 viewer 合并服务资源并写入 Main-Class，生成 `dist/loglens-viewer.jar`。实际 package BUILD SUCCESS（本次打包跳过测试，既有 HtmlExporterTest 证据独立记录）。IDEA JBR 21 通过 `java -jar dist/loglens-viewer.jar export ...` 读取真实 Agent 历史，成功生成 `.build/viewer-report-c3f56ebd-6e43-4d8c-bb93-0c72202ce369.html`；tmp/user.home/native/cache 均显式位于 E 盘。UI 与浏览器检查、历史 HTTP 进程验证尚未完成。

## 外部 SPI 示例产物

新增 custom-plugin 的 OrderTagFactory（example-order-tag）与 custom-chain-plugin 的 OrderChainProvider，以及对应 META-INF/services 和示例说明。API provided 依赖。实际 package 双模块 BUILD SUCCESS；IDEA JBR 不提供 jar.exe，改用 Python ZipFile 检查，两个 JAR 有正确服务注册与实现类，没有 io/loglens/api class。本次构建跳过测试；示例插件真实外部 loader/Agent 子 JVM 运行仍待验证，不能据此标 R07/R21 完成。

## 整链插件真实 Agent 运行

外部 plugins 目录只放 custom-chain-plugin JAR，用打包 Agent 与普通 LogbackDemo 子 JVM 启动。实际成功：三条 FINAL（示例链没有 RepeatFold，区别于默认两条输出），首条 fields.orderId=O-1，输出不包含 private；证明 ServiceLoader 外部整链替换及外围脱敏/存储仍运行。退出码 0。

证据目录：`.build/plugin-verification/b14d83cf-3c0f-42f6-8c44-c5318673714e/logs/plugin-check/1791785287661-601369f2-e347-4dbe-ae27-96d71b1ec4d8`。实际 assertions 检查记录数、业务提取与敏感文本。节点工厂示例真实替换、Spring Boot loader 及插件故障仍待验证。

## 已解析 YAML STRICT 传播

AgentRuntime 对已加载配置的 STRICT 初始化失败抛出专用异常；JDK-only launcher 通过异常类名识别，不共享运行时 API 类型，解包 InvocationTargetException 后决定终止。AgentRuntimeTest 两项通过：非法 schema 在增强前失败，STRICT + 损坏插件 JAR 传播专用异常。BUILD SUCCESS，证据 `loglens-agent/target/surefire-reports/TEST-io.loglens.agent.AgentRuntimeTest.xml`。

配置文件本身无法解析时需显式 strict=true 参数；此情况的 YAML 策略识别仍需设计与验收核对。新源代码尚未重打 dist Agent，真实 STRICT 子 JVM 终止证据待补齐。

## 内嵌 UI 初始实现

新增 ui/index.html、style.css、app.js，顶栏/等级与关联筛选/固定行高虚拟列表/详情/来源/深浅主题/URL 同步/健康轮询初步接入。列表最多保留 5000 条，日志按 textContent 呈现；Web 静态资源固定白名单，设置 CSP。node --check 通过，Web reactor compile BUILD SUCCESS。

当前尚未实现实时 SSE 合并与暂停期间新日志计数、关注镜头、详情栈交互和完整历史分页体验，未做浏览器视觉/行为测试，dist JAR 尚未重打 UI；R17/T10 不能标完成。

## UI 实时接入实施

app.js 接入 EventSource ready 水位 → through 水位历史分批查询 → 实时缓冲合并；recordSequence 去重，gap/重连按最近序号补齐，客户端 buffer 上限 5000。暂停保持接收并显示新日志数量，恢复跟随跳到尾部；筛选变更重建订阅并取消旧历史请求结果。SSE 健康事件映射状态栏。node --check 通过；尚未执行浏览器行为测试，不能把代码与语法检查作为无遗漏/无重复验收证据。关注镜头/详情交互/分页及完整 R17 仍待完成。

## UI 详情与来源分页

详情增加订单关注按钮（写入临时 URL/表单筛选，不改 YAML）、可折叠异常与逐页重复明细，来源游标每页最多 100 条，切换选中记录后丢弃旧请求结果，不保留无界来源数组。JS 语法通过，LocalWebServerTest 复测 BUILD SUCCESS（1 项）；该 HTTP 测试不覆盖浏览器 UI 交互，浏览器行为/视觉验收仍待完成。

## Spring Boot 嵌套 JAR 初次验证

新增 BootDemo 与 Spring Boot 3.4.4 repackage，package BUILD SUCCESS（跳过测试）。真实 IDEA JBR 21 通过 `-javaagent:dist/loglens-agent.jar ... -jar examples/springboot-demo/target/springboot-demo-0.1.0-SNAPSHOT.jar` 退出码 0，业务原日志运行。随后 captured 的 BOOT-1/BOOT-2 数量断言失败；尚不能证明 Boot nested loader 采集成功，需继续诊断，不能宣称兼容。Boot 外部插件、replace/reset 与发布矩阵均待验证。

## Spring Boot 失败断言复核与外部插件验证

核对明确 sessionId `1791785812292-7918e48d-1c68-4976-ade1-2077541bc972`：captured 实际包含 BOOT-1/BOOT-2，脱敏且 complete=true。先前按 LastWriteTime 选择目录误读旧会话，属于验收定位错误。改为每次独立 UUID 证据目录，不再猜测“最新会话”。

## 正常丢弃与暂存计数

先更新设计再实现：正常 process 返回但未调用 continuation 的事件计入 processorDropped 并诊断；节点显式 BUFFER 则为暂存，RepeatFold 增加 BUFFER 决策。ChainRunnerTest 七项通过，新增断言静默正常丢弃计数为 1，窗口暂存计数为 0。BUILD SUCCESS，证据 `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.ChainRunnerTest.xml`。插件决策持久化解释的完整流程仍待最终审计。

## 异常帧格式修正

真实 Agent 输出显示 StackTraceElementProxy.toString 已含 `at `，文本渲染又添加前缀。快照改为反射读取 getStackTraceElement，统一存 JDK StackTraceElement 字符串，避免重复 at。RealLogbackSnapshotTest 两项复测通过，新增断言帧不含 at 前缀；BUILD SUCCESS，证据同名 surefire XML。dist Agent 尚需重打才能包含修正。

## 最新产物整合复验

实际 package Agent/viewer reactor BUILD SUCCESS，重新运行 package-agent.py 并复制 viewer 到 dist；包含当前 UI、STRICT 传播、DROP/BUFFER 与异常帧修正。打包显式 skipTests，随后实际运行两个验收脚本均通过：

- 普通 Java observe/replace-console、原文件保留、一次采集、脱敏与正常退出：`.build/agent-verification/c9d85b85-20e6-43f2-a566-524665556ace/results.json`。
- Boot nested JAR + 外部整链字段提取/脱敏/正常退出：`.build/boot-verification/202950f5-f8cc-49d7-b7f8-7bffd2874460/results.json`。

当前 dist 与这些源码变更同步，但 UI 浏览器、Java 17、性能/故障/最终审计仍未完成，不能标项目完成。

## 开发与运行说明同步

README 从“无运行代码”更新为实际阶段状态，加入 IDEA/E 盘构建、Agent 接入、viewer/导出、可重复验证、SDD 文档入口及未完成验收限制。T10–T13/T15 同步为实施中，未将阶段证据等同全面完成。文档改动通过 git diff --check；全部 verify 命令和 Java 17/UI/性能门禁仍需实际执行，不因文档列出命令而视为已通过。

## 完整 reactor verify

实际执行 `& ./scripts/build.ps1 -MavenArguments @('verify')`，2026-10-12 14:25:58 BUILD SUCCESS，全部模块与示例构建通过，总耗时 1:18。运行中未 skipTests；证据为各模块 target/surefire-reports/TEST-*.xml。integration-tests 模块当前没有端到端测试源码，其 SUCCESS 只证明空模块构建，真实子 JVM 证据仍来自独立脚本。

未将绿色 reactor 视为最终完成：Java 17、浏览器、十万行/三模式性能、完整可靠性矩阵与逐需求审计仍待完成。另检查 D:/java 为 Java 11，不能用于 Java 17 验收。

## 框架级别与继承分发验证

LogbackDemo 增加 DEBUG 探针，verify-agent 明确断言不存在该探针。使用 root INFO + Console/File 两个 Appender，业务 com.example 子 Logger 默认 additivity=true；observe/replace-console 两模式均 captured=3、无重复且不采集关闭的 DEBUG。实际编译与脚本通过，证据 `.build/agent-verification/6c6bd98b-9ecc-4b2a-95d9-0db520db7e12/results.json` 及记录文件。复杂多级重复 attachment/reset/TurboFilter 场景仍待补齐。

## Logback reset/reconfigure 验收

LogbackDemo 可选 demo.reset，在业务事件之后执行 LoggerContext.reset 和 Joran 重载原配置，再发 WARN。verify-agent 两模式实际通过：captured=4、processed=3、repeatCount=[1,1,2]；after-reset-probe 已采集，重建原 FileAppender 保存该事件；重建 ConsoleAppender 在 observe 输出、replace-console 抑制；DEBUG 仍关闭，敏感内容保存脱敏。

证据：`.build/agent-verification/937a441d-c6c2-4aa7-9c33-fe43e63462c9/results.json` 与原文件/stdout。此测试覆盖同配置 reset 重建，不推断任意动态框架或复杂包装关系。

## 插件来源引用外围脱敏

审查发现 output sourceRefs 原样传递。现对分组键/值及样本 ID 执行外围规则与字符/数量限制，保持输入副本不变；避免插件输出敏感文本绕过保护。RedactorTest 1 项通过，BUILD SUCCESS；证据 `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.RedactorTest.xml`。分组索引一致性与复杂插件来源约束仍需最终审计，dist 待重打。

## 会话存储片段元数据

session 增加当前存在的日志/JSONL segments 清单（file/bytes/active），结束会话无 active 片段。SessionStorageTest 四项通过，包含 session.json captured/processed 清单断言，BUILD SUCCESS。随后补充 endedAt 条件保证结束清单 active=false，该小改动待下次复测；dist 待重打。目录片段数量的长期上限与保留清理仍需审计，不据此标 R13/R14 全部完成。

## 不完整历史 UI 实施

历史 UI 增加未完整结束提示及 captured 明细模式，使用 /api/captured 游标每页 100 条，沿用有界虚拟列表；明细明确标为 CAPTURED_ONLY，不生成 FINAL/汇总，不显示汇总来源按钮。明细模式关闭处理结果筛选，避免误示筛选语义。node --check 通过；真实浏览器、强杀历史、分页切换行为尚待测试，dist 待重打。

## 历史文本重导出

新增 TextExporter 和 viewer export-text 命令，逐批 500 条 FINAL 流式写入新文件，不更改历史。首次测试发现 Windows newLine 与原 segment LF 不一致，改为固定 LF；TextExporterTest 随后通过，恢复导出与原 combined 文本一致且拒绝覆盖。BUILD SUCCESS，证据 `loglens-viewer/target/surefire-reports/TEST-io.loglens.viewer.TextExporterTest.xml`。大历史/故障与实际 CLI 待验证，dist 待重打。

## 真实浏览器 UI 初步验证

Playwright 仅安装到 `.build/python-tools`，使用现有 Chrome，profile/tmp/截图均在 E 盘。verify-ui.py 真实浏览器 + 确定性 HTTP fixture 通过：日志按文本展示（恶意 img 无 DOM 注入）、记录详情/订单按钮/异常区可见、主题切换、不完整提示、无 pageerror。证据 `.build/ui-verification/4ed891cb-f6da-4204-85e9-d8980cd963cf/results.json` 与 light.png。

安装由提升工具写入，沙箱读取包权限受限，浏览器验收使用同样提升执行成功。此 smoke 不覆盖真实后端、SSE/暂停、来源分页或十万行；截图尚需视觉查看，不能标 R17 全部完成。

新增 scripts/verify-boot.py，实际执行通过：Spring Boot 3.4.4 nested JAR + 外部整链插件加载、两个订单字段提取、captured/processed 无 private、会话完整结束。证据 `.build/boot-verification/2686f8fe-58d5-4475-8e49-c84224891e04/results.json` 及 stdout/stderr/session。此证据仅覆盖 JBR 21 和该组合；Java 17、其他版本及 reset 等仍待验证。

## 内嵌 UI HTTP 资源验证

扩展 LocalWebServerTest 并实际通过：GET / 返回日志工作台 HTML；CSP 含 script-src self；/app.js 返回正确 JavaScript MIME，使用 textContent 且无 innerHTML；/AGENTS.md 返回 404，不暴露仓库文件。BUILD SUCCESS，0 失败/错误/跳过。证据 `loglens-web/target/surefire-reports/TEST-io.loglens.web.LocalWebServerTest.xml`。这证明资源服务和响应策略，不证明浏览器脚本执行、可访问性或布局。

## Chrome 历史 UI 分页与采集明细交互

扩展 `scripts/verify-ui.py` 的确定性 HTTP fixture，使用本机 Chrome 实际执行 UI：101 条 FINAL 记录和 101 条 CAPTURED_ONLY 记录各自跨两页；筛选只保留 ERROR；切换采集明细禁用结果筛选；采集记录详情标记 CAPTURED_ONLY 且不显示订单镜头或重复来源；恶意 img 文本没有进入 DOM；主题和异常详情可见；无 pageerror。首轮浏览器实测发现模式切换保留旧 FINAL 详情，修复为切换时清除所选详情与异步来源请求后，完整脚本通过。证据 `.build/ui-verification/d6269260-c4fa-42f0-beec-d13dd5e6e440/results.json` 和 `light.png`。该结果不覆盖 live SSE 水位并发、暂停恢复、键盘可访问性或其他浏览器；R17 仍未完成。

## Chrome live SSE 与暂停接收交互复测

在同一浏览器脚本加入可控 EventSource：ready 水位为 1，先补齐该水位历史，再送入序号 102 的实时记录；点击暂停后继续收到记录并增加新日志计数，恢复后计数清零。与两种历史视图的分页、筛选及 captured-only 详情一起运行通过，无浏览器 pageerror。证据 `.build/ui-verification/eaeb06c2-314e-48bf-916f-59871bbc5239/results.json`。EventSource 使用浏览器内确定性替身，不代表真实网络慢客户端或 EventHub 并发压力；R17 仍未完成。

## Java 17 packaged-agent 与 Spring Boot 验收

打包脚本新增 `LOGLENS_JAVA` 覆盖项，默认仍使用 IDEA JBR。通过 Corretto 17.0.14 实际启动普通 Logback Java 程序和 Spring Boot 3.4.4 nested JAR；observe/replace-console 均 captured=4、processed=3、complete=true，原文件与控制台行为断言通过；Boot captured/processed=6、两个订单事件及外部整链插件断言通过。显式将 `java.io.tmpdir`、`user.home`、`org.sqlite.tmpdir`、TMP/TEMP 指向 E 盘。证据 `.build/agent-verification/b5e9ccd4-9155-480b-80f0-27641b68ef84/results.json`、`.build/boot-verification/86d1581f-7f97-4489-8ef9-1118dcea43c8/results.json`。这只覆盖该 Java 17 发行版和既定 Logback/Boot 版本组合。

## 最新完整 reactor 与发布物

`& ./scripts/build.ps1 -MavenArguments @('verify')` 在 IDEA JBR 21 上 BUILD SUCCESS：17 个 reactor 模块、21 个 Surefire XML 套件、62 项测试，0 失败、0 错误、0 跳过，耗时 1:24。随后重新打包 `dist/loglens-agent.jar` 和 `dist/loglens-viewer.jar`；打包阶段明确 `-DskipTests`，其测试证据来自前述完整 verify。真实 Java 17/21 子进程脚本均通过。integration-tests 模块仍为空，因此 17 模块 BUILD 不代表端到端覆盖完整；性能和故障矩阵也未完成。

## maxDisk 删除关闭片段

为 1 MiB 结构化存储上限写入多条大事件，产生多个 captured JSONL 片段；实测 `cleanup()` 删除最旧的关闭片段、移除其 captured 索引记录，同时保留当前写入片段。`SessionStorageTest` 定向运行 5 项通过，0 失败/错误/跳过。证据 `loglens-storage/target/surefire-reports/TEST-io.loglens.storage.SessionStorageTest.xml`。当前只验证 maxDisk 路径，未验证 retention 天数、索引删除失败、跨会话整体磁盘配额或十万行分页。

## 非法 YAML 的 STRICT 子 JVM

扩展 `scripts/verify-agent.py`：向打包 Agent 传入语法损坏的 YAML 和 `strict=true`，确认应用 main 未运行、JVM 非零退出且 stderr 含 strict initialization failed。JBR 21 与 Corretto 17.0.14 均在普通应用 Agent 验收中通过该断言。证据 `.build/agent-verification/83d0e4da-1e5a-4b4d-8054-0b6540b51e88/strict-invalid-config/`、`.build/agent-verification/736f715c-add5-4665-aca2-85ebeb48b0fc/strict-invalid-config/`。默认非 strict 的解析失败回退及后续重启对照仍未覆盖。

## 本轮新增存储验收后的全量复验

包含固定种子性能演示、十万条历史恢复和 AsyncAppender 子 JVM 场景后再次执行 IDEA JBR 21 的完整 `verify`：18 个模块全部 SUCCESS，22 个 Surefire 套件共 73 项测试，0 失败、0 错误、0 跳过，耗时 2:52，结束于 2026-10-13 14:13:21 +08:00。十万条恢复测试三次耗时 63.62 秒 (full 100-page traversal)。之后统一发布脚本 BUILD SUCCESS，并重新生成和验证 Agent、Viewer、API 三个 JAR。`node --check`、Python 验收脚本的 `py_compile` 与 `git diff --check` 通过。integration-tests 模块仍为空，集成行为由单独真实子 JVM 和浏览器证据覆盖。

## 统一发布产物脚本

新增 `scripts/package-release.ps1`，调用受工作区约束的 Maven 脚本打包 reactor，并生成 `dist/loglens-agent.jar`、`dist/loglens-viewer.jar`、`dist/loglens-api.jar`；`verify-release.py` 检查 premain/Main-Class manifest 与 API 类。首次执行发现 `-DskipTests` 通过子 PowerShell 转发时被解释为脚本参数，因此改为在同一 PowerShell 进程以参数数组调用支持返回状态码的 build.ps1；随后脚本完整 BUILD SUCCESS，三个产物检查通过。输出大小分别为 24,170,576、17,107,105 和 28,152 bytes。打包有意跳过测试，需先单独执行完整 `verify`。

## 100,000 条历史恢复与 keyset 边界分页

新增 `HistoryScaleTest`，写入 100,000 条 processed JSONL 记录，实际运行 `HistoryRepository` 重建索引；验证水位 100,000、首尾各 1,000 条 keyset 页和最后一条内容，并确认缺失 session 元数据的恢复会话仍标为 incomplete。JBR 21.0.6 下测试通过，用时 38.91 秒，0 失败/错误/跳过。摘要证据 [history-scale.json](evidence/history-scale.json)，完整定向 Maven XML 在 `loglens-storage/target/surefire-reports/TEST-io.loglens.storage.HistoryScaleTest.xml`。该测试未遍历中间全部 98 页，也未测超过 100,000 条或同时 HTTP 查询时的内存。

## 外部 SPI 节点工厂真实 Agent 加载

新增 `scripts/verify-plugin-factory.py`，只把 `custom-plugin` JAR 放入 Agent 插件目录，配置内置字段提取后接外部 `example-order-tag` provider。JBR 21 与 Corretto 17 子 JVM 均 captured/processed=3，O-1/O-2 两条记录带外部标签，第三条错误日志不带标签；敏感 token 未从 captured/processed 泄漏，会话完整结束。摘要证据 [plugin-factory.json](evidence/plugin-factory.json)，本轮各次运行 stdout/stderr/session 保存在 `.build/plugin-factory-verification/`。

## 多整链 provider 冲突与 STRICT

新增 `duplicate-chain-plugin`，与 `custom-chain-plugin` 提供不同 provider 类。JBR 21 和 Corretto 17 真实子 JVM 均验证：默认模式在 stderr 给出 `multiple LogChainProviders` 和两个 provider 名，Agent 初始化失败后主程序仍启动并输出原业务日志；`strict=true` 时退出码 1 且应用 main 未启动。摘要证据 [plugin-conflict.json](evidence/plugin-conflict.json)，原始 stdout/stderr 在 `.build/plugin-conflict-verification/`。该场景验证整链数冲突；重复 API/providerId 仍以 Registry 单测为证据。

## Logback TurboFilter 与多级 additivity 子 JVM

扩展 Logback 演示与 `verify-agent.py`：TurboFilter 拒绝 marker 事件；`com.example` 子 Logger 附加独立 FileAppender 并 additivity 到 root Console/File；随后执行 LoggerContext reset 并重载相同配置。JBR 21 和 Corretto 17 的 observe/replace-console 都 captured=4、processed=3，TurboFilter 事件不进入捕获或文件，子级/root 文件均保留两条订单日志且无重复采集，reset 后行为保持。另用真实 AsyncAppender 包装 ConsoleAppender，在 replace-console 下验证诊断 `UNSUPPORTED_CONSOLE_WRAPPER`、captured=3、控制台与原文件输出均保留。摘要证据 [logback-dispatch.json](evidence/logback-dispatch.json)，完整输出和文件位于 `.build/agent-verification/88648c3b-84ee-4b61-89e3-8b2d5c3ce99d/`、`.build/agent-verification/4c364e52-d83b-4961-b8f4-bfb975d48773/`、`.build/agent-verification/39106521-9291-4982-8271-40b3c2c5f6f5/` 与 `.build/agent-verification/77fcca30-377d-4c8b-8192-7e1daee12e4f/`。这仍不覆盖其他 Logback 版本、非 additive 多层组合及独立单事件满缓冲场景。

## 固定种子 100,000 条三模式性能实测

使用 IDEA JBR 21.0.6、Windows 10 build 26200、20 个逻辑处理器、seed=20261012、64 个 logger bucket、相同 Logback Console/File 配置和 8,192 槽 DROP_NEWEST 缓冲。程序逐次测量 Logger 调用延迟，采样进程 CPU/堆与 GC，外部记录总墙钟和输出字节；脚本核对所有模式原 FileAppender 均恰有 100,000 条，replace-console 的 fallback 行数等于丢弃计数，captured + dropped = 输入数。

| 模式 | Logger 调用吞吐 | p50 / p95 / p99 | 进程 CPU | GC 次数/毫秒 | 采样峰值堆 | captured / dropped | File 输出 | LogLens 会话数据 | 总墙钟 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 191,530–198,103/s | 3.9 / 7.8–8.1 / 18.1–19.1 µs | 1.28–1.36 s | 3 / 6–7 | 79.1 MB | 0 / 0 | 3,938,079 B | 0 B | 0.84–0.89 s |
| observe | 32,615–94,378/s | 6.8–21.7 / 23.4–63 / 68.4–162.3 µs | 5.73–12.63 s | 9 / 23–38 | 84.1–85.9 MB | 8,662–11,435 / 88,565–91,338 | 3,938,079 B | 10,603,090–14,001,011 B | 10.83–16.04 s |
| replace-console | 19,071–53,168/s | 12–38.9 / 44–87.9 / 91.8–211.1 µs | 10.70–20.67 s | 18 / 40–86 | 84.1–86.1 MB | 15,620–16,512 / 83,488–84,380 | 3,938,079 B | 20,087,816–21,206,271 B | 12.11–19.07 s |

replace-console 三次运行将 83,488–84,380 条未入队事件退回原 ConsoleAppender；三个模式的原 FileAppender 都完整。测试后端继续处理已接收队列，故 Agent 模式墙钟明显高于 Logger 调用循环时间。数据表明默认 8,192 槽位不适合此突发强度：高丢弃是实际测量结果，不能解读为无损性能。observe 吞吐约 2.9 倍波动、replace-console 约 2.8 倍波动，说明这台主机的实验受 JIT/系统调度/消费者排空时间影响，不能据此制定产品性能承诺。CPU/GC/堆为 JVM 管理接口采样，LogLens 会话数据不含单独列出的 SQLite native DLL。三组原始运行指标保存在 [performance-run-1.json](evidence/performance-run-1.json)、[performance-run-2.json](evidence/performance-run-2.json)、[performance-run-3.json](evidence/performance-run-3.json)；完整控制台和会话文件在 `.build/performance/` 对应 UUID 目录。还需控制 JIT 与系统负载、增加环境和 Java 版本样本；R22 未完成最终验收。

## MULTI Disruptor multi-producer and STOP drain

Added `ProcessingRuntimeTest.multiProducerPublishesAreDrainedBeforeStop`: four real concurrent threads each publish 1,000 events with ringBufferSize=8192. All 4,000 were accepted and persisted in captured JSONL; STOP drained the queue and marked the session complete. Targeted JBR 21 run passed 2 tests with zero failures/errors/skips in 22.565 seconds. BLOCK saturation and shutdown with in-flight producers remain uncovered.

## Full traversal of 100,000 historical records

Extended `HistoryScaleTest` to traverse all 100 pages of 1,000 records after rebuilding the index from 100,000 JSONL rows. It verifies every recordSequence is continuous and checks cursor, hasMore, final row, and incomplete metadata. Targeted JBR 21 run passed with zero failures/errors/skips in 56.04 seconds. See [history-scale.json](evidence/history-scale.json).

## Retention by age and historical text export

Added `SessionStorageTest.retentionDaysDeletesExpiredClosedSegmentsAndTheirIndex` with a controlled clock: expired closed segments and captured index rows are removed while the active segment remains. Targeted JBR 21 run passed all 6 SessionStorageTest cases. `TextExporterTest` verifies re-exported output matches the original app log and refuses to overwrite an existing file.


## Plugin-derived source-group redaction

The runtime now canonicalizes source group names and values through the same redactor before writing the semantic groups JSONL and SQLite index, and applies that canonicalization again to persisted FINAL SourceRefs. Explicit sensitive field names use configured-key masking; other values pass through configured message rules. `ProcessingRuntimeTest.pluginDerivedSourceGroupsAreRedactedAndStillQueryable` loads a real ServiceLoader plugin that creates sentinel private fields, publishes and closes a session, reopens it with the read-only history repository, verifies source lookup still returns the captured row, and scans every session file to prove neither raw sentinel reached disk. Targeted JBR 21 verification: 3 ProcessingRuntimeTest cases passed, zero failures/errors/skips. The test report is `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.ProcessingRuntimeTest.xml`.

## BLOCK saturation and shutdown with an in-flight publisher

Added `ProcessingRuntimeTest.blockPublisherLeavesDuringShutdownAndAcceptedBatchDrains`. A real test plugin holds the single consumer while a 1,024-slot ring fills under BLOCK; a further producer is confirmed waiting. Shutdown stops acceptance, the waiting publisher returns false, the plugin is released, and all 1,024 previously accepted events drain before STOP with session complete=true. Targeted JBR 21 run: 4 ProcessingRuntimeTest cases passed, zero failures/errors/skips. This closes the first-version R06 BLOCK/concurrent-stop scenario; timeout/forced-kill failure behavior remains audited under R19/R20. Evidence: `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.ProcessingRuntimeTest.xml`.

## Bounded shutdown timeout and deferred cleanup

Added `ProcessingRuntimeTest.stuckPluginTimesOutAsIncompleteAndIsClosedAfterItReturns`. A real ServiceLoader plugin blocks the single consumer; with `shutdownTimeoutMillis=300`, `close()` returns within the bounded test limit and persists `complete=false` / `INCOMPLETE`. After releasing the plugin, the test waits for deferred plugin close and verifies the entire session directory can be moved, proving SQLite and all file handles have been released before temporary-directory teardown. Targeted JBR 21 run: all 5 ProcessingRuntimeTest cases passed, zero failures/errors/skips. Forced process kill and reverse-order cleanup at every startup failure phase remain unverified. Evidence: `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.ProcessingRuntimeTest.xml`.

## Session segment manifest accuracy

Extended `SessionStorageTest.utf8OffsetsKeysetFiltersAndExactLevelFiles` to inspect the live session manifest and the final persisted manifest. Every listed file has an exact byte count; the current segment is active while the session is open, and no segment remains active after finish. Targeted JBR 21 SessionStorageTest run passed all 7 cases, zero failures/errors/skips.

## Expired group associations with retained captured detail

Added `SessionStorageTest.expiredGroupSegmentRemovesSourceAssociationsWhileCaptureRemains`. Large semantic-association rows rotate the groups log independently; after advancing a controlled clock, retention removes the expired groups segment and its SQLite associations while the matching captured segment is still within retention. Querying a FINAL record then returns no source rows and `expired=true`, while the captured source row remains available. Targeted JBR 21 SessionStorageTest run passed all 7 cases, zero failures/errors/skips.

## HTTP SSE client capacity and disconnect recovery

Added `LocalWebServerTest.sseClientLimitRejectsExtraStreamAndReleasesSlotAfterDisconnect`: a real loopback HTTP SSE stream reaches its ready watermark, a second stream is rejected with 503 at maxClients=1, closing the first body releases the EventHub subscription, and a replacement stream is accepted. Targeted JBR 21 LocalWebServerTest run passed both cases with zero failures/errors/skips. This proves SSE client-slot bounds and ordinary disconnect cleanup; a TCP socket that stops reading under sustained large event output remains a separate R16 test.

## HTTP record query watermark and filter semantics

Extended `LocalWebServerTest.recordsApiHonorsInclusiveWatermarkAndExactLevelFilter` using real loopback requests over three persisted records. `through=2` is inclusive and excludes sequence 3, `level=ERROR` matches only the exact ERROR row, and `/api/captured` preserves keyset cursor/hasMore semantics. Targeted JBR 21 LocalWebServerTest run passed all 3 cases with zero failures/errors/skips. HTTP executor saturation/rejection remains untested.

## Real non-reading TCP SSE client isolation

Added `LocalWebServerTest.slowTcpSseClientIsDroppedWithoutBlockingPublisherOrHealthyClient`. It opens two raw loopback TCP SSE sockets, reads the healthy socket, and leaves the slow socket unread with a 1KiB receive buffer. The test publishes 500 records carrying 32KiB messages through a 16-entry EventHub queue; the slow subscription is removed on overflow and the healthy stream continues receiving. Records are paced 5 ms apart so the healthy socket can drain; cumulative time inside the 500 EventHub publish calls must remain under one second, excluding that deliberate pacing. The test decodes actual HTTP chunked transfer framing for the healthy stream. The final full JBR 21 verify passed this case. Browser history/SSE cross-batch reconnect deduplication remains separate. Evidence: [slow-sse-socket.json](evidence/slow-sse-socket.json) and `loglens-web/target/surefire-reports/TEST-io.loglens.web.LocalWebServerTest.xml`.

## Reverse cleanup after web-stage initialization failure

Extended `RuntimeWebTest.portConflictFailsInitializationAndClosesRuntimeResources` with a real ServiceLoader node whose close callback is observed. After HTTP bind failure, the test verifies the plugin closes, session metadata is persisted incomplete, and the entire session directory can be relocated on Windows, proving SQLite and segment handles are released. Targeted JBR 21 RuntimeWebTest run passed both cases with zero failures/errors/skips. Earlier plugin/storage initialization stages and forced process termination remain separate R19 gaps.

## Final reactor verification for this implementation pass

After the real non-reading TCP SSE test and web-stage startup cleanup test were added, `scripts/build.ps1 -MavenArguments verify` completed on IDEA JBR 21.0.6: all 18 reactor modules succeeded; 22 Surefire reports recorded 73 tests, zero failures, zero errors, and zero skips. Maven reported 2:52 elapsed and finished at 2026-10-13 14:18:48 +08:00. The 100,000-record history rebuild and 100-page traversal took 63.864 seconds in this run. Evidence: the module Surefire reports, including `loglens-storage/target/surefire-reports/TEST-io.loglens.storage.HistoryScaleTest.xml` and `loglens-web/target/surefire-reports/TEST-io.loglens.web.LocalWebServerTest.xml`.

## Keyboard access for virtual log rows

The R17 specification/design now require keyboard focus and activation for core UI operations (D08 records the rationale). Virtual rows expose button role and an accessible name, Enter/Space open details, and `:focus-visible` outlines rows and form controls. `node --check` and a full JBR 21 reactor verify passed after this change: 18 modules, 22 reports, 73 tests, zero failures/errors/skips; the 100,000-row traversal took 62.281 s and Maven finished at 2026-10-13 14:28:14 +08:00. The expanded real-Chrome keyboard assertions are present in `scripts/verify-ui.py` but could not be executed: the normal sandbox could not import `playwright.sync_api` from the ACL-restricted `.build/python-tools`; two escalated browser-run requests timed out during automatic approval review. Therefore the keyboard browser assertion remains unverified; do not mark R17/T10 complete. Static checks `node --check`, Python compile, and `git diff --check` passed.

A no-dependency Chrome DevTools Protocol fallback was also tried with workspace-only profile and temporary directories. Chrome opened the target page and accepted the WebSocket, but its page target did not answer `Page.enable`; this was not accepted as browser evidence. Logs are under `.build/ui-keyboard/` for the runs on 2026-10-13 around 15:17. The Playwright ACL/browser gate remains open.

Rebuilt release artifacts after the UI resource change with `scripts/package-release.ps1` on IDEA JBR 21. Maven package succeeded at 2026-10-13 14:51:48 +08:00 and `scripts/verify-release.py` passed. Verified outputs: Agent 24,171,913 bytes, Viewer 17,107,186 bytes, API 28,152 bytes. Package phase uses `-DskipTests`; test evidence is the full verify above.

## Processor rule-boundary coverage

Expanded `ProcessorTest` with four cases for named-group extraction and optional nonmatches plus ordered MDC override; original-template preference and sequential normalizations; bounded highlight FIFO eviction, exception/keyword behavior, and disabling first-occurrence tracking; and zero-sample aggregation with complete source sequence range. `& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-processors','-am','test')` passed on IDEA JBR 21.0.6: all 12 ProcessorTest cases passed, zero failures/errors/skips; finished 2026-10-13 15:20:05 +08:00. Evidence: `loglens-processors/target/surefire-reports/TEST-io.loglens.processors.ProcessorTest.xml`. Broader default-chain and configuration boundary acceptance remains open under R09.

## Reactor verification after SDD rule and SSE test updates

The 2026-10-13 15:23 full verify initially failed only `slowTcpSseClientIsDroppedWithoutBlockingPublisherOrHealthyClient`: the 1 ms producer cadence overflowed both the unread and nominally healthy socket queues. The test now paces large records at 5 ms so the healthy reader remains active, and measures only cumulative `EventHub.publish` calls against a 1,000 ms bound (excluding deliberate pacing). A targeted JBR 21 web run passed all four `LocalWebServerTest` cases. The subsequent complete `scripts/build.ps1 -MavenArguments verify` passed all 18 modules and 22 reports: 77 tests, zero failures/errors/skips, 3:01 elapsed, finished 2026-10-13 15:33:46 +08:00. History rebuild/100-page traversal took 66.353 s; the slow TCP test method took 5.155 s. Evidence: module Surefire XML, [slow-sse-socket.json](evidence/slow-sse-socket.json), and [history-scale.json](evidence/history-scale.json). This JBR 21 pass does not resolve Java 17 test-matrix coverage or the keyboard-browser execution gap.

## Reverse cleanup when storage initialization fails

`ProcessingRuntime` now closes assembled nodes in reverse order and always closes the plugin registry when `SessionStorage` construction fails; cleanup failures are attached to the original startup exception rather than replacing it. Plugin-registry close failures are likewise suppressed onto a chain assembly failure. `RuntimeWebTest.storageInitializationFailureClosesEveryPluginAndPreservesStartupFailure` uses a real ServiceLoader JAR and an output parent that is a file to force session-directory creation failure. The plugin node's close callback deliberately throws; the test verifies the original `IOException` remains primary, the close error is suppressed, and the plugin JAR can be renamed after failure (classloader released). Targeted JBR 21 run passed all 3 RuntimeWebTest cases, zero failures/errors/skips; BUILD SUCCESS at 2026-10-13 15:39:44 +08:00. Evidence: [storage-init-cleanup.json](evidence/storage-init-cleanup.json) and `loglens-runtime/target/surefire-reports/TEST-io.loglens.runtime.RuntimeWebTest.xml`. This covers the storage startup phase; other constructor phases and forced process termination remain open under R19/R20.

The full reactor was rerun after this runtime change: IDEA JBR 21.0.6 `verify` passed all 18 modules and 22 Surefire reports, 78 tests, zero failures/errors/skips, 3:06 elapsed, finished 2026-10-13 15:45:31 +08:00. History rebuild/100-page traversal took 70.045 s. `scripts/package-release.ps1` then rebuilt the embedded Agent runtime and Viewer/API artifacts and `scripts/verify-release.py` passed at 15:46:32 +08:00: Agent 24,172,012 bytes, Viewer 17,107,186 bytes, API 28,152 bytes. Package build skips tests; the preceding full verify supplies the test evidence.

## SQLite failure after authoritative segment append

Added two real SQLite trigger-injection cases. One aborts the captured-index insert after `captured-*.jsonl` append. The other lets SQLite insert the processed record, then aborts the `tags` insert inside the same transaction after `processed-*.jsonl` append; `SqliteIndex` rolls the record row back. Both paths mark output unhealthy and leave the session incomplete. The processed row does not advance the watermark or broadcast. After closing each damaged writer, `HistoryRepository` rebuilds a private read-only index from the authoritative JSONL and recovers the captured row and processed row. Targeted IDEA JBR 21 `SessionStorageTest` run passed all 9 cases, zero failures/errors/skips; finished 2026-10-13 16:07:23 +08:00. Evidence: [sqlite-partial-failure.json](evidence/sqlite-partial-failure.json), plus `loglens-storage/target/surefire-reports/TEST-io.loglens.storage.SessionStorageTest.xml`. These injected faults do not replace the remaining persistent disk/index fault, slow-disk, or forced-kill coverage in R20.

## Reactor verification after SQLite partial-failure coverage

After adding the two SQLite trigger-injection tests, the complete reactor produced 22 Surefire reports with 80 tests, zero failures, zero errors, and zero skips on IDEA JBR 21.0.6. The 100,000-record history rebuild and 100-page traversal took 61.94 seconds. Latest Surefire report timestamp: 2026-10-13 16:10:52 +08:00. `scripts/verify-release.py` also passed against the existing release artifacts: Agent 24,172,012 bytes, Viewer 17,107,186 bytes, API 28,152 bytes. Evidence: module Surefire XML reports and [sqlite-partial-failure.json](evidence/sqlite-partial-failure.json). This does not prove Java 17 compatibility, browser keyboard/reconnect acceptance, the remaining persistent fault cases, or completion of the architecture scope; G2–G7 remain open.

## Proxy Remote 监控融合：实现阶段验证（2026-10-18）

实现包含 LogLens Agent 的可选 `proxyRemote.enabled` 配置、JDK-only 遥测 DTO/bridge、Byte Buddy 适配模块、有界连接/会话表与累计字节采样、Disruptor 遥测事件、JSONL/SQLite 历史、只读 API/SSE 和连接 UI。`proxy-remote` 业务源码未修改。未采集 payload。活动面板显示 socket peer 与 Stream ID，不宣称其为用户账号。

验证命令：`& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-storage,loglens-adapter-proxyremote,loglens-agent','-am','test')`，IDEA JBR 21.0.6，2026-10-18 16:32 +08:00，BUILD SUCCESS。包括 12 个 reactor 模块：API 3、bootstrap 5、config 11、processors 12、storage 13（含 100k history scale）、web 7、runtime 20、Logback adapter 4、ProxyRemote adapter 2、Agent 5；合计 82 个测试，无失败/错误/跳过。代理监控测试覆盖活动物理连接及逻辑 Stream、目标、上下行累计、关闭事件、周期采样，以及 100 个并发 Stream 注册在 maxTrackedSessions=1 时只保留一个并记录 99 个 drop。历史测试覆盖 telemetry JSONL→SQLite 恢复及 IP/Stream/transport/target/status keyset 过滤。桥接测试验证非活动/关闭通道不会计入字节。Web/runtime 测试验证遥测脚本静态资源、HTTP 历史查询与 typed SSE 发布。

前端 `telemetry.js` 和既有 `app.js` 通过 Node `--check` 语法检查。尚未运行真实浏览器验收。上述测试使用 JBR 21 编译 `--release 17`；未在 JDK 17 JVM 上执行，也未在真实 SimplePlane 子 JVM 中证明 Byte Buddy matcher 已命中 Netty/HTTP3 与 OutboundSession 类。因此 T19、T24 和 G3/G6 仍未通过，不能把遥测标记为端到端验证完成。后续还需验证遥测队列饱和/停机排空、遥测专属保留、慢客户端 gap 补齐、打包产物以及 Java 17/21 真实服务端运行。
## Proxy Remote 子 JVM 和真实浏览器验证增量（2026-10-18）

修复真实 `-javaagent` 测试捕获到的 Transformer 失败：`@Advice.Thrown` 绑定要求 `@Advice.OnMethodExit(onThrowable = Throwable.class)`。此前 Advice 类无法转换，连接注册看似工作但 `OutboundSession` 生命周期与字节切点被整个跳过。已将转换失败诊断保留为仅输出目标类名和异常类型，避免静默退化。

新增 `scripts/verify-proxyremote.py` 与测试 fixture，使用实际打包 `dist/loglens-agent.jar` 在 JBR 21 子 JVM 启动 Java 类名与方法签名均匹配 SimplePlane 的隔离 fixture。结果在 `.build/proxyremote-agent-verification/dfd7724a-dd91-4450-9303-ee851bfc3d0c/result.json`：7 条事件含 CLIENT_CONNECTED、SESSION_OPENED、SESSION_ACTIVE、TRAFFIC_SAMPLE、SESSION_CLOSED、CLIENT_DISCONNECTED；正常关闭累计 uplink=12/downlink=7，JSONL 无 payload 字段。该 fixture 证明 AgentBuilder 精确 matcher 和 Advice 实际织入，不等价于真实 SimplePlane 应用子 JVM。

新增 Playwright 连接页验收已通过：`python scripts/verify-ui.py`，结果目录 `.build/ui-verification/6d25b002-4601-4a3a-bc60-f7143ecba02e`。在真实 Chrome 浏览器中验证活动连接/会话统计、累计流量格式、Stream 行触发日志关键词检索、日志 SSE 切换，以及连接关闭的 telemetry SSE 更新；原有日志历史分页、键盘详情、暂停跟随、主题和文本安全检查仍通过。

`& ./scripts/package-release.ps1` 于 2026-10-18 16:39 +08:00 完成 BUILD SUCCESS，生成 Agent 24,203,943 bytes、Viewer 17,120,646 bytes、API 31,104 bytes；`scripts/verify-release.py` 通过。随后新增 fixture 验收通过。尚未运行真实 SimplePlane 服务端类、Java 17 runtime 矩阵、遥测队列饱和/排空与 telemetry retention 专项；T19/T20/T21/T22/T24 和 G3/G6 仍保持未完成状态。
## Final regression after OutboundSession transformation fix (2026-10-18)

IDEA JBR 21.0.6 `& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-agent','-am','test')` completed BUILD SUCCESS at 16:41:08 +08:00. The 12-module reactor ran 82 tests with zero failures/errors/skips, including the new adapter monitor/concurrency tests and telemetry JSONL recovery test. `scripts/package-release.ps1` then produced the Agent including the proxy adapter; `scripts/verify-proxyremote.py` passed against that exact artifact. The test is a same-FQCN/method-signature isolated fixture, not the actual SimplePlane module.

Final UI rerun: `python scripts/verify-ui.py` passed in Chrome with no page errors; evidence `.build/ui-verification/6ded990a-714b-475d-8b9a-57dc31a36c84/results.json` reports telemetry panel, active counts, cumulative traffic, stream-to-log search and live SSE checks. `python scripts/verify-agent.py` and `python scripts/verify-release.py` passed; latest artifact sizes were Agent 24,203,943 bytes, Viewer 17,120,646 bytes, API 31,104 bytes. Deployment usage and Java 17+ restriction are documented in `docs/proxy-remote-monitoring.md`.

Still open: no Java 17 installation was available for runtime execution; no real SimplePlane server child process was started; telemetry ring saturation/drain, dedicated telemetry retention, telemetry slow-client gap/recovery, and failure/security/performance matrices remain outstanding. These are explicit SDD limits, not passed checks.

## Proxy Remote 遥测故障场景与真实 SimplePlane 类验证（2026-10-18）

新增三项专项测试：`ProcessingRuntimeTest.telemetryIsNonblockingAtRingCapacityAndStopDrainsAcceptedEvents` 在活跃消费者被阻塞时填满共享 1024 槽环，验证遥测在 `overflowPolicy=BLOCK` 下仍快速返回、溢出/停机拒绝均计数，并在释放消费者后将 1023 个已接受遥测事件全部写入 JSONL、会话标记 complete；`SessionStorageTest.telemetryRetentionDeletesExpiredJsonlAndSQLiteRows` 验证过期关闭遥测段与索引行一并清理，活动段保留；`LocalWebServerTest.telemetryGapCanRecoverFromHistoryThroughReconnectWatermark` 验证容量为 1 的订阅收到 gap 并关闭，重连水位为 2，历史 API 按 through 水位补回序号 1、2。

使用 `& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-runtime,loglens-storage,loglens-web,loglens-agent','-am','test')` 在 IDEA JBR 21.0.6 执行，12 个 reactor 模块共 85 项测试通过，0 失败/错误/跳过，结束时间 2026-10-18 18:39:13 +08:00。使用 `-JavaHome 'C:\Users\92819\.jdks\corretto-17.0.14'` 重跑相同 reactor，同样 85 项通过，结束时间 18:39:59 +08:00。测试中出现的 `CAPTURED_WRITE_FAILED`/`OUTPUT_WRITE_FAILED` 来自既有故障注入用例，断言预期回退且测试通过。

随后 `scripts/package-release.ps1` BUILD SUCCESS，`scripts/verify-release.py` 与 `scripts/verify-proxyremote.py` 均通过；最终公开包大小为 Agent 24,204,741 bytes、Viewer 17,120,646 bytes、API 31,104 bytes。用该最终 Agent 包在 Corretto 17 子 JVM 中加载 `proxy-remote/target/classes` 的实际 `SessionManager`、`OutboundSession`，并使用 Netty 4.2.18 `EmbeddedChannel` parent-child：输出 SESSION_OPENED/SESSION_ACTIVE/SESSION_CLOSED，connectionId=`embedded`，累计 uplink=12/downlink=7，session complete=true。fixture 同时确认 Netty `DefaultChannelHandlerContext` 为 final 实现类，直接从该实现类反射调用 public `channel()` 会触发 `IllegalAccessException`；Agent 修复为从公开接口/父类解析方法后仍成功采集。JBR 21 实际类验证此前已通过（见 `.build/real-simpleplane-agent/run/result.json`）；本轮最终包的 Corretto 17 汇总证据位于 `../.build/real-simpleplane-agent/run3-java17/final-result.json`，完整 telemetry/session 文件位于其 `logs` 子目录。

本验证没有启动完整 SimplePlane 服务器或真实监听端口，因此不证明真实部署拓扑下所有 Netty/HTTP3 matcher；T24 的完整启动、故障/性能/安全矩阵仍未通过。Proxy Remote 扩展专项 T19–T23 已有对应验收证据；基础需求 R01–R24 的多项审计缺口以及 G2–G7 仍按 `audit.md` 保持开放，不据本次扩展验证宣称整个 LogLens SDD 完成。


## Proxy Remote 最终运行时与安全/性能烟测（2026-10-18）

修正 R34 的 Java 8 启动诊断边界：外层 `LogLensAgent` 仅依赖 Java 8 API，并在 Agent 模块 Maven 编译中单独以 `--release 8` 输出 classfile major version 52；Java 17 runtime 仍保持 `--release 17`。新增 `LauncherCompatibilityTest` 验证 Java 17/21 允许初始化、1.8 输出 `COMPATIBILITY_ERROR` 并默认返回、strict 模式抛出初始化失败。机器未安装 Java 8 JVM，因此没有声称做过 Java 8 真实运行；测试覆盖守卫分支与字节码/API 编译边界。部署说明已同步这一行为。

使用最终 `dist/loglens-agent.jar` 在真实 `ProxyRemoteServer` 入口下分别以 Corretto 17.0.14 与 JBR 21 启动完整服务，绑定仅 loopback 的独立测试端口，建立并关闭本机 TCP 连接，再调用服务 `shutdown()`。两种运行时均输出 CLIENT_CONNECTED 和 CLIENT_DISCONNECTED，connectionId 相同、peerAddress 为 loopback、transport=http2，会话 complete=true。结果：`.build/real-simpleplane-agent/full-server-java17/full-server-result.json` 与 `.build/real-simpleplane-agent/full-server-java21/result.json`。

另在两个运行时各顺序打开/关闭 100 个 loopback TCP 客户端。Corretto 17 在 45 ms、JBR 21 在 48 ms 完成客户端 connect/close 循环；每种运行时均落盘 100 个连接和 100 个断开事件，会话 complete=true。该数字仅是本机启动/网络/采集烟测，不构成吞吐或延迟性能承诺。每个事件只含连接元数据，没有 data/payload 字段。真实字节切点仍由前述实际 `SessionManager`/`OutboundSession` 测试覆盖：累计 uplink=12/downlink=7。

兼容性守卫变更后，`& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-agent','-am','test')` 在 JBR 21 于 18:46:17 通过 12 个 reactor 模块的 87 项测试；相同命令加 `-JavaHome 'C:\Users\92819\.jdks\corretto-17.0.14'` 于 18:50:15 再通过 87 项。最终 `scripts/package-release.ps1`、`scripts/verify-release.py`、`scripts/verify-proxyremote.py` 均通过，Agent 24,205,923 bytes、Viewer 17,120,642 bytes、API 31,104 bytes。Web 测试仍覆盖 loopback、无 CORS、只读方法和 Origin 拒绝；遥测 ring 饱和/排空、保留和 gap/历史恢复的专项测试在上一节。

T16–T24 的 Proxy Remote 融合验收已完成。边界仍为：没有用真实 Java 8 JVM 启动 Agent；100 客户端只是 smoke，不替代 T14/R22 的正式固定种子性能基准；服务端测试建立了真实监听连接但未执行完整代理协议请求，逻辑会话生命周期和字节通过实际 SimplePlane 编译类 + Netty EmbeddedChannel 验证。基础 LogLens R01–R24 的既有审计缺口和 G2–G7 门禁仍开放，不能据此宣称整个 LogLens SDD 完成。


## LogLens 迁移后构建回归（2026-10-18）

项目已迁移到 `E:\SimplePlanePlatform\LogLens`。使用 `scripts/build.ps1` 在迁移路径以 JBR 21 和 Corretto 17 分别执行 `-pl loglens-agent -am test`，两次均通过 12 个模块共 87 项测试，失败/错误/跳过为 0。随后在新路径执行 `scripts/package-release.ps1`，BUILD SUCCESS，`scripts/verify-release.py` 通过；产物 Agent 24,205,811 bytes、Viewer 17,120,642 bytes、API 31,104 bytes。构建缓存、临时目录与产物留在该子目录 `.build/`、`dist/`（均忽略，不纳入源码提交）。

## Proxy Remote 实际 systemd 部署与本地面板验收（2026-10-18）

将分支提交 `8f3939c47deadfec81ed55e1b5bc4444f29b19bb` 构建出的 Agent 部署到实际 `proxy-remote.service`。服务运行于 Amazon Corretto 17.0.20；通过 systemd service drop-in 设置 `JAVA_TOOL_OPTIONS` 注入 Agent 和监控 YAML，遥测数据写入服务账户的数据目录。Web 服务绑定服务器 loopback `127.0.0.1:9527`，没有增加公网监听。

部署后 systemd 服务为 active，面板监听进程 PID 与服务 MainPID 一致，排除了其他进程占用面板端口的情况。健康 API 返回 `accepting=true`、`outputHealthy=true`、`telemetryDropped=0`。在运维工作站建立 `127.0.0.1:9527 -> server loopback 127.0.0.1:9527` SSH 隧道后，浏览器页面返回 HTTP 200、标题为“LogLens / 日志工作台”；本地 health 和活动连接 API 均成功。该时点活动连接数为 16，随线上流量变化，不是固定验收值。Agent SHA-256 和脱敏部署摘要见 [proxyremote-deployment.json](evidence/proxyremote-deployment.json)。

该检查证明实际 Proxy Remote 服务中的 Agent 已启动、面板由正确 JVM 提供且隧道可用。检查过程没有读取 peer 地址或 payload，也没有通过生产请求验证每个会话的字节值；生产 Logback 日志捕获和字节累计分别由前述专项测试覆盖。现有服务重启会短暂中断代理连接，后续配置变更需按 [部署指南](../proxy-remote-monitoring.md) 维护。基础 LogLens R01–R24 的既有审计缺口和 G2–G7 门禁仍开放。

## Docker 集成配置检查（2026-10-19）

更新 Docker Remote 镜像，使其在 Java 17+ runtime 中启动 Proxy Remote 与同 JVM LogLens Agent；新增 loopback 面板 relay、持久化数据挂载、外部 Agent YAML、仅本机发布的面板/代理端口，以及显式的公网 9090 compose override。使用 LogLens runtime 内的 SnakeYAML 2.3 在 Corretto 17.0.14 下解析 `docker-compose.yml`、`docker-compose.remote-public.yml`、`docker/remote.yml`、`docker/proxy.yml` 与 `docker/loglens-proxyremote.yaml`；并由真实 `ConfigLoader` 加载 Agent 配置，确认 Proxy Remote 监控、结构化存储与 `127.0.0.1:9527` loopback Web 均启用。静态检查通过。

当前执行环境没有 Docker 或 Compose CLI，因此尚未实际构建镜像、启动容器、验证 `network_mode: service:proxy-remote` 下的 relay 发布与容器重启行为，也未通过 Docker 部署执行 SSH 隧道浏览器验收。R35/T26 保持待容器环境验收，不因 YAML/Agent 配置校验通过而标为完成。

## 可选 SQLite / Elasticsearch 搜索索引（2026-10-19）

按 R36–R40、D13 新增 `SearchIndex` 策略：默认 SQLite 仍使用 `index.sqlite` 和原有事务/JSONL 重建行为；ES 模式通过 Java 17 `HttpClient` 调 Elasticsearch REST API，为每个应用会话建立隔离索引，查询命中后从 JSONL 指针恢复完整 DTO。在线 `SessionStorage` 写入仍遵循 JSONL→选定索引→文本→SSE，RepeatFold/责任链未更改；历史 Viewer 仍仅使用 SQLite 临时索引。配置支持 backend、HTTP(S) endpoint、index prefix、连接/请求超时及环境变量 API key，摘要不含端点或密钥。

`ElasticsearchIndexTest` 的本机 HTTP mock 验证索引初始化、captured/processed/telemetry/source-group 写入、按段删除、JSONL hydration、水位、SessionStorage 文本与 SSE 广播、索引失败时不广播，以及 HTTP 503 初始化失败。新增查询断言覆盖大小写敏感的字面子串（含 `*`）、peer 地址子串、稀疏序号下的 keyset 翻页、固定 through 水位、无 `from` 深偏移和跨度 1,000,000 的无分组来源查询。`ConfigLoaderTest` 验证 SQLite 默认和 ES 配置边界；既有 `SessionStorageTest`/`HistoryRepositoryTest`/`HistoryScaleTest` 覆盖 SQLite 原路径与 JSONL 恢复。Java 21.0.6 执行 `& ./scripts/build.ps1 -MavenArguments @('-pl','loglens-agent','-am','test')`：12 个 reactor 模块共 94 项测试通过，0 失败/错误/跳过。Corretto 17.0.14 使用相同命令也通过 94 项。最新代码下，Java 21 与 Java 17 各自定向运行 `ElasticsearchIndexTest` 均通过 5 项。证据见各模块 `target/surefire-reports/TEST-*.xml` 和 [ElasticsearchIndexTest.xml](../../loglens-storage/target/surefire-reports/TEST-io.loglens.storage.ElasticsearchIndexTest.xml)。两次完整执行均覆盖 SQLite 历史 100,000 行重建及分页。

ES 查询映射使用 wildcard 字段复现 SQLite `instr` 字面子串语义；排序索引按唯一递增序号预排序，每页通过 `seq > cursor`、`seq <= through` 和 `limit + 1` 做有界 keyset 查询。未运行真实 Elasticsearch 集群或性能基准；本轮按任务约束仅使用本地 HTTP mock，无法据此声称具体集群版本兼容、生产吞吐或延迟已验收。ES 在线写入仍同步 `refresh=wait_for`，远程延迟进入单消费者路径；启用前须在目标集群做端到端基准。支持版本按 wildcard query 要求记为 Elasticsearch 7.10+。

限制：当前没有连接真实 Elasticsearch 服务，REST 兼容只由本地 mock 验证；没有比较 SQLite 与 ES 的查询延迟或承载速率。ES 使用 `refresh=wait_for` 维持 SSE 与历史查询的即时可见性，远程写入延迟会进入唯一消费者路径；生产启用前需对目标流量做基准。不得把 ES 在大规模全文检索场景可能有优势表述为已测得的总体性能提升。

## Elasticsearch Bulk 与 JSONL 分页 hydration（2026-10-19）

按 R41/R42、D14 完成 bounded Bulk 与共享 `SegmentReader.readMany`。captured 可延迟至 processed FINAL 一起提交；批次最多 100 个索引动作、8 MiB，单动作/超限动作走单文档 PUT。FINAL 与 telemetry 的索引确认仍发生在对应 SSE 广播之前；批次响应必须完整且每项成功，部分失败不会被当作成功。确定性文档 ID 使显式重放幂等，失败仍通过既有存储健康状态显式暴露，不执行无界异步重试。SQLite 和 ES 页结果均按 segment 分组，每页每个 segment 只打开一个短生命周期 FileChannel，结束即关闭以允许保留清理删除文件。

`ElasticsearchIndexTest` 覆盖 captured 与 FINAL 合并成 2 项 Bulk、200 响应中的逐项部分失败不广播 FINAL、写入失败状态和查询可见性；`SegmentReaderTest` 覆盖跨 segment hydration 顺序和不安全路径拒绝。最新代码下，Java 21.0.6 与 Corretto 17.0.14 各自执行 `scripts/build.ps1 -MavenArguments @('-pl','loglens-agent','-am','test')`，12 个 reactor 模块均成功，各 95 项测试通过，零失败/错误/跳过；两次全量执行均覆盖 SQLite 的 100,000 行历史恢复与分页。Java 21/17 定向执行 `ElasticsearchIndexTest,SegmentReaderTest` 各 6 项通过。报告见模块 `target/surefire-reports/`，包括 [ElasticsearchIndexTest.xml](../../loglens-storage/target/surefire-reports/TEST-io.loglens.storage.ElasticsearchIndexTest.xml) 和 [SegmentReaderTest.xml](../../loglens-storage/target/surefire-reports/TEST-io.loglens.storage.SegmentReaderTest.xml)。

验证仍仅使用本机 HTTP mock；未在真实 Elasticsearch 上做版本兼容、吞吐、延迟或 SQLite 对比基准。因此这次确认的是批次边界、确认/失败顺序和回归正确性，不代表生产性能提升；部署前仍应使用目标集群和实际流量做基准。共享 ES 索引与 n-gram 未引入，因其增加跨会话清理复杂度或索引磁盘/写入成本，当前缺乏基准证据证明收益大于代价。

## Docker Elasticsearch 配置（2026-10-19）

Docker Agent YAML 增加显式 `output.search` 配置，默认仍为 SQLite；Compose 仅透传可选的 `LOG_LENS_ELASTICSEARCH_API_KEY`，没有增加 Elasticsearch 服务或公网端口。SnakeYAML 2.2 已解析 `docker-compose.yml`、公网覆盖文件和三个挂载 YAML；Corretto 17.0.14 执行 `scripts/build.ps1 -MavenArguments @('-pl','loglens-config','-am','test')`，API 3 项与 ConfigLoader 13 项均通过。Docker 部署说明写明切换 backend、配置容器可达 endpoint、可选设置 API key 以及重启服务；root README 也补充了入口说明。Compose 未设置 API key 时不注入该环境变量，启用 SQLite 时不会连接 ES。

当前机器未安装 Docker/Compose CLI，未执行 Compose 插值校验、镜像构建或容器启动；T33 保持部分完成，不能据此宣称 Docker 镜像已用 ES 部署验证。
