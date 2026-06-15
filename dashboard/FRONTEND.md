# SimplePlane Dashboard 前端开发文档

> 本文档描述 `dashboard/` 这个 Web 管理面板的前端架构、与后端的交互契约、各页面/模块说明、开发与调试方式，以及新增功能时的规范。文档贴合当前代码实现（`server.js` / `public/` 三件套），既是「现状说明书」，也是「开发规范」。

---

## 1. 技术栈与设计原则

这是一个**零构建、零依赖**的纯静态前端：

- **后端**：单文件 `server.js`，Node.js 原生 `http` 模块，无 Express、无第三方依赖（`package.json` 里 dependencies 为空）。
- **前端**：原生 HTML + CSS + JavaScript（ES2020），无框架、无打包、无 npm 包。
  - `public/index.html` — 单页结构，所有「页面」都是 `<section>`，靠显隐切换。
  - `public/app.js` — 全部前端逻辑，包在一个 IIFE 模块 `App` 里。
  - `public/style.css` — 全部样式。
- **实时通信**：Server-Sent Events（SSE，`EventSource`），后端单向推送状态与日志。

**设计原则（新增代码请遵守）：**

1. 不引入任何前端框架或构建工具，保持「双击即跑、改完刷新即生效」。
2. 前端只通过 `/api/*` 与后端交互，不直接碰文件系统、不执行命令。
3. 所有 DOM 操作走 `app.js` 里的 `$` / `$$` 两个辅助函数，不要散落 `document.querySelector`。
4. 用户可见的提示统一走 `toast()`，不要用 `alert()`。
5. 用户输入渲染进 DOM 前必须经过 `esc()` / `escAttr()` 转义（防 XSS）。

---

## 2. 目录结构

```
dashboard/
├── server.js              # 后端：HTTP 服务 + 进程管理 + 系统代理/DNS 控制 + 配置读写
├── package.json           # 仅声明 "start": "node server.js"
├── yaml.js                # 极简 YAML 解析/序列化（parseYaml / toYaml），proxy.yml/remote.yml 用
├── start-tun.sh           # 【自动生成】裸 sudo 启动 tun-adapter（注意：与项目根目录 start-tun.sh 不是同一个）
├── setup-tun-permissions.sh  # 一次性配置免密 sudo（sudoers 规则）
├── presets/               # 配置预设（*.yml）
└── public/                # ← 前端全部在这里
    ├── index.html         # 单页结构
    ├── app.js             # 前端逻辑（IIFE 模块 App）
    └── style.css          # 样式
```

> 注意：`dashboard/start-tun.sh` 与**项目根目录**的 `start-tun.sh` 是两套不同的实现，后者功能更完整（按需编译 + 就绪检测 + DNS 恢复）。详见根目录 README 与改造计划。

---

## 3. 启动与访问

```bash
cd dashboard
node server.js          # 或 npm start
```

- 默认端口 `3000`，可用环境变量覆盖：`DASHBOARD_PORT=8080 node server.js`
- 启动后访问 `http://localhost:3000`
- 后端会读写项目里的三个配置文件：
  - `proxy-local/src/main/resources/proxy.yml`
  - `proxy-remote/src/main/resources/remote.yml`
  - `tun-adapter/config/tun.toml`

**TUN 模式的权限前提（前端无法绕过）：**

- **macOS**：首次需运行 `./setup-tun-permissions.sh` 配置免密 sudo，且 Dashboard 必须从**真实终端**启动（沙盒/IDE 内置终端可能因 EPERM 无法 spawn sudo）。
- **Windows**：需以**管理员身份**运行 `node server.js`。

前端会通过 `/api/platform` 拿到平台信息，并在 TUN 卡片下展示对应提示（`#tunPermissionHint`）。

---

## 4. 前端整体架构（app.js）

`app.js` 是一个 IIFE，对外只暴露给 HTML 内联 `onclick` 使用的方法：

```js
return {
  startService, stopService, restartService, buildService,
  quickStartProxy, quickStartTun, quickStopAll, toggleSystemProxy,
  loadTunConfig, saveTunConfig, switchLogService, clearLogs,
};
```

### 4.1 模块内的全局状态

| 变量 | 含义 |
|------|------|
| `localConfig` / `remoteConfig` | 当前编辑中的 proxy.yml / remote.yml（JS 对象） |
| `originalLocalJson` / `originalRemoteJson` | 加载时的快照，用于「是否有改动」对比 |
| `hasChanges` | 配置是否被修改（控制保存按钮可用性） |
| `currentSection` | 当前显示的页面名 |
| `currentLogService` | 日志页当前查看的服务 |
| `eventSource` | SSE 连接实例 |
| `platformInfo` | 平台信息（isWindows / isMacOS / platform） |

### 4.2 初始化流程（`init()`）

```
DOMContentLoaded
  → bindNavigation()     绑定侧边栏导航
  → bindActions()        绑定按钮/输入事件
  → bindGlobalKeys()     绑定 Cmd/Ctrl+S 保存
  → loadPlatformInfo()   GET /api/platform，更新平台提示
  → loadConfigs()        GET /api/config/local + /remote，填表单
  → loadStatus()         GET /api/status，刷新服务状态
  → loadTunConfig()      GET /api/config/tun，填 TUN 编辑器
  → connectSSE()         建立 /api/events 长连接
  → startUptimeTimer()   每 10s 兜底拉一次 /api/status
```

### 4.3 统一 API 封装

```js
async function api(path, method = 'GET', body = null) {
  const opts = { method, headers: {} };
  if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  const res = await fetch(`/api${path}`, opts);
  return res.json();
}
```

约定：所有后端响应都是 JSON，且都带 `ok: true/false` 字段；失败时带 `error` 字符串，部分成功带 `message`。**新增接口请保持这个约定。**

---

## 5. 页面（Section）说明

单页应用，5 个 section 通过 `switchSection()` 显隐切换，导航在左侧 `.nav-item`。

| section（data-section） | 标题 | 作用 |
|------------------------|------|------|
| `dashboard` | 控制面板 | 服务卡片（启动/停止/重启/编译）+ 快捷操作 + 系统代理开关 |
| `proxy-config` | 代理配置 | 编辑 proxy.yml：端口、集群策略、负载均衡、远程服务器列表 |
| `tun-config` | TUN 模式 | 直接编辑 tun.toml 原文（textarea） |
| `route` | 路由规则 | 编辑分流：默认路由 + proxyList + directList |
| `logs` | 运行日志 | 查看 proxy-local / tun-adapter 实时日志（SSE 追加） |

切换逻辑：

```js
function switchSection(name) {
  currentSection = name;
  $$('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.section === name));
  $$('.section').forEach(s => { s.hidden = s.dataset.section !== name; });
  // 更新顶栏标题；进入 logs 时主动 loadLogs()
}
```

> 新增页面：在 `index.html` 加一个 `<section data-section="xxx" hidden>`、在侧边栏加 `<a class="nav-item" data-section="xxx">`、在 `switchSection` 的 `titles` 映射里补标题即可。

---

## 6. 后端 API 契约（前端依赖的全部接口）

所有接口前缀 `/api`，响应均为 JSON。下面是前端实际用到的接口。

### 6.1 平台与状态

| 方法 | 路径 | 说明 | 返回关键字段 |
|------|------|------|------|
| GET | `/api/platform` | 平台信息 | `platform`, `isWindows`, `isMacOS`, `arch`, `nodeVersion` |
| GET | `/api/status` | 全量状态 | `services`, `systemProxy`, `platform` |

`/api/status` 的 `services` 结构：

```jsonc
{
  "proxy-local": { "status": "running", "pid": 12345, "startedAt": 1700000000000, "uptime": 60000 },
  "tun-adapter": { "status": "stopped", "pid": null, "startedAt": null, "uptime": 0 }
}
```

`status` 取值：`stopped` / `starting` / `running` / `running (external)`（外部检测到端口/网卡在用，但不是 Dashboard 启动的）。

### 6.2 服务控制

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/service/start` | `{ name }` | name = `proxy-local` 或 `tun-adapter` |
| POST | `/api/service/stop` | `{ name }` | 停止指定服务 |
| POST | `/api/service/restart` | `{ name }` | 停止后重启 |
| POST | `/api/service/build` | `{ name }` | 编译（mvn / cargo），耗时较长 |
| GET | `/api/logs?service=xxx` | — | 拉取最近 500 条日志 |

### 6.3 系统代理

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| GET | `/api/system-proxy` | — | 返回 `{ enabled }` |
| POST | `/api/system-proxy` | `{ enabled }` | 开/关系统代理（macOS networksetup / Windows 注册表） |
| POST | `/api/reset-network` | `{}` | 急救：强制还原 DNS/路由 + 关系统代理，返回 `{ ok, steps }` |

### 6.4 配置读写

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / POST | `/api/config/local` | proxy.yml（POST body：`{ data }`，JS 对象） |
| GET / POST | `/api/config/remote` | remote.yml（POST body：`{ data }`） |
| GET / POST | `/api/config/tun` | tun.toml（GET 返回 `{ raw }`；POST body：`{ raw }` 原文字符串） |
| POST | `/api/yaml/parse` | 辅助：`{ yaml }` → `{ data }` |

### 6.5 预设

| 方法 | 路径 | Body |
|------|------|------|
| GET | `/api/presets` | — |
| POST | `/api/presets/save` | `{ name, data }` |
| POST | `/api/presets/delete` | `{ name }` |

### 6.6 SSE 实时事件

| 路径 | 说明 |
|------|------|
| GET `/api/events` | `text/event-stream`，连接后立即推 `connected` |

事件类型：

| event | data | 前端处理 |
|-------|------|---------|
| `connected` | 全量状态 | 标记连接成功（状态徽标变绿） |
| `status` | services 状态对象 | `updateServiceUI()` 刷新卡片 |
| `log` | `{ service, time, text, stream }` | 若正在看该服务的日志页则追加一行 |
| `config-changed` | `{ type }` | 配置被改动（目前前端未强制处理，可扩展） |

---

## 7. 关键交互流程

### 7.1 服务状态如何刷新（双通道）

前端用「SSE 推送 + 定时轮询」双保险：

- **主通道**：SSE `status` 事件 → `updateServiceUI()` 实时刷新。
- **兜底通道**：`startUptimeTimer()` 每 10s `loadStatus()` 一次，防 SSE 断连后状态僵死。
- 关键操作（start/stop/restart）后还会用 `setTimeout(loadStatus, 2000~4000)` 主动补刷。

`updateServiceUI()` 根据 `status` 设置：状态指示灯 class（running/starting/stopped）、中文标签、卡片高亮、运行时长。

### 7.2 一键操作（快捷按钮）

| 函数 | 行为 |
|------|------|
| `quickStartProxy()` | 启动 proxy-local → 等 3s → 开系统代理 |
| `quickStartTun()` | 启动 proxy-local → 等 3s → 启动 tun-adapter（若弹授权对话框则等 8s） |
| `quickStopAll()` | 关系统代理 → 停 tun-adapter → 停 proxy-local |

> 端口冲突已修复：后端 `startProxyLocal` 现在检测到 1080 已在监听就复用、跳过启动（不再无脑杀进程重启），因此「先开系统代理、再一键 TUN」不会再瞬断 proxy-local。

### 7.3 TUN 权限失败的前端提示

`startService('tun-adapter')` 失败且 error 含「管理员/sudo/EPERM」时：

- Windows → toast 提示「以管理员身份重启 Dashboard」
- macOS → toast 提示「运行 setup-tun-permissions.sh 或从真实终端启动」

另有 `showTerminalHint(cmd)` 可弹出一个带「复制命令」按钮的浮层（当前为预留能力）。

### 7.4 配置编辑与保存

- proxy-config / route 页：表单输入 → `markChanged()` → `collectLocalConfig()` 收集进 `localConfig` → 与 `originalLocalJson` 对比 → 控制保存按钮与「配置已修改」指示。
- 保存：`saveAll()` → POST `/api/config/local` → 成功后更新快照。
- 快捷键：`Cmd/Ctrl + S` 触发保存。
- 远程服务器是动态卡片（`createServerCard`），含 host/port/cipher/cipherKey/ssl 字段。
- TUN 配置是纯文本编辑（textarea），直接存 `raw`，前端不解析 TOML。

---

## 8. 样式约定（style.css）

- 组件类名语义化：`.service-card` / `.quick-btn` / `.nav-item` / `.toast` / `.status-indicator` 等。
- 状态用 class 切换：状态灯 `.status-indicator.running|.starting|.stopped`；卡片 `.card-running|.card-starting`。
- 按钮族：`.btn` + 变体 `.btn-primary / .btn-success / .btn-warning / .btn-danger / .btn-ghost / .btn-accent / .btn-sm / .btn-icon`。
- 开关组件：`.toggle > input[type=checkbox] + .toggle-track > .toggle-thumb`。
- 新增样式请复用既有变量与类名风格，保持深色控制台观感一致。

---

## 9. 本地开发与调试

1. 启动：`cd dashboard && node server.js`，浏览器开 `http://localhost:3000`。
2. 改前端（`public/` 下任意文件）→ **直接刷新浏览器**即可，无需重启 node。
3. 改后端（`server.js`）→ 需要重启 node 进程。
4. 调试 SSE：浏览器 DevTools → Network → `events` 请求可看推送流。
5. 调试接口：DevTools Console 里直接 `fetch('/api/status').then(r=>r.json()).then(console.log)`。
6. 看后端日志：node 进程的终端输出；服务子进程日志走 `/api/logs` 与 SSE `log` 事件。

---

## 10. 安全与注意事项

- **XSS**：任何把用户/配置数据写入 `innerHTML` 的地方，字符串必须 `esc()`，属性值必须 `escAttr()`。`createServerCard`、`appendLogLine` 已遵循，新增渲染务必照做。
- **静态文件防穿越**：`serveStatic` 已校验 `filePath.startsWith(PUBLIC_DIR)`，不要绕过。
- **权限**：TUN/系统代理涉及 sudo 与系统设置，前端只发指令，真正的权限校验与兜底在后端；前端不要假设一定成功，始终根据返回的 `ok` 给用户反馈。
- **DNS/网络清理**：停止 TUN 后若网络异常，后端有 `restore-dns.sh` 兜底；前端已提供「恢复网络」急救按钮（`#btnResetNetwork` → `App.resetNetwork()` → `POST /api/reset-network`），强制还原 DNS/路由并关闭系统代理。

---

## 11. 新增功能的标准步骤（Checklist）

以「新增一个前端功能」为例：

1. **后端**：在 `server.js` 的路由段新增 `/api/xxx`，返回 `{ ok, ... }` 格式。
2. **前端逻辑**：在 `app.js` 写函数，用 `api('/xxx', 'POST', body)` 调用。
3. **暴露方法**：若要被 HTML `onclick` 调用，加进 IIFE 末尾的 `return {...}`。
4. **DOM**：在 `index.html` 加元素，事件优先在 `bindActions()` 里集中绑定（避免到处写内联）。
5. **反馈**：成功/失败统一用 `toast()`。
6. **转义**：渲染动态内容用 `esc()` / `escAttr()`。
7. **状态同步**：若涉及服务状态变化，操作后调用 `loadStatus()` 或依赖 SSE `status` 事件。
8. **样式**：复用既有类名；必要时在 `style.css` 末尾追加。
9. **自测**：刷新页面走一遍正常流程 + 失败流程（断网、权限不足）。

---

## 12. 当前已知问题 / 待改造点（与 Dashboard 改造计划对应）

- **Block 1**：dashboard 自带一套简化的 TUN 启动逻辑，未复用项目根目录的成熟脚本，功能不对等（无按需编译就绪检测、DNS 恢复编排不完整）。
- **Block 2**：权限/认证是「事后报错」而非「事前检测引导」；缺少启动前的环境自检清单。
- **Block 3（部分完成）**：已新增「恢复网络」急救按钮（`/api/reset-network`），并让 Dashboard 进程在 SIGINT/SIGTERM 退出时跑 DNS 兜底还原；仍待补：启动时残留自动检测、以及非信号方式（直接关浏览器）下的清理。
- **端口冲突（已修复）**：系统代理已开（1080 被占）时再起 TUN，现在会复用已有 proxy-local、跳过重复启动。

> 上述问题在补齐前，README 已将 Dashboard 标注为「暂未开放」，仅供开发使用。
