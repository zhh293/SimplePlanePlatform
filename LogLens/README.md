# LogLens：Java 日志与连接监控工作台

LogLens 是一个通过 Byte Buddy Java Agent 接入目标 JVM 的本地观测工作台。它把应用日志采集、处理、历史查询和 Proxy Remote 连接遥测放在同一个 Agent、会话存储与网页面板中。目标应用无需编译期依赖 LogLens；Proxy Remote 监控通过运行时字节码增强观察连接和会话生命周期，并累计代理上下行字节数。

Proxy Remote 融合功能已完成专项测试，并已部署到实际 `proxy-remote.service` 验证。LogLens 的基础 SDD 需求审计仍有开放项；状态和范围以 [SDD 验证记录](docs/sdd/verification.md) 与 [逐需求审计](docs/sdd/audit.md) 为准。

## 能力

- 通过 Logback 适配器采集日志快照，并支持 observe 与受限的 replace-console 模式。
- 通过 Proxy Remote 适配器观察物理连接、逻辑会话、会话状态和目标地址。
- 统计 `forward(byte[])` 上行与 `writeBack(byte[])` 下行字节，按周期生成累计流量快照。
- 在内嵌 Web UI 中查看活动连接、会话历史、流量与日志；支持筛选和从 Stream ID 检索相关日志。
- 使用有界队列、活动跟踪上限、JSONL/SQLite 历史存储、查询水位与 SSE 更新。
- 通过 SPI 扩展处理节点；可用 Viewer 查询和导出历史会话。

## 架构与数据边界

Agent 由 Byte Buddy 外壳、JDK-only bootstrap bridge、隔离加载的 LogLens runtime 和适配器组成。日志和网络遥测共用 Agent 生命周期、有限 Disruptor、会话目录、只读 HTTP/SSE 服务和 UI；遥测使用独立的 `TelemetryEvent` 类型，不进入日志折叠处理链。

监控只读取连接元数据、目标信息、会话状态和字节数组长度。代理 payload 不会被读取、解密、保存或交给后台线程。socket peer 和 Stream ID 是连接标识，不能据此认定为用户账号。日志采集依赖目标进程中的 Logback；是否能采集某个应用的日志取决于其实际日志框架和配置。

Web 服务默认只绑定 `127.0.0.1`。远程查看应使用 SSH/VPN 隧道，不要向公网开放面板端口。遥测有连接数、会话数与队列容量上限；超过上限会记录 drop，不能承诺零丢失。

详细设计见 [架构说明](docs/loglens-architecture.md)、[SDD 设计契约](docs/sdd/design.md) 和 [Proxy Remote 监控与部署指南](docs/proxy-remote-monitoring.md)。

## 环境与构建

- Java runtime 基线为 Java 17；Java 17 和 21 有实际验证。Java 8 目标字节码的 SimplePlane 服务不代表 Agent 能在 Java 8 JVM 运行。
- 使用 IDEA 配套 JDK/Maven 构建。脚本默认 IDEA 在 D 盘，可用 `IdeaHome` 参数修改。
- 缓存、临时目录、用户目录和构建产物定向到仓库 `.build/` 与模块 `target/`。
- 项目应放在非 C 盘工作区。

```powershell
& ./scripts/build.ps1 -MavenArguments @('verify')
& ./scripts/package-release.ps1
```

`package-release.ps1` 生成 `dist/loglens-agent.jar`、`dist/loglens-viewer.jar` 和 `dist/loglens-api.jar`，并检查发布包布局。发布前先运行 `verify`；打包脚本自身跳过测试。

## 将 Agent 接入普通 Java 应用

把 `dist/loglens-agent.jar` 和 YAML 配置放到目标主机，然后在原 Java 启动参数中加入：

```text
-javaagent:/path/to/loglens-agent.jar=config=/path/to/loglens.yaml
```

Agent 参数必须作用于加载目标应用类的 JVM。配置或插件发生变化后，重启该 JVM。仅增加 Maven 依赖不会启动 Agent。

配置示例见 [loglens.yaml](loglens.yaml)。默认模式是 `observe`，Web 端口为 `127.0.0.1:9527`。结构化存储必须启用才能使用 Web 查询。

在线搜索索引可在 `output.search.backend` 选择，默认 `sqlite`，现有 SQLite 查询、历史恢复和 Viewer 行为继续可用。需要 Elasticsearch 时设置 `backend: elasticsearch`、服务端 `endpoint` 和独立的 `indexPrefix`；ES 需为 7.10 或更新版本。可用 `apiKeyEnv` 指定环境变量名，密钥本身从环境变量读取，不写入 YAML 或会话配置摘要。ES 为每个应用会话建立隔离索引，完整日志仍保存在本地 JSONL；在线 ES 模式需要 ES 服务可达。日志消息、peer/target 地址保持 SQLite 的大小写敏感字面子串语义，分页按递增序号 keyset 进行并固定补拉水位，不使用深偏移。ES 将 captured 与 FINAL 索引动作合并到最多 100 项/8 MiB 的有界 Bulk，FINAL 和 telemetry 仍在对应 SSE 广播前同步确认；因此远端延迟仍会影响 LogLens 消费速度，启用前应按实际日志速率做基准。真实 ES 集群和性能基准尚未验证。切换后需重启 Agent。详见[架构设计](docs/sdd/design.md#可选搜索索引d13)与[验证记录](docs/sdd/verification.md)。

## 启用 Proxy Remote 监控

在服务端配置中启用 `proxyRemote`，并为数据选择持久化目录：

```yaml
schemaVersion: 1
application: simpleplane-proxy
output:
  directory: ./loglens-data
  structured:
    enabled: true
proxyRemote:
  enabled: true
  sampleIntervalMillis: 1000
  maxTrackedConnections: 10000
  maxTrackedSessions: 50000
web:
  enabled: true
  host: 127.0.0.1
  port: 9527
```

如果服务由 systemd 管理，可给**实际运行 Proxy Remote 的服务单元**增加 drop-in：

```ini
[Service]
Environment="JAVA_TOOL_OPTIONS=-javaagent:/opt/loglens/loglens-agent.jar=config=/opt/loglens/loglens.yaml"
```

然后执行 `sudo systemctl daemon-reload` 和 `sudo systemctl restart <proxy-service>`。重启会中断该服务上的现有连接；确认服务恢复后再查看面板。服务启动失败时应移除该 drop-in、daemon-reload 并重启，以恢复原启动方式。

当前部署实例的文件布局、运行结果和限制见 [部署验证证据](docs/sdd/evidence/proxyremote-deployment.json)。

仓库根目录的 Docker Compose 也会在 Proxy Remote 同一 JVM 中注入 Agent，并将 LogLens 数据持久化到 `loglens-data/`。Docker 镜像使用 Java 17+；面板保持 loopback 绑定，由 relay 仅发布到宿主机 `127.0.0.1:9527`，默认不发布代理 9090。完整启动、端口边界和公网端口显式覆盖步骤见 [Docker 部署说明](../docs/docker部署说明.md)。容器镜像尚需在具备 Docker/Compose 的环境完成实际构建验收。

## 本地打开远端面板

保持服务器 Web 只监听 loopback，在本地建立 SSH 隧道：

```powershell
ssh -i <key.pem> -N -L 9527:127.0.0.1:9527 ec2-user@<server>
```

浏览器打开 [http://127.0.0.1:9527](http://127.0.0.1:9527)，切换到“连接”视图。隧道的本地端口和服务器面板端口应保持一致，以通过面板的 Host/Origin 本地请求校验；如果本地端口被占用，同时调整服务端 `web.port` 和隧道两端端口。不要提交私钥或将服务器真实地址写入共享文档。

## 历史查看与导出

```powershell
java -jar dist/loglens-viewer.jar '<session-directory>' 9527 '<scratch-directory>'
java -jar dist/loglens-viewer.jar export '<session-directory>' '<report.html>' '<scratch-directory>'
```

scratch 和导出文件应放在原会话目录外。Viewer 使用只读历史查询，不会修改会话索引。

## 示例与验证

- [Java/Spring Boot 与 SPI 示例](examples/README.md)
- [需求规格](docs/sdd/requirements.md)
- [设计契约](docs/sdd/design.md)
- [任务追踪](docs/sdd/tasks.md)
- [验证记录](docs/sdd/verification.md)
- [逐需求审计](docs/sdd/audit.md)

普通 Java、Spring Boot、插件及浏览器 UI 的可重复验证命令均记录在验证文档中。当前 Proxy Remote 连接页已通过 Java 17/21 Agent 测试和真实服务部署检查；基础 LogLens 的未完成审计项仍按 SDD 保持开放。
