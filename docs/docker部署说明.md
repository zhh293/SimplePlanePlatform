# Docker 部署说明

本项目运行时由两个进程组成：

- **proxy-remote**：远程服务端，接收加密隧道流量并代为访问目标站点（监听 9090）。
- **proxy-local**：本地客户端，对外暴露 SOCKS5 / HTTP CONNECT 代理端口（监听 1080），把流量经加密隧道转发给服务端。

下文给出两端的 Docker 镜像构建方式，以及用 `docker-compose` 一键编排的方法。

---

## 1. 文件清单

```
SimplePlanePlatform/
├── docker-compose.yml          # 一键编排两端
├── .dockerignore               # 构建上下文忽略规则
└── docker/
    ├── Dockerfile.remote       # 服务端镜像（多阶段构建）
    ├── Dockerfile.local        # 客户端镜像（多阶段构建）
    ├── remote.yml              # 服务端外部配置（挂载用）
    └── proxy.yml               # 客户端外部配置（挂载用）
```

镜像采用**多阶段构建**：第一阶段用 `maven:3.9-eclipse-temurin-8` 编译打包，第二阶段用轻量的 `eclipse-temurin:8-jre` 运行，最终镜像不含 Maven 与源码。

> **为什么不用 `java -jar`？**
> 当前项目的 `maven-jar-plugin` 只写了 `Main-Class`，没有打 fat-jar，jar 内也没有 Class-Path。
> 因此镜像里采用「**瘦 jar + 依赖目录**」方式：构建阶段执行 `dependency:copy-dependencies` 把 Netty/SnakeYAML/Logback 等依赖拷到 `lib/`，运行时用 `-cp "config:classes:lib/*"` 启动。
> 这样**无需改动任何现有 pom**。

---

## 2. 一键启动（推荐：docker-compose）

在**项目根目录**执行：

```bash
# 构建镜像并后台启动两端
docker compose up -d --build

# 跟踪日志
docker compose logs -f

# 只看某一端
docker compose logs -f proxy-remote
docker compose logs -f proxy-local

# 停止并移除容器与网络
docker compose down
```

> 旧版本 Docker 用 `docker-compose`（带连字符）替代 `docker compose`。

编排关系说明：

- 两个容器在同一自定义 bridge 网络 `proxy-net` 中，`proxy-local` 通过**服务名 `proxy-remote`** 连接服务端（已在 `docker/proxy.yml` 的 `remoteServers.host` 中配置）。
- **仅 `proxy-local` 的 1080 端口映射到宿主机**；9090 只在容器内网用于两端通信，不对外暴露。
- `proxy-remote` 配了 healthcheck（探测 9090 TCP 可连），`proxy-local` 通过 `depends_on: condition: service_healthy` **等服务端就绪后再启动**，保证启动顺序正确。

启动成功后，宿主机上即可使用代理：

```bash
# SOCKS5
curl -x socks5h://127.0.0.1:1080 https://www.example.com -v
# HTTP CONNECT（同端口）
curl -x http://127.0.0.1:1080 https://www.example.com -v
```

---

## 3. 单独构建 / 运行（不用 compose）

### 3.1 构建镜像（项目根目录）

```bash
docker build -f docker/Dockerfile.remote -t netty-proxy-remote:1.0.0 .
docker build -f docker/Dockerfile.local  -t netty-proxy-local:1.0.0  .
```

### 3.2 运行服务端

```bash
docker run -d --name netty-proxy-remote \
  -p 9090:9090 \
  -v "$(pwd)/docker/remote.yml:/app/config/remote.yml:ro" \
  netty-proxy-remote:1.0.0
```

### 3.3 运行客户端

```bash
docker run -d --name netty-proxy-local \
  -p 1080:1080 \
  -v "$(pwd)/docker/proxy.yml:/app/config/proxy.yml:ro" \
  -v "$(pwd)/logs:/app/logs" \
  netty-proxy-local:1.0.0
```

> 单独运行时两个容器不在同一 compose 网络，`proxy.yml` 里的 `remoteServers.host` 不能再写 `proxy-remote`，
> 需改成宿主机可达的地址（例如服务端宿主机 IP，或在 macOS/Windows 上用 `host.docker.internal`）。

---

## 4. 配置说明（重点）

外部配置通过挂载到容器的 `/app/config/` 生效。该目录在 classpath 最前，会**覆盖镜像内置的同名配置**。

### 4.1 两端必须一致的两项

| 配置项 | 文件 | 说明 |
|--------|------|------|
| `cipher` | remote.yml / proxy.yml | 加密算法，任一不一致 → 隧道解密失败 |
| `cipherKey` | remote.yml / proxy.yml | 加密密钥，**务必改成强随机值**，且两端相同 |

示例配置默认 `cipher: aes-gcm`、`cipherKey: change-me-to-a-strong-shared-key`，**上生产前务必替换密钥**。

### 4.2 容器内必须关闭系统代理

`docker/proxy.yml` 中已设置：

```yaml
systemProxy:
  enabled: false
```

原因：proxy-local 的“自动设置系统代理”功能改的是**容器内部**的系统代理，对宿主机无意义，且在精简镜像里可能因缺少 `networksetup`/`gsettings` 而报错（代码里是非致命警告，但无实际作用）。容器场景下应由使用方应用**显式**把代理指向宿主机 `127.0.0.1:1080`。

### 4.3 两端分开部署

若服务端和客户端部署在不同机器，不使用同一 compose：

1. 在服务端机器只起 `proxy-remote`，并把 9090 映射/放行到外网（`docker/Dockerfile.remote` 已 `EXPOSE 9090`，compose 中取消 `ports` 注释或用 `docker run -p 9090:9090`）。
2. 在客户端机器修改 `docker/proxy.yml` 的 `remoteServers.host` 为服务端的真实 IP/域名，只起 `proxy-local`。
3. 确认两端 `cipher`/`cipherKey` 一致，且服务端 9090 在防火墙/安全组放行。

---

## 5. 日志与数据

| 容器 | 日志位置 | 持久化 |
|------|----------|--------|
| proxy-remote | 标准输出（`docker logs` 可见） | 无需挂载 |
| proxy-local | 标准输出 + 容器内 `/app/logs/proxy-local.log` | compose 已把 `./logs` 挂到 `/app/logs` |

查看：

```bash
docker compose logs -f
# 或本地端文件
tail -f logs/proxy-local.log
```

---

## 6. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| `proxy-local` 启动后连不上服务端 | 服务端未就绪 / host 配错 | compose 已用 healthcheck 控制顺序；分开部署时核对 `remoteServers.host` |
| 能连接但数据无响应 / 报解密错误 | 两端 `cipher`/`cipherKey` 不一致 | 对齐 remote.yml 与 proxy.yml |
| 构建很慢 | 首次拉取基础镜像 + 下载 Maven 依赖 | 后续构建命中缓存会快很多；可配置 Maven 镜像源加速 |
| 1080 端口冲突 | 宿主机 1080 被占用 | 改 compose 端口映射，如 `"11080:1080"` |
| healthcheck 一直 unhealthy | 基础镜像无 bash | 本项目用的 `eclipse-temurin:8-jre` 含 bash；若换镜像需调整 healthcheck 命令 |

---

## 7. 速查命令

```bash
# 构建并启动
docker compose up -d --build
# 重启某一端（改了配置后）
docker compose restart proxy-local
# 重新构建某一端
docker compose build proxy-remote && docker compose up -d proxy-remote
# 进容器排查
docker exec -it netty-proxy-local sh
# 停止并清理
docker compose down
```
