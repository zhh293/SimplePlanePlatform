# Docker 部署说明

Docker 编排同时支持 Proxy Remote、LogLens 监控面板和可选的 proxy-local。LogLens Agent 与 Proxy Remote 运行在同一个 Java 17 JVM 中，通过 Byte Buddy 观察连接、会话、字节数并采集受支持的 Logback 日志；无需修改服务端业务源码。

## 服务与端口

| 服务 | 容器监听 | 宿主机发布 | 用途 |
|---|---:|---:|---|
| `proxy-remote` | TCP 9090 | 默认不发布 | 代理隧道，仅 compose 网络可访问 |
| LogLens Web | `127.0.0.1:9527` | 不直接发布 | Agent 内嵌的只读面板 |
| `loglens-panel-relay` | TCP 19527 → 容器 loopback 9527 | `127.0.0.1:9527` | 让宿主机 SSH 隧道访问面板 |
| `proxy-local` | TCP 1080 | `127.0.0.1:1080` | 宿主机本地 SOCKS5 / HTTP CONNECT 代理 |

LogLens Web 强制绑定 loopback。Docker 的端口发布无法直接连接容器自身的 loopback，因此 relay 与 `proxy-remote` 共享网络命名空间，单独转发到容器内 19527；宿主机只绑定 `127.0.0.1:9527`。面板本身仍只接受本地请求，远程管理应通过 SSH 隧道，不能把 9527 映射到 `0.0.0.0`。

## 一键启动

在项目根目录执行：

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f proxy-remote
```

首次构建会编译 Java 8 目标字节码的代理模块和 Java 17 LogLens Agent；最终 Proxy Remote 镜像使用 Java 17 运行时。Agent 配置在 `docker/loglens-proxyremote.yaml`，日志和遥测历史持久化到 `./loglens-data/`。`docker/remote.yml` 和 `docker/proxy.yml` 中的加密算法与密钥必须一致；部署前将示例密钥替换为强随机值，不要把实际密钥提交到版本库。

LogLens 在线搜索默认使用 SQLite。若要使用 Elasticsearch，编辑挂载的 `docker/loglens-proxyremote.yaml`，将 `output.search.backend` 改为 `elasticsearch`，并把 `endpoint` 设置为 **Proxy Remote 容器可访问**的 HTTP(S) 地址；同一 Docker 网络中的服务应使用服务 DNS 名，不能直接使用宿主机上的 `127.0.0.1`。Compose 不会默认启动 Elasticsearch，也不会公开 ES 端口。需要 API key 时，在启动 Compose 的 shell 中设置 `LOG_LENS_ELASTICSEARCH_API_KEY`；Compose 只透传该变量，密钥不写入配置文件或仓库。

```bash
read -rsp 'Elasticsearch API key: ' LOG_LENS_ELASTICSEARCH_API_KEY; echo
export LOG_LENS_ELASTICSEARCH_API_KEY
docker compose up -d --build proxy-remote loglens-panel-relay
unset LOG_LENS_ELASTICSEARCH_API_KEY
```

不启用 API key 时无需设置该环境变量。切换到 Elasticsearch 只改变在线搜索索引；会话 JSONL 仍保存在 `./loglens-data/`，历史 Viewer/恢复仍使用本地 SQLite。ES 不可达或鉴权失败会使 LogLens 存储报告不健康，启用前应确认容器网络与集群凭据。

本机面板地址：<http://127.0.0.1:9527/>。远程服务器上部署时，在管理工作站建立 SSH 隧道：

```bash
ssh -i <key.pem> -N -L 9527:127.0.0.1:9527 <user>@<server>
```

随后在工作站访问 <http://127.0.0.1:9527/>。隧道本地端口和服务器面板端口需一致，以通过 Host/Origin 校验。

常用维护命令：

```bash
docker compose logs -f proxy-remote loglens-panel-relay
docker compose restart proxy-remote
docker compose down                 # 保留 loglens-data
docker compose down -v              # 不会删除 bind mount 的 loglens-data
```

修改 `docker/loglens-proxyremote.yaml` 或代理配置后需重建/重启 `proxy-remote`。重启该容器会断开现有代理连接。升级 Agent 或 Java 运行时需执行 `docker compose up -d --build proxy-remote loglens-panel-relay`。

## 从其他机器连接 Proxy Remote

默认 compose 不发布 9090。只有确实需要接受其他机器上的 `proxy-local` 时才叠加公网端口配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.remote-public.yml up -d --build
```

该覆盖文件只发布 Proxy Remote TCP 9090；请同时配置云安全组/主机防火墙，仅允许受信任的客户端 IP。LogLens 9527 仍只绑定宿主机 loopback。拆分两端部署时，客户端的 `remoteServers.host` 应填写服务端可达的地址，并保持两端 `cipher` 与 `cipherKey` 一致。

## 配置与数据

- `docker/remote.yml`：Proxy Remote 的监听和加密配置。`cipherKey` 示例值仅供开发，生产必须替换。
- `docker/proxy.yml`：proxy-local 连接服务端的配置；同 compose 网络通过服务名 `proxy-remote` 访问。
- `docker/loglens-proxyremote.yaml`：Agent 的日志过滤、脱敏、遥测上限、loopback Web 端口、存储保留策略和在线搜索后端；搜索默认 SQLite。
- `loglens-data/`：LogLens 会话、JSONL 和 SQLite 历史数据；备份时复制该目录。容器使用 UID 10001 写入数据，Linux 主机可按需将目录属主设置为 UID/GID 10001。
- `ladder.pem`、`.build/`、Maven/Rust/Gradle 缓存、运行日志和 LogLens 数据均在 `.dockerignore` 中排除，避免把私钥、构建缓存或运行数据发送到 Docker 构建上下文。

Agent 采用 `observe` 模式，不接管或替换原始控制台日志。遥测采集只读取字节数组长度，不读取、解密、保存代理 payload。活动连接/会话、事件队列和历史磁盘空间均有上限；达到上限时会报告 drop 或按保留策略清理，不承诺零丢失。socket peer 是网络地址，不是账号身份。

## 排查

```bash
docker compose ps
docker compose logs --tail=200 proxy-remote
docker compose logs --tail=100 loglens-panel-relay
curl --max-time 5 http://127.0.0.1:9527/api/health
```

- `proxy-remote` 不健康：先看 Agent 初始化诊断和 Java 启动日志，再检查 `docker/remote.yml`、配置挂载及数据目录权限。
- 面板本机不可访问：确认 `proxy-remote` 和 relay 均为 healthy，宿主机 9527 未被占用，并确认 compose 发布地址是 `127.0.0.1`。
- SSH 隧道已建立但页面不可用：服务器本机应能访问 `http://127.0.0.1:9527/api/health`；检查远端 9527 发布与 relay 日志。不要把 Agent 的 `web.host` 改成 `0.0.0.0`。
- 代理无法连接：同 compose 模式检查 `remoteServers.host: proxy-remote` 和两端密钥；分开部署模式检查服务端 TCP 9090、防火墙和真实地址。
- Java 版本错误：LogLens Agent 需要 Java 17 或更高版本；不要把 Proxy Remote 镜像运行时降回 Java 8。

更多监控边界与非 Docker 部署流程见 [Proxy Remote 监控部署指南](../LogLens/docs/proxy-remote-monitoring.md)。
