# SimplePlane Update Server

SimplePlane 桌面客户端的自托管更新分发服务。设计目标与改造细节见 [docs/redesign.md](docs/redesign.md)。

## 能力

- **公开接口**（无认证只读，供 Tauri Updater 客户端使用）
  - `GET /healthz` 健康检查
  - `GET /latest.json` Tauri Updater 静态清单（双平台合并）
  - `GET /api/v1/updates/{target}/{arch}/{current_version}` 动态接口，返回 204 或单平台 JSON
  - `GET|HEAD /download/{platform}/{version}/{file}` 安装包下载（支持 Range，长期缓存）
- **管理接口**（认证后可写）
  - `POST /admin/login` / `POST /admin/logout` / `GET /admin/session`
  - `GET /admin/versions?platform=` / `GET /admin/versions/{id}` / `PATCH`（只改 notes）/ `DELETE`（非 latest）
  - `POST /admin/versions/{id}/publish` 切换 latest（再次单调递增校验）
  - `POST /admin/uploads` 创建 upload session
  - `HEAD /admin/uploads/{id}/{package,signature}` 查 offset（断点续传入口）
  - `PATCH /admin/uploads/{id}/{package,signature}` 追加分片
  - `POST /admin/uploads/{id}/complete` 完成（校验 sha256 + 签名格式 + 文件名配对）
  - `GET /admin/audit?limit=` 审计日志
  - `GET /admin` 控制面板 Web UI
  - `GET /admin/static/{file}` 静态资源

## 设计约束

- **服务端不执行客户端卸载/安装**。Tauri Updater 客户端负责下载、验签、原子替换、重启。
- **私钥只在管理员本地**。服务端永远不持私钥、永远不代签。
- **Mac/Windows 双平台独立版本序列**，各自 SemVer 严格单调递增。
- **同一 `(platform, version)` 不可重复上传**。
- **当前 latest 不可删除**；非 latest 可删；notes 可改，其他字段不可改。
- **零运行时第三方依赖**。用 Node 内置 `node:sqlite`、`crypto HMAC`、SRP-6a PAKE 协议（Node `createDiffieHellmanGroup('modp14')` 提供 2048-bit safe prime）。

## 本地运行

需要 Node.js 22 或更高（因为 `node:sqlite` 实验性 API）。SRP verifier 走文件或 systemd `LoadCredential` 注入，不进 `.env`。

```bash
# 1. 生成 SRP verifier（管理员密码 12+ 字符，含大小写+数字+符号）
node --experimental-sqlite scripts/generate-srp-credentials.mjs > ./data/verifier.json
# 或者非交互式（CI 用）
node --experimental-sqlite scripts/gen-verifier-non-tty.mjs 'your-strong-password' > ./data/verifier.json

# 2. 启动服务
HOST=127.0.0.1 PORT=8787 \
DATA_DIR=./data \
PUBLIC_BASE_URL=https://update.example.com \
SESSION_SECRET='32字节随机字符串' \
SRP_VERIFIER_FILE=./data/verifier.json \
node --experimental-sqlite src/server.js
```

环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `8787` | 监听端口 |
| `DATA_DIR` | `./data` | 数据目录（SQLite + releases + uploads） |
| `PUBLIC_BASE_URL` | 请求推导 | 返回给客户端的下载 URL 前缀 |
| `SRP_VERIFIER_FILE` | — | SRP verifier 文件路径（systemd 通过 `LoadCredential` 注入 `%d/srp_verifier`） |
| `SESSION_SECRET` | — | session cookie HMAC 密钥（≥32 字节，必须配置） |
| `MAX_FILE_SIZE` | `1073741824` | 单安装包最大字节（1 GiB） |
| `MAX_CHUNK_SIZE` | `33554432` | 单分片最大字节（32 MiB） |
| `DEFAULT_CHUNK_SIZE` | `8388608` | 创建 session 返回的 chunk size（8 MiB） |
| `UPLOAD_SESSION_TTL_HOURS` | `24` | upload session 有效期 |
| `MAX_ACTIVE_UPLOADS` | `5` | 同时活跃 upload session 上限 |

> `ADMIN_PASSWORD_HASH` 已废弃。旧 `/admin/login` 返回 410 Gone，必须走 `/admin/srp/challenge` + `/admin/srp/verify` 两步握手。详见 [docs/srp-auth-design.md](docs/srp-auth-design.md)。

## 数据目录

```text
data/
├── update-server.db                   # SQLite（versions / upload_sessions / audit_log）
├── releases/
│   ├── windows-x86_64/
│   │   └── 0.2.3/
│   │       ├── SimplePlane_0.2.3_x64-setup.nsis.zip
│   │       └── SimplePlane_0.2.3_x64-setup.nsis.zip.sig
│   └── darwin-aarch64/
│       └── 0.2.4/
│           ├── SimplePlane_0.2.4_aarch64.app.tar.gz
│           └── SimplePlane_0.2.4_aarch64.app.tar.gz.sig
└── uploads/                           # 临时上传分片
    └── {upload_id}/
        ├── package.part
        └── signature.part
```

## 发布流程（管理员视角）

1. 在本地用 Tauri 私钥签 `.zip` / `.tar.gz`，生成 `.sig` 文件。
2. 浏览器打开 `https://update.example.com/admin`，登录。
3. 选择平台 + 填版本号 + 上传安装包和 `.sig`（控制面板自动分块 + 断点续传）。
4. 上传完成后点"发布为 latest"，服务端再次校验单调递增并切换 latest。
5. 桌面客户端启动时（或每 4h 定时）会从 `/api/v1/updates/...` 拉到新版本，弹更新框。

## 迁移从旧 `latest.json`

```bash
node scripts/migrate-latest-json.mjs --data-dir ./data
```

脚本会把 `data/latest.json` 导入 SQLite，把 release 文件移到 `releases/{platform}/{version}/`，并备份原 `latest.json` 为 `.pre-migrate.bak`。

## 测试

```bash
# 单元 + 集成测试
node --experimental-sqlite --test tests/*.test.mjs

# 全链路测试（启动真实 HTTP server，22 个场景）
node --experimental-sqlite scripts/e2e-test.mjs
```

测试覆盖：

- `tests/version.test.mjs` — SemVer parse/compare 单元
- `tests/util.test.mjs` — path/crypto 单元
- `tests/db.test.mjs` — SQLite 表 + 查询单元
- `tests/server.test.mjs` — 公开 API 回归（healthz / latest / updates / download）
- `tests/auth.test.mjs` — session / lockout 单元
- `tests/admin.test.mjs` — 版本 CRUD + audit 集成
- `tests/uploads.test.mjs` — tus 分块 + complete + publish 集成
- `tests/static.test.mjs` — 控制面板静态文件集成
- `scripts/e2e-test.mjs` — 全链路：双平台独立 + 断点续传 + 单调递增 + 删除 latest 保护 + lockout

## 客户端集成

`desktop/src-tauri/tauri.conf.json`：

```json
"plugins": {
  "updater": {
    "endpoints": [
      "https://update.example.com/api/v1/updates/{{target}}/{{arch}}/{{current_version}}"
    ],
    "pubkey": "（与私钥配对的公钥）"
  }
}
```

客户端零代码改动（`desktop/web/app.js` 已接好 `checkForUpdates` + `showUpdateDialog` + `downloadAndInstall` + `relaunch`）。

## 部署

详见 [docs/deployment.md](docs/deployment.md)（Nginx + HTTPS + 备份策略）。

## 相关文档

- [docs/redesign.md](docs/redesign.md) — 改造方案
- [docs/deployment.md](docs/deployment.md) — 部署指南
- [../docs/sdd/update-server/requirements.md](../docs/sdd/update-server/requirements.md) — SDD 规格
- [../docs/sdd/update-server/tasks.md](../docs/sdd/update-server/tasks.md) — SDD 任务清单
- [../docs/sdd/update-server/verification.md](../docs/sdd/update-server/verification.md) — SDD 验证记录
