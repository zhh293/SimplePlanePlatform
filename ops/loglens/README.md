# 运营日志中心

运营后台的 `/admin/logs` 依赖 LogLens 节点输出和专用 Elasticsearch 索引。用户筛选按签发设备证书时的账号摘要匹配。旧证书和兼容线路的账号归属显示为未知。页面查询的目标是代理会话的主机和端口。

## 配置

- 在启用 LogLens 的代理节点开启结构化输出。采集进程读取 `<directory>/<application>/<session>/captured-*.jsonl` 与 `telemetry-*.jsonl`。节点 ID 应与运营后台目录一致。
- 为日志部署设置私有 HTTPS Elasticsearch，客户端信任其服务端证书。使用单独的模板管理、采集写入、后台只读凭证，权限范围固定到该环境的 `sp-log-<环境>-*`。公开的无认证 ES 只用于合成数据灰度。
- 采集进程使用独立账号，能读取节点日志目录并写入独立检查点目录。一个检查点文件对应一个节点和一个目标索引前缀。保留足够的本地滚动存储以覆盖预期故障时间，索引删除策略与 `--retention-days` 采用同一留存窗口。

模板由运维凭证预创建，随后换用范围受限的写入凭证启动采集进程。将凭证放在服务环境中。使用私有 CA 时为 Python 设置 `SP_LOG_ES_CA`，并通过 Java 信任库配置后台容器。

```sh
python3 ops/loglens/ship.py prepare --url https://es-logs.internal --prefix sp-log-prod
python3 ops/loglens/ship.py ship --url https://es-logs.internal --prefix sp-log-prod \
  --node zhh-1 --root /var/lib/loglens --state /var/lib/loglens-ship/checkpoint.sqlite
```

后台通过 `SP_LOG_ES_URL`、`SP_LOG_ES_PREFIX`、`SP_LOG_ES_AUTH` 和 `SP_LOG_OPERATORS` 配置日志中心。日志操作人使用逗号分隔的 OIDC subject，账号还须拥有现有的运营后台管理员权限。未配置日志地址时入口隐藏。运营方明确选择复用公开 9200 时，后台设置 `SP_LOG_PUBLIC_HTTP=true`，采集进程使用 `--allow-public-http`，生产前缀固定为 `sp-log-prod`，HTTP 请求不携带认证凭据。

## 灰度和切换

管理后台的独立灰度地址为 `/admin/logs-preview`。部署脚本 `deploy_preview.py start` 从带 SHA-256 目录名的候选包启动回环实例、专用数据库和合成索引。`publish` 先检查候选和现网，再为预览增加 HTTPS 路由，使用现有运营管理员登录。发布前保存原站点配置；配置校验、重载或路由复查失败会自动恢复。

1. 使用 `check_canary.py` 在独立前缀建立合成索引。脚本验证账号隔离、域名筛选、短语、断线恢复、重复重放和 Elasticsearch 分页，并清理创建的索引。
2. 使用隔离网络启动带 Agent 的代理候选实例，验证至少两个真实设备证书、兼容连接、TCP 与 UDP 往返，以及失败日志和会话归属。等候候选实例的资源用量稳定后，再进行正式节点的切流验证。
3. 管理后台候选实例在独立回环端口验证登录、普通账号拒绝、日志授权、设备续签和实际查询。原入口继续服务；验证后才切换反向代理，并保存上一版配置和包。流量和健康检查异常时恢复上一版路由。
4. 正式代理逐节点切换。确认旧连接能在旧实例继续工作，且新旧凭据和 TCP/UDP 都能通过，再调整入口。一个节点过载或磁盘不足时暂停该节点的切换。

切流后观察查询错误率、采集延迟、索引增长、两种代理凭据的连接成功率。取样流量统计使用已结束会话的最终累计值。
