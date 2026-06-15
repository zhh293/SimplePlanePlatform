# SimplePlane Dashboard 后端开发文档

> 本文档描述 `dashboard/server.js` 这个后端服务的架构、进程管理模型、系统代理与 DNS 控制、跨平台差异、权限（sudo）方案、配置读写、SSE 推送，以及开发与排障规范。文档严格贴合当前代码实现。
>
> 配套文档：前端见 `dashboard/FRONTEND.md`。

---

## 1. 定位与技术选型

`server.js` 是一个**单文件后端**，职责是把命令行那套「启动 proxy-local / tun-adapter、开关系统代理、改 DNS、读写配置」封装成 HTTP API，供前端面板调用。

- **运行时**：Node.js，仅用内置模块 `http` / `fs` / `path` / `os` / `child_process`，外加本地 `./yaml.js`。
- **零第三方依赖**：`package.json` 无 dependencies，`npm start` 即 `node server.js`。
- **核心手段**：通过 `child_process` 的 `spawn`（长驻进程）和 `execSync`（一次性命令）去驱动系统：`java -jar`、`cargo/mvn` 编译、`networksetup`、`reg`、`sudo`、`pkill` 等。

**设计取向（新增后端代码请遵守）：**

1. 所有对外接口返回统一 JSON：`{ ok: boolean, ... }`，失败带 `error` 字符串，部分场景带 `message`。
2. 任何会改变服务/系统状态的操作，完成后用 `broadcastSSE('status', getStatusAll())` 推送，让前端实时更新。
3. 涉及特权操作（TUN、改 DNS、改系统代理）一律「先尝试无 sudo，失败再 `sudo -n` 兜底」，绝不交互式等密码（会卡死后台进程）。
4. 跨平台分叉用 `IS_WIN` / `IS_MAC` 显式判断，macOS 与 Windows 各自一套实现函数。
5. 对外部命令的失败要 `try/catch` 包裹并降级，不要让一个 `execSync` 抛错拖垮整个进程。

---

## 2. 启动与运行时常量

```bash
cd dashboard
node server.js                 # 默认 3000
DASHBOARD_PORT=8080 node server.js
```

`server.js` 顶部的关键常量（第 8~22 行）：

| 常量 | 值 / 来源 | 说明 |
|------|----------|------|
| `PORT` | `process.env.DASHBOARD_PORT` 或 `3000` | HTTP 监听端口 |
| `PROJECT_ROOT` | `path.resolve(__dirname, '..')` | 项目根目录（dashboard 的上一级） |
| `LOCAL_CONFIG` | `proxy-local/src/main/resources/proxy.yml` | proxy-local 配置 |
| `REMOTE_CONFIG` | `proxy-remote/src/main/resources/remote.yml` | proxy-remote 配置 |
| `TUN_CONFIG` | `tun-adapter/config/tun.toml` | TUN 配置 |
| `PUBLIC_DIR` | `dashboard/public` | 静态资源目录 |
| `PRESETS_DIR` | `dashboard/presets` | 预设目录（启动时自动创建） |
| `IS_WIN` / `IS_MAC` | `process.platform` 判断 | 平台分叉开关 |

服务在 `server.listen(PORT)` 启动，并在启动时记录三个配置文件的 mtime 作为基线。

---

## 3. 进程管理模型（核心）

### 3.1 进程状态表

后端用一张内存表跟踪两个受管服务（第 36~39 行）：

```js
const processes = {
  'proxy-local': { proc: null, status: 'stopped', logs: [], startedAt: null },
  'tun-adapter': { proc: null, status: 'stopped', logs: [], startedAt: null },
};
```

| 字段 | 含义 |
|------|------|
| `proc` | `spawn` 返回的子进程句柄；null 表示当前没有受管子进程 |
| `status` | `stopped` / `starting` / `running` |
| `logs` | 环形日志缓冲（上限 `MAX_LOG_LINES = 2000`） |
| `startedAt` | 启动时间戳，用于算 uptime |

> 注意：`status` 还有一个**派生值** `running (external)`，只在 `getStatusAll()` 里临时计算（见 3.5），不写回 `processes`。

### 3.2 日志缓冲与推送

```js
function addLog(name, line, stream = 'stdout') {
  const entry = { time, text: line, stream };
  processes[name].logs.push(entry);        // 超过 2000 行裁剪
  broadcastSSE('log', { service: name, ...entry });  // 同时 SSE 推送
}
```

所有子进程的 stdout/stderr 逐行进 `addLog`，既留存在内存（供 `/api/logs` 拉历史），又实时 SSE 推给前端日志页。

### 3.3 proxy-local 生命周期

- **启动 `startProxyLocal()`**（第 102 行）：
  1. 若已有存活 proc → 返回 `Already running`；若 proc 句柄是僵尸则清理。
  2. `getJarPath()` 找 fat jar，没有则提示先 Build。
  3. `killProcessOnPort(1080)` 先清掉占用 1080 的残留进程。
  4. `spawn('java', ['-Dproxy.dns.nameservers=114.114.114.114,223.5.5.5', '-jar', jar])`。
  5. 监听 stdout/stderr，命中 `started on port` 等关键字即置 `running`。
  6. 5s 后兜底：若仍 `starting` 且 1080 在监听则置 `running`。
- **停止 `stopProxyLocal()`**（第 174 行）：
  - 无受管 proc 但 1080 有人 → `killProcessOnPort(1080)` 清孤儿。
  - 有 proc → macOS `SIGTERM`、Windows `taskkill /F /T`；3s 后仍在则强杀 + 清端口。

> 关键设计：proxy-local 始终带 `-Dproxy.dns.nameservers=114...` 启动，**与 TUN 独立**。这也是「停 TUN 时要连带停 proxy-local」的根因（见 3.4）。
>
> 端口复用（已修复的冲突）：`startProxyLocal` 在启动前先用 `isPortListening(1080)` 检测，若 1080 已在监听（例如先开了系统代理、或外部 start-tun.sh 起过）则**复用现有监听、跳过启动**（返回 `{ ok:true, reused:true }`），不再无脑 `killProcessOnPort(1080)` 重启，避免瞬断正在服务的连接。

### 3.4 tun-adapter 生命周期（最复杂）

tun-adapter 需要 **root 权限**创建虚拟网卡、改路由和 DNS，跨平台启动策略不同：

- **macOS 启动**（`startTunMacOS` → 失败回退 `startTunViaSudo`）：
  1. 先尝试直接 `spawn(bin, ['-c', TUN_CONFIG], { detached: true })`。
  2. 直接 spawn 抛错（EPERM）或运行后 error → 回退 `spawn('sudo', ['-n', bin, ...])`（依赖免密 sudo）。
  3. 写 PID 到 `.tun-adapter.pid`，日志写 `.tun-adapter.log`。
  4. stderr 出现 `password is required` / `sudo:` → 判定免密未配置，提示跑 `setup-tun-permissions.sh`。
- **Windows 启动**（`startTunWindows`）：要求 Dashboard 以管理员身份运行，spawn EPERM 时提示「以管理员身份运行」。
- **PID/日志文件**：
  - `TUN_PID_FILE = dashboard/.tun-adapter.pid`
  - `TUN_LOG_FILE = dashboard/.tun-adapter.log`（sudo 后台进程的输出靠 tail 这个文件读回，见 `startTunLogTail`）

- **停止 `stopTunAdapter()`**（第 533 行，重点理解）：按多重保险顺序杀进程，并保证网络恢复：
  1. 先杀受管 proc（macOS `process.kill SIGTERM`，Windows `taskkill`）。
  2. 再按 PID 文件杀；macOS 下 root 进程 `SIGTERM` 遇 EPERM → `sudo -n kill`。
  3. 给 **5 秒** 让 tun-adapter 的 Rust `Drop` 处理器完成「删 resolver → 还原 DNS → 删路由 → 还原网关 → flush 缓存」，5s 后仍在才 `SIGKILL` / `sudo -n kill -9`。
  4. 兜底按进程名 `pkill -f tun-adapter`（含 `sudo -n` 版本）。
  5. **连带停 proxy-local**：避免 114 DNS 残留拖慢直连（见下方说明）。
  6. **DNS 兜底**：5.5s 后调 `restoreDnsFallback()`（见第 4 节）。

> 为什么停 TUN 要连带停 proxy-local：proxy-local 永远带 `-Dproxy.dns.nameservers=114...` 启动。若只停 TUN 而 proxy-local 还活着，114 resolver 会继续生效，导致用户关代理后直连变慢。所以 `stopTunAdapter` 里检测到 proxy-local 非 stopped 就一起停。

### 3.5 状态聚合 `getStatusAll()`

```js
function getStatusAll() {
  // 遍历 processes，输出 { status, pid, startedAt, uptime }
  // 若 proxy-local 无受管 proc 但 1080 在监听 → status = 'running (external)'
  // 若 tun-adapter 无受管 proc 但 isTunRunning() → status = 'running (external)'
}
```

`running (external)` 表示「系统里确实跑着，但不是本 Dashboard 启动的」——比如用户先用命令行 `start-tun.sh` 起过。前端据此显示「运行中(外部)」。

### 3.6 TUN 运行检测 `isTunRunning()`（三重判定）

特权进程不一定能用 `process.kill(pid,0)` 探测，所以用三种方法叠加：

1. **PID 文件**：读 `.tun-adapter.pid`，`process.kill(pid,0)`；EPERM 也算「存在」（进程在但非本用户所有）。
2. **网卡/接口**：macOS `ifconfig` 含 `198.18.0.1`（TUN 网关）；Windows PowerShell 查 Wintun 适配器 Status=Up。
3. **进程名**：macOS `pgrep -x tun-adapter`；Windows `tasklist` 查 `tun-adapter.exe`。

---

## 4. DNS 兜底恢复机制

这是「关闭时把网络清理干净」的关键，理解它能避免把用户 DNS 卡死在 FakeDNS（`198.18.0.2`）。

- tun-adapter 的 Rust 端（`route_guard.rs`）在启动时把原 DNS 备份到 `/tmp/tun-adapter-dns-backup.conf`，正常退出时（`Drop`）会还原并删除该备份文件。
- **备份文件存在 = Drop 没跑完 = DNS 没还原**。
- 后端 `restoreDnsFallback()`（第 513 行）：
  - Windows 直接跳过（由 tun-adapter 自身处理）。
  - macOS 若发现备份文件残留 → `sudo -n /bin/bash restore-dns.sh`（项目根目录脚本，与命令行同一套逻辑）。
  - 若 sudo 需要密码 → 提示先跑 `setup-tun-permissions.sh`。
- 调用时机：`stopTunAdapter()` 里用 `setTimeout(restoreDnsFallback, 5500)`，**必须等 Drop 的 5s 窗口过后再查**，否则会和 Drop 竞态、误触发兜底。

---

## 5. 系统代理控制（跨平台）

入口 `getSystemProxy()` / `setSystemProxy(enable)` 按平台分叉。

### 5.0 急救恢复 `resetNetwork()` / `POST /api/reset-network`

与只在检测到备份文件残留时才跑的 `restoreDnsFallback()` 不同，`resetNetwork()` 是用户主动触发的「恢复网络」急救动作，**无论怎么卡住都强制拉回干净状态**：先 `setSystemProxy(false)` 关代理，再（非 Windows）`sudo -n /bin/bash restore-dns.sh` 还原 DNS/路由并删除备份文件。逐步结果放在返回的 `steps` 数组；sudo 需密码时返回明确提示引导跑 `setup-tun-permissions.sh`。适用于用户直接关浏览器/终端、或 force-kill 后无法上网的场景。

### 5.1 macOS

- **读** `getSystemProxyMacOS()`：`networksetup -listallnetworkservices` 找到 Wi-Fi 服务，`-getsocksfirewallproxy` 判断是否 `Enabled: Yes`。
- **写** `setSystemProxyMacOS(enable)`：开启时设置 SOCKS（`127.0.0.1 1080`）+ web/secureweb 代理并打开；关闭时三者 off。
- **特权处理** `runNetworkSetup(args)`（重要模式）：先直接 `networksetup ...`；若报 commit/database/permission 类错误才 `sudo -n /usr/sbin/networksetup ...`；若 sudo 仍需密码 → 抛 `NEEDS_SETUP` 错误，提示跑 `setup-tun-permissions.sh`。

### 5.2 Windows

- **读** `getSystemProxyWindows()`：`reg query` 注册表 `HKCU\...\Internet Settings` 的 `ProxyEnable`。
- **写** `setSystemProxyWindows(enable)`：`reg add` 写 `ProxyServer=socks=127.0.0.1:1080` + `ProxyEnable=1/0`，再用 PowerShell 通知 WinInet 刷新。

---

## 6. 配置读写

| 文件 | 读 | 写 | 格式处理 |
|------|----|----|---------|
| proxy.yml | `getConfig('local')` | `saveConfig('local', data)` | `yaml.js` 的 `parseYaml`/`toYaml` |
| remote.yml | `getConfig('remote')` | `saveConfig('remote', data)` | 同上 |
| tun.toml | `readTunConfig()` | `saveTunConfig(text)` | **纯文本**，后端不解析 TOML |

- proxy/remote 走 YAML 对象往返；保存成功后 `broadcastSSE('config-changed', { type })`。
- tun.toml 只存原文 `raw`，由前端 textarea 直接编辑。
- `yaml.js` 是项目自带的极简实现（非 npm 的 js-yaml），改动时注意它支持的语法子集有限。

---

## 7. HTTP 路由与中间层

- 单个 `http.createServer` 处理所有请求（第 951 行起），手写 if 分支路由，无框架。
- 统一加了 CORS 头，`OPTIONS` 直接 204。
- 辅助：`parseBody(req)`（读 JSON，限 2MB）、`json(res, code, data)`（写 JSON）、`serveStatic`（带 `startsWith(PUBLIC_DIR)` 防目录穿越）。
- **API 清单**见 `FRONTEND.md` 第 6 节（前后端共用同一份契约，避免重复维护）。新增接口时两边文档同步更新。

---

## 8. SSE 实时推送

```js
const sseClients = new Set();
function broadcastSSE(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of sseClients) { try { client.write(msg); } catch { sseClients.delete(client); } }
}
```

- `/api/events` 建立连接后立即推 `connected` + 全量状态，`req.on('close')` 时移除客户端。
- 推送事件：`connected` / `status` / `log` / `config-changed`。
- 任何状态变更后调用 `broadcastSSE('status', getStatusAll())` 是后端的统一约定。

---

## 9. 权限（sudo）方案

### 9.1 一次性配置：`setup-tun-permissions.sh`

为当前用户写一条 `/etc/sudoers.d/simpleplane-tun` 免密规则（用 `visudo -c` 校验语法后再落盘，0440 / root:wheel）：

```
<user> ALL=(root) NOPASSWD: <TUN_BIN> *
<user> ALL=(root) NOPASSWD: /bin/kill *
<user> ALL=(root) NOPASSWD: /usr/bin/pkill -f tun-adapter
<user> ALL=(root) NOPASSWD: /usr/sbin/networksetup
<user> ALL=(root) NOPASSWD: /bin/bash <PROJECT_ROOT>/restore-dns.sh
```

这覆盖了后端所有需要 root 的 `sudo -n ...` 调用：启动 tun-adapter、kill 进程、pkill 清理、改系统代理、DNS 兜底还原。

> 重要：规则里 `<TUN_BIN>` 是**绝对路径**。若重新编译到别的目录，需重新跑该脚本，否则免密失效。移除：`sudo rm /etc/sudoers.d/simpleplane-tun`。

### 9.2 后端的「无 sudo → sudo -n」统一模式

后端从不交互式等密码。所有特权调用都遵循：

```
先直接执行（不带 sudo）
  └─ 失败且是权限类错误 → sudo -n 重试
       └─ sudo -n 报 "password is required" → 返回明确提示，引导跑 setup 脚本
```

这个模式出现在 `runNetworkSetup`、`stopTunAdapter`、`restoreDnsFallback`、`startTunViaSudo` 等多处。新增特权操作请复用同一模式，**禁止**用会阻塞的交互式 sudo。

---

## 10. 跨平台差异速查

| 能力 | macOS | Windows |
|------|-------|---------|
| TUN 启动 | 直接 spawn → 失败回退 `sudo -n` | 需管理员身份运行 Dashboard |
| 杀进程 | `process.kill` / `sudo -n kill` / `pkill` | `taskkill /F /T` / 按镜像名 |
| TUN 检测 | `ifconfig` 含 198.18.0.1 / `pgrep` | PowerShell 查 Wintun / `tasklist` |
| 系统代理 | `networksetup`（+sudo 兜底） | `reg add` 注册表 + PowerShell 刷新 |
| 编译 proxy-local | `mvn` | `mvn.cmd` |
| 编译 tun-adapter | `cargo build --release` | 同 |
| DNS 兜底 | `restore-dns.sh` | 由 tun-adapter 自身处理 |

---

## 11. 编译（Build）

`buildService(name)`（第 736 行）用 `spawn(..., { shell: true })` 异步编译并把输出转发到日志：

- `proxy-local` → `mvn package -pl proxy-local -am -DskipTests`（Windows 用 `mvn.cmd`）
- `tun-adapter` → 在 `tun-adapter/` 下 `cargo build --release`

退出码 0 返回 `{ ok: true }`，否则 `{ ok: false, error }`。前端 Build 按钮即调 `/api/service/build`。

---

## 12. 优雅关闭

`gracefulShutdown(signal)` 同时绑定到 `SIGINT` 和 `SIGTERM`（带 `shuttingDown` 防重入）：Dashboard 被 Ctrl+C 或 `kill`（TERM）时，先停 proxy-local（SIGTERM/taskkill）和 tun-adapter（`sudo -n kill`/taskkill），然后等 2s（留给 tun-adapter Drop 还原 DNS）后**调 `restoreDnsFallback()` 做 DNS 兜底**，再 `process.exit(0)`。这保证退出后用户不会被困在 FakeDNS（198.18.0.2）。

> 仍有局限：若用户直接关终端窗口或 `kill -9` 掉 Dashboard（SIGKILL 不可捕获），清理仍不会触发；此时需用户点「恢复网络」按钮（`/api/reset-network`）手动拉回。启动时残留自动检测仍待补。

---

## 13. 排障指南

| 现象 | 可能原因 | 排查 |
|------|---------|------|
| TUN 启动失败、日志提示 sudo 需要密码 | 免密未配置 | 跑 `dashboard/setup-tun-permissions.sh` |
| TUN 启动 EPERM（macOS） | Dashboard 在沙盒/IDE 终端里，spawn sudo 被拒 | 从真实终端 `node server.js` |
| 关 TUN 后上不了网 / DNS 异常 | Drop 未跑完且兜底失败 | 手动 `sudo ./restore-dns.sh`；检查 `/tmp/tun-adapter-dns-backup.conf` 是否残留 |
| proxy-local 显示「运行中(外部)」 | 命令行已起过 / 1080 被占 | `lsof -i :1080`；必要时停掉外部进程 |
| 系统代理开关无效 | networksetup 权限/服务名不对 | 看返回 error；确认 Wi-Fi 服务名；检查免密 networksetup 规则 |
| 改了 server.js 不生效 | 后端需重启 | 重启 `node server.js`（前端改 `public/` 刷新即可） |
| 编译失败 | mvn/cargo 环境缺失 | 看 `/api/logs` 输出；确认 Java/Maven/Rust 工具链 |

调试技巧：
- 服务日志：`/api/logs?service=proxy-local|tun-adapter` 或 SSE `log` 事件。
- tun-adapter 后台输出额外落在 `dashboard/.tun-adapter.log`。
- Dashboard 自身日志看 `node server.js` 的终端。

---

## 14. 新增后端功能 Checklist

1. 在 `http.createServer` 路由段加 `/api/xxx` 分支，返回 `{ ok, ... }`。
2. 业务逻辑写成独立函数，跨平台用 `IS_WIN`/`IS_MAC` 分叉。
3. 外部命令用 `execSync`（一次性）或 `spawn`（长驻），全部 `try/catch` 降级。
4. 特权操作走「无 sudo → `sudo -n` 兜底 → 提示 setup」模式；如需新 sudo 命令，记得同步更新 `setup-tun-permissions.sh` 的 sudoers 规则。
5. 状态变更后 `broadcastSSE('status', getStatusAll())`。
6. 需要 root 的新进程，写好 PID 文件、日志文件，并保证 `stop` 时能清理干净（含 DNS/路由还原）。
7. 在 `FRONTEND.md` 第 6 节同步 API 契约。
8. 自测：正常流程 + 权限不足 + 进程异常退出 + 跨平台（至少 macOS）。

---

## 15. 已知问题 / 待改造点

- **Block 1**：dashboard 自带的 TUN 启动逻辑与项目根目录 `start-tun.sh` 是两套实现，未复用，功能不对等（无按需编译就绪检测、无完整就绪等待）。建议改为薄壳，直接调根目录脚本。
- **Block 2**：权限/编译是「事后报错」，缺少启动前的环境自检引导。
- **Block 3（部分完成）**：已新增 `resetNetwork()` / `POST /api/reset-network` 急救接口与前端「恢复网络」按钮，并让 `gracefulShutdown` 在 SIGINT/SIGTERM 退出时跑 DNS 兜底；仍待补：启动时残留自动检测、SIGKILL/直接关终端场景的清理。
- **端口冲突（已修复）**：`startProxyLocal` 检测到 1080 已监听则复用/跳过启动，不再重复拉起 proxy-local。

> 上述问题在补齐前，README 已将 Dashboard 标注为「暂未开放」，仅供开发使用。
