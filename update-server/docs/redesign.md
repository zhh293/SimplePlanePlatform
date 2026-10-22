# Update Server 改造方案：自托管分发 + 控制面板 + 分块上传

> 目标：把 `update-server/` 从一个静态清单 + 下载分发服务，升级为完整的自托管更新分发平台。
>
> 最终形态：管理员通过 Web 控制面板上传 Mac/Windows 安装包（分块 + 断点续传），服务端严格校验版本号单调递增（双平台独立序列），桌面端轮询自托管 endpoint 发现新版本，用户选择性更新到最新版。
>
> 面向对象：SimplePlane 桌面客户端 + 自建更新服务的管理员。

---

## 一、背景与现状盘点

### 1.1 现有实现

当前 `update-server/`（未纳入 git）已具备：

| 能力 | 文件/位置 | 状态 |
| --- | --- | --- |
| 静态清单 `/latest.json` | `src/server.js` `buildStaticManifest` | ✅ 可用 |
| 动态接口 `/api/v1/updates/{target}/{arch}/{current_version}` | `src/server.js` `resolvePlatformUpdate` | ✅ 可用 |
| 下载接口 `/download/{version}/{file}`（支持 HEAD/Range） | `src/server.js` `serveDownload` | ✅ 可用 |
| 版本号解析 + SemVer 比较 | `parseVersion` / `compareVersions` | ✅ 可用 |
| 本地发布脚本 | `scripts/publish-release.mjs` | ✅ 可用（命令行单机版） |
| `latest.json` 原子替换 | `publish-release.mjs` | ✅ 可用 |
| 数据目录结构 `data/releases/{version}/` | 文件约定 | ✅ 可用 |

### 1.2 现有数据模型

```text
data/
├── latest.json            # 单一全局最新版本清单
└── releases/
    └── 0.1.8/
        ├── SimplePlane_0.1.8_x64-setup.nsis.zip
        ├── SimplePlane_0.1.8_x64-setup.nsis.zip.sig
        ├── SimplePlane_0.1.8_aarch64.app.tar.gz
        └── SimplePlane_0.1.8_aarch64.app.tar.gz.sig
```

`latest.json` 示例：

```json
{
  "version": "0.1.8",
  "notes": "修复连接问题，提升稳定性",
  "pub_date": "2026-09-20T08:30:00Z",
  "platforms": {
    "windows-x86_64": {
      "file": "SimplePlane_0.1.8_x64-setup.nsis.zip",
      "signatureFile": "SimplePlane_0.1.8_x64-setup.nsis.zip.sig"
    },
    "darwin-aarch64": {
      "file": "SimplePlane_0.1.8_aarch64.app.tar.gz",
      "signatureFile": "SimplePlane_0.1.8_aarch64.app.tar.gz.sig"
    }
  }
}
```

### 1.3 桌面客户端现状（已完成，无需改动）

- `desktop/src-tauri/Cargo.toml`：`tauri-plugin-updater = "2"` 已引入
- `desktop/src-tauri/src/main.rs`：`.plugin(tauri_plugin_updater::Builder::new().build())` 已注册
- `desktop/src-tauri/capabilities/default.json`：`"updater:default"` 权限已开
- `desktop/src-tauri/tauri.conf.json`：`plugins.updater` endpoint + pubkey 已配置（当前指向 GitHub Release）
- `desktop/web/app.js`：`checkForUpdates()` + `showUpdateDialog()` + 进度条 + `downloadAndInstall` + `relaunch` 全部接好
- `desktop/docs/auto-updater-plan.md`：完整集成文档

**结论**：客户端零改动，只要服务端按 Tauri Updater JSON Schema 返回正确响应，客户端就能正常工作。

### 1.4 缺什么（本次改造范围）

| 能力 | 现状 | 目标 |
| --- | --- | --- |
| Web 控制面板 | 无 | 提供 Mac/Windows 包上传 UI、版本列表、CRUD |
| 分块上传 + 断点续传 | 无 | 支持 100MB+ 包断点续传，网络中断可恢复 |
| 管理员认证 | 无 | 密码登录 + Session Cookie/JWT |
| 双平台独立版本序列 | 共用 `latest.json` | Mac/Windows 各自维护最新版本 |
| 版本单调递增校验 | `publish-release.mjs` 无校验 | 服务端 publish 时强校验 |
| 历史版本 CRUD | 无 | 列表、删除（仅非 latest）、改 notes |
| 元数据存储 | `latest.json` 单文件 | SQLite（或 JSON 文件）存所有版本元数据 |
| 签名校验 | 无 | 上传时验证 `.sig` 与包对应关系 |

---

## 二、目标产品形态

### 2.1 角色与流程

```text
┌────────────────────────────────────────────────────────────────────┐
│                       管理员（Admin）                               │
│                                                                    │
│  本地持有 Tauri 私钥 → 签名 .zip/.tar.gz → 得到 .sig               │
│                                                                    │
│  浏览器 → 控制面板 → 分块上传 .zip + .sig → 服务端校验 + 发布      │
└────────────────────────────┬───────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                  自托管 update-server                              │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐   │
│  │ Admin API    │  │ Public API   │  │  Static Download       │   │
│  │ /admin/*     │  │ /api/v1/*    │  │  /download/...         │   │
│  │ (认证)       │  │ (只读)       │  │  (CDN 可选)            │   │
│  └──────┬───────┘  └──────┬───────┘  └────────────────────────┘   │
│         │                 │                                        │
│         ▼                 ▼                                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  SQLite: versions 表（platform, version, notes, sig, ...）  │   │
│  │  Storage: data/releases/{platform}/{version}/...            │   │
│  └──────────────────────────────────────────────────────────────┘   │
└────────────────────────────┬───────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                    桌面客户端（Tauri Updater）                      │
│                                                                    │
│  启动 + 每 4h 定时 → GET /api/v1/updates/{target}/{arch}/{current} │
│  → 204 No Content（无更新） 或 200 JSON（有更新）                  │
│  → 用户确认 → downloadAndInstall → 验签 → 原子替换 → 重启         │
└────────────────────────────────────────────────────────────────────┘
```

### 2.2 用户故事

1. **管理员上传 Windows 0.2.3**：浏览器登录 → 选平台 Windows → 填版本号 0.2.3 + notes → 分块上传 `.zip` + `.sig`（支持断点续传）→ 服务端校验 sig 匹配 + 版本号严格大于当前 0.2.2 → 发布成功，Windows 客户端下次轮询能拿到 0.2.3。

2. **管理员误传旧版本**：试图上传 Windows 0.2.1（当前已是 0.2.2）→ 服务端拒绝，返回 `409 Conflict: version_older_than_latest`。

3. **管理员修改 notes**：在版本列表点 0.2.2 → 改 notes → 保存。版本号、文件、签名不可改。

4. **管理员删除历史版本**：删 0.2.0（非 latest）→ 文件 + 元数据一起删。尝试删 0.2.2（当前 latest）→ 服务端拒绝 `409: cannot_delete_latest`。

5. **桌面用户拉取**：当前 0.2.0 → GET `/api/v1/updates/windows/x86_64/0.2.0` → 返回 0.2.2 的 JSON（包含 url 和 signature）→ 客户端确认更新 → Tauri Updater 下载并安装。

6. **桌面用户想跨版本升级**：当前 0.1.8 → 直接升到 0.2.2，不需要先升到 0.2.0。服务端只暴露最新版，客户端直接升到最新。

---

## 三、关键设计决策

### 3.1 卸载/安装由 Tauri Updater 负责，服务端不参与

**决定**：服务端只负责"分发 + 元数据 + 静态文件托管"。卸载旧版、安装新版、原子替换、重启，全部由 Tauri Updater 客户端完成。

**理由**：
- Tauri Updater 已经处理 NSIS/`.app.tar.gz` 的原子替换，经过官方验证和大量生产实践。
- 服务端推命令执行卸载会让服务端变成 MDM，复杂度提升一个数量级，且引入"服务端可远程执行客户端命令"的攻击面。
- 用户描述的"进行卸载和安装"实际上 Tauri Updater 已经在做，不需要自己实现。

** implication **：服务端永远只返回 Tauri Updater 规范的 JSON，不返回任何执行命令。

### 3.2 签名谁来做：管理员本地签，服务端不持私钥

**决定**：私钥（`simpleplane.key`）只存在管理员本地或 CI Secrets，服务端只接收已签好的 `.zip` + `.sig`。

**理由**：
- 服务端持私钥 = 服务器被打可投毒所有客户端。
- Tauri Updater 强制验签：客户端会用 `tauri.conf.json` 里内嵌的公钥验签。服务端即使返回伪造包，客户端也会拒绝安装。
- 但即使客户端验签保护，服务端代签仍会让"上传任意文件就能发布"成为可能（因为服务端会自动签）。所以私钥不下服务器。

**上传流程**：
1. 管理员本地：`cargo tauri signer sign -k ~/.tauri/simpleplane.key path/to/SimplePlane_0.2.3_x64-setup.nsis.zip` → 生成 `.sig` 文件。
2. 控制面板同时上传 `.zip` 和 `.sig`。
3. 服务端校验：`.sig` 是合法 base64、非空、与 `.zip` 文件名对应。
4. 服务端不做内容验签（客户端会做），但做"格式 + 命名对应"校验。

### 3.3 版本单调递增：双平台独立序列

**决定**：Mac 和 Windows 各自独立维护版本序列，互不影响。每个平台内部版本号必须严格递增（SemVer 比较）。

**数据模型**：
- 每个 `(platform, version)` 是一条记录。
- 每个平台只有一个 `is_latest = true` 的记录。
- 上传新版本时，服务端比较 SemVer：`new > current_latest`，否则 `409`。

**为什么双平台独立**：
- Mac 和 Windows 发版节奏可能不同（比如 Windows 有紧急 bugfix 先发）。
- 共用 `latest.json` 会导致一方没新版却被迫 bump 版本号。

**为什么严格递增（不能相等、不能回退）**：
- 相等：客户端 `compareVersions(latest, current) <= 0` 会返回 204，等于没更新。
- 回退：一旦发布过 0.2.3，就不该再允许覆盖 0.2.2（即便没人下载过也不行，避免管理员误操作导致历史混乱）。
- 强制约束让"版本即时间戳"，每个版本号只对应一次发布。

### 3.4 "只允许更新到最新版本"

**决定**：Tauri Updater endpoint 只返回当前平台的最新版本，不返回历史版本。客户端无论怎么请求都只能看到 latest。

**实现**：`/api/v1/updates/{target}/{arch}/{current_version}` 查 `versions` 表 `WHERE platform = ? AND is_latest = true`。如果 `latest.version > current` 返回 JSON，否则 204。

**客户端配合**：`desktop/web/app.js` 的 `checkForUpdates()` 已经是这个行为，无需改动。

### 3.5 分块上传 + 断点续传：自实现轻量 tus 协议

**决定**：不引入第三方依赖（保持 Node 零依赖现状），自实现 tus-like 协议。

**协议设计**：

```text
# 1. 创建 upload session
POST /admin/uploads
Content-Type: application/json
{
  "platform": "windows",
  "version": "0.2.3",
  "file_name": "SimplePlane_0.2.3_x64-setup.nsis.zip",
  "file_size": 83886080,
  "signature_file_name": "SimplePlane_0.2.3_x64-setup.nsis.zip.sig",
  "signature_size": 256,
  "sha256": "abc..."            # 客户端预先算好整个文件的 sha256（可选）
}

→ 201 Created
{
  "upload_id": "u_20261022_abc123",
  "package_chunk_size": 8388608,   # 8 MiB，服务端决定
  "package_offset": 0,
  "signature_offset": 0
}
```

```text
# 2. 查询 offset（断点续传入口）
HEAD /admin/uploads/{upload_id}/package
→ 200
  Upload-Offset: 41943040
  Upload-Length: 83886080

HEAD /admin/uploads/{upload_id}/signature
→ 200
  Upload-Offset: 0
  Upload-Length: 256
```

```text
# 3. 追加分片
PATCH /admin/uploads/{upload_id}/package
Upload-Offset: 41943040
Content-Type: application/octet-stream
Content-Length: 8388608
<8 MiB 二进制>

→ 204
  Upload-Offset: 50331648
```

```text
# 4. 完成（两个流都到 offset == length）
POST /admin/uploads/{upload_id}/complete
→ 200 OK
{
  "version_id": "v_20261022_xyz",
  "package_sha256": "...",    # 服务端落地后计算
  "signature_valid": true,
  "published": false          # complete 不等于 published，需显式发布
}
```

```text
# 5. 发布（complete 之后显式调用，做版本单调递增校验）
POST /admin/versions/{version_id}/publish
→ 200 OK
{
  "platform": "windows",
  "version": "0.2.3",
  "is_latest": true,
  "previous_latest": "0.2.2"
}
```

**断点续传**：客户端每次启动上传前先 `HEAD` 查 offset，从 offset 续传。Session 元数据（offset、临时文件路径）持久化到 SQLite `upload_sessions` 表，服务端重启不丢。

**临时文件**：上传中的分片落到 `data/uploads/{upload_id}/package.part` 和 `signature.part`，complete 时原子 rename 到 `data/releases/{platform}/{version}/...`。

**清理**：upload session 24h 未 complete 自动清理（定时任务 + 上传时惰性清理）。

### 3.6 元数据存储：SQLite

**决定**：从单一 `latest.json` 文件迁移到 SQLite。

**理由**：
- `latest.json` 只能存单条 latest，无法支持"历史版本 CRUD"。
- SQLite 是单文件，零部署成本，跟 Node 一起跑。
- 原子事务支持上传 + publish 流程的一致性。

**数据库文件**：`data/update-server.db`（跟 `releases/` 同级）。

**表结构**：

```sql
CREATE TABLE versions (
  id              TEXT PRIMARY KEY,           -- v_20261022_xxx
  platform        TEXT NOT NULL,               -- windows-x86_64 / darwin-aarch64
  version         TEXT NOT NULL,               -- 0.2.3
  notes           TEXT NOT NULL DEFAULT '',
  file_name       TEXT NOT NULL,
  file_size       INTEGER NOT NULL,
  file_sha256     TEXT NOT NULL,
  signature       TEXT NOT NULL,               -- .sig 文件内容（base64）
  is_latest       INTEGER NOT NULL DEFAULT 0,  -- 0/1
  created_at      TEXT NOT NULL,               -- ISO8601
  published_at    TEXT,
  UNIQUE (platform, version)
);

CREATE INDEX idx_versions_platform_latest ON versions(platform, is_latest);

CREATE TABLE upload_sessions (
  id              TEXT PRIMARY KEY,
  platform        TEXT NOT NULL,
  version         TEXT NOT NULL,
  file_name       TEXT NOT NULL,
  file_size       INTEGER NOT NULL,
  file_offset     INTEGER NOT NULL DEFAULT 0,
  file_sha256     TEXT,                        -- 客户端预计算（可选）
  signature_name  TEXT NOT NULL,
  signature_size  INTEGER NOT NULL,
  signature_offset INTEGER NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'active', -- active | completed | expired
  created_at      TEXT NOT NULL,
  expires_at      TEXT NOT NULL
);

CREATE TABLE audit_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  ts              TEXT NOT NULL,
  actor           TEXT NOT NULL,                -- admin | system
  action          TEXT NOT NULL,                -- version.upload / version.publish / version.delete / ...
  platform        TEXT,
  version         TEXT,
  detail          TEXT
);
```

**注意**：Tauri Updater 客户端不直接读 SQLite，服务端 `/api/v1/updates/...` 从 SQLite 动态构造 JSON 响应。

**`latest.json` 兼容**：保留 `/latest.json` 静态接口，从 SQLite 构造响应（双平台合并）。便于 CDN 缓存场景。

### 3.7 管理员认证

**决定**：单密码 + bcrypt + httpOnly Session Cookie。不引入 OAuth/OIDC（单管理员场景）。

**配置**：
- 环境变量 `ADMIN_PASSWORD_BCRYPT`（启动时从 env 读，bcrypt hash）。
- 环境变量 `SESSION_SECRET`（cookie 签名密钥，32+ 字节随机）。
- Session 存内存（单实例够用），进程重启要求重新登录。

**API**：
- `POST /admin/login`（body: `{password}`）→ Set-Cookie `session=...` + 200。
- `POST /admin/logout` → 清 cookie + 204。
- `GET /admin/session` → 返回当前管理员信息或 401。

**所有 `/admin/*` 路由**（除 `/admin/login`）必须带有效 cookie，否则 401。

**生产强化**（可选，后续迭代）：
- 增加失败次数限制（5 次错密码后 15 分钟锁定）。
- 加 IP 白名单（Nginx 层）。
- 双因素 TOTP（如果管理员账号被打）。

### 3.8 轮询策略

**客户端**：`desktop/web/app.js` 启动后 3s 检查一次，之后每 4h 检查一次（`setInterval`）。已有逻辑只需确认 endpoint 指向自托管服务器。

**服务端**：`/api/v1/updates/...` 响应 `Cache-Control: no-store`，确保客户端不命中浏览器缓存。下载接口 `Cache-Control: public, max-age=31536000, immutable`（版本包永不变）。

---

## 四、API 设计

### 4.1 公开接口（客户端用，只读，无认证）

| 方法 | 路径 | 用途 | 状态 |
| --- | --- | --- | --- |
| GET | `/healthz` | 健康检查 | 已有 |
| GET | `/latest.json` | Tauri Updater 静态清单（双平台合并） | 已有，需改读 SQLite |
| GET | `/api/v1/updates/{target}/{arch}/{current_version}` | Tauri Updater 动态接口（返回 204 或单平台 JSON） | 已有，需改读 SQLite |
| GET / HEAD | `/download/{platform}/{version}/{file}` | 安装包下载（支持 Range） | 已有，路径加 `{platform}` 段 |

**动态接口响应 schema**（不变，符合 Tauri Updater 规范）：

```json
{
  "version": "0.2.3",
  "notes": "...",
  "pub_date": "2026-10-22T08:30:00Z",
  "url": "https://update.example.com/download/windows-x86_64/0.2.3/SimplePlane_0.2.3_x64-setup.nsis.zip",
  "signature": "dW50cnVzdGVkIGNvbW1lbnQ..."
}
```

### 4.2 管理接口（控制面板用，需认证）

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/admin/login` | 登录 |
| POST | `/admin/logout` | 登出 |
| GET | `/admin/session` | 当前会话 |
| GET | `/admin/versions?platform={p}` | 版本列表（按平台过滤，按 version desc） |
| GET | `/admin/versions/{id}` | 单版本详情 |
| PATCH | `/admin/versions/{id}` | 修改 notes（仅此字段可改） |
| DELETE | `/admin/versions/{id}` | 删除版本（非 latest 才允许） |
| POST | `/admin/uploads` | 创建上传 session |
| HEAD | `/admin/uploads/{id}/package` | 查询 package offset |
| HEAD | `/admin/uploads/{id}/signature` | 查询 signature offset |
| PATCH | `/admin/uploads/{id}/package` | 追加 package 分片 |
| PATCH | `/admin/uploads/{id}/signature` | 追加 signature 分片 |
| POST | `/admin/uploads/{id}/complete` | 完成上传（合并 + 校验） |
| POST | `/admin/versions/{id}/publish` | 发布（设为 latest，旧 latest 取消） |
| GET | `/admin/audit?limit=100` | 审计日志 |

**错误响应 schema**：

```json
{
  "error": "version_older_than_latest",
  "message": "new version 0.2.1 must be greater than current latest 0.2.2 for platform windows-x86_64",
  "current_latest": "0.2.2",
  "attempted": "0.2.1"
}
```

### 4.3 错误码表

| HTTP | error code | 触发场景 |
| --- | --- | --- |
| 400 | `invalid_version_format` | 版本号不是合法 SemVer |
| 400 | `invalid_file_name` | 文件名包含路径分隔符或特殊字符 |
| 400 | `invalid_signature_format` | `.sig` 文件不是合法 base64 |
| 401 | `unauthorized` | 未登录或 session 过期 |
| 404 | `version_not_found` | 版本 ID 不存在 |
| 404 | `upload_not_found` | upload session 不存在 |
| 409 | `version_already_exists` | `(platform, version)` 已存在 |
| 409 | `version_older_than_latest` | 新版本号 ≤ 当前 latest |
| 409 | `cannot_delete_latest` | 试图删除当前 latest 版本 |
| 409 | `upload_not_complete` | 试图 publish 未 complete 的版本 |
| 410 | `upload_expired` | upload session 已过期 |
| 413 | `file_size_exceeded` | 分片大小超过限制 |
| 422 | `signature_mismatch` | `.sig` 与 `.zip` 命名不对应或格式不符 |

---

## 五、上传流程（分块 + 断点续传 + 校验）

### 5.1 完整时序

```text
管理员浏览器                  update-server                     SQLite + FS
    │                              │                               │
    │ 1. 本地签 .zip → .sig        │                               │
    │                              │                               │
    │ 2. POST /admin/uploads       │                               │
    │    {platform, version,       │                               │
    │     file_name, file_size,   │                               │
    │     signature_name,          │                               │
    │     signature_size}          │                               │
    │─────────────────────────────▶│                               │
    │                              │ 2a. 校验 version > latest      │
    │                              │ 2b. 校验 (platform,version)   │
    │                              │     未存在                    │
    │                              │ 2c. 创建 upload_session 行     │
    │                              │ 2d. mkdir data/uploads/{id}   │
    │                              │─────INSERT──────────────────▶│
    │                              │                               │
    │ 3. ◀────────────────────────│ 201 {upload_id, chunk_size}  │
    │                              │                               │
    │ 4. PATCH /uploads/{id}/package                               │
    │    Upload-Offset: 0           │                               │
    │    <8 MiB 二进制>             │                               │
    │─────────────────────────────▶│                               │
    │                              │ 4a. 校验 offset == 当前 file_offset
    │                              │ 4b. append to package.part    │
    │                              │ 4c. UPDATE file_offset        │
    │                              │─────UPDATE──────────────────▶│
    │                              │                               │
    │ 5. ◀────────────────────────│ 204 Upload-Offset: 8388608   │
    │                              │                               │
    │   ... 重复 4-5 直到 file_offset == file_size ...            │
    │                              │                               │
    │ 6. PATCH /uploads/{id}/signature（同上流程，文件小通常一片）│
    │                              │                               │
    │ 7. POST /uploads/{id}/complete                                │
    │─────────────────────────────▶│                               │
    │                              │ 7a. 校验 file_offset == file_size
    │                              │ 7b. 校验 signature_offset == signature_size
    │                              │ 7c. 计算 package sha256      │
    │                              │ 7d. 读 .sig 内容，校验非空    │
    │                              │ 7e. 校验 .sig 文件名对应 .zip│
    │                              │ 7f. mkdir releases/{platform}/{version}
    │                              │ 7g. rename package.part → 最终文件名
    │                              │ 7h. rename signature.part → 最终 .sig
    │                              │ 7i. INSERT versions 行 (is_latest=0, published_at=NULL)
    │                              │ 7j. UPDATE upload_sessions status=completed
    │                              │─────TX─────────────────────▶│
    │                              │                               │
    │ 8. ◀────────────────────────│ 200 {version_id, sha256,     │
    │                              │       signature_valid,        │
    │                              │       published: false}       │
    │                              │                               │
    │ 9. POST /admin/versions/{id}/publish                         │
    │─────────────────────────────▶│                               │
    │                              │ 9a. TX 开始                  │
    │                              │ 9b. 查当前 latest            │
    │                              │ 9c. 再次校验 new > current   │
    │                              │     （防 complete 后被改）  │
    │                              │ 9d. UPDATE 旧 latest         │
    │                              │     is_latest=0              │
    │                              │ 9e. UPDATE 新版本            │
    │                              │     is_latest=1,             │
    │                              │     published_at=now()       │
    │                              │ 9f. INSERT audit_log          │
    │                              │─────TX──────────────────────▶│
    │                              │                               │
    │ 10. ◀───────────────────────│ 200 {platform, version,      │
    │                              │      is_latest: true,         │
    │                              │      previous_latest: 0.2.2} │
```

### 5.2 分块大小

- 服务端决定 chunk size，默认 **8 MiB**（Windows NSIS zip 通常 50-150MB，8MiB ≈ 10-20 片）。
- 客户端按服务端给的 `package_chunk_size` 切片。
- 单片最大 32 MiB（防止恶意大分片耗尽内存）。

### 5.3 并发与顺序

- package 和 signature 两条流独立，可并行上传（signature 小，通常一片完成）。
- 同一条流内**必须顺序**（offset 递增），服务端拒绝非连续 offset 的 PATCH（`416 Range Not Satisfiable`）。

### 5.4 断点续传

- 客户端上传中断 → 重新启动浏览器 → 重新登录 → 在控制面板点"继续上传"。
- 客户端先 `HEAD /admin/uploads/{id}/package` 和 `/signature` 拿当前 offset → 从 offset 续传。
- upload session 24h 内有效，超期清理时同步删除 `data/uploads/{id}/` 临时文件。

### 5.5 完整性校验

- **必选**：服务端 complete 时计算 package 落地后的 sha256，存入 `versions.file_sha256`。
- **可选**：客户端创建 session 时传 `sha256`（整个文件的预计算值），服务端 complete 时比对，不一致则 `422 file_checksum_mismatch`。
- **签名格式校验**：`.sig` 文件内容 base64 decode 后长度 > 0，且文件名与 `.zip` 严格对应（`xxx.zip` ↔ `xxx.zip.sig`，或 `xxx.app.tar.gz` ↔ `xxx.app.tar.gz.sig`）。

---

## 六、版本 CRUD 与单调递增约束

### 6.1 约束矩阵

| 操作 | 平台 | 目标版本状态 | 约束 | 结果 |
| --- | --- | --- | --- | --- |
| Upload（complete） | win/mac | 新版本号 > 当前 latest | SemVer 严格大于 | 允许，`is_latest=0` |
| Upload（complete） | win/mac | 新版本号 ≤ 当前 latest | — | 拒绝 409 `version_older_than_latest` |
| Upload（complete） | win/mac | `(platform, version)` 已存在 | — | 拒绝 409 `version_already_exists` |
| Publish | win/mac | 新版本 > 当前 latest（再次校验） | — | 允许，切换 `is_latest` |
| Publish | win/mac | 新版本 ≤ 当前 latest | — | 拒绝 409（防 complete 后 latest 被改） |
| Delete | win/mac | 目标版本是 latest | — | 拒绝 409 `cannot_delete_latest` |
| Delete | win/mac | 目标版本非 latest | — | 允许，删文件 + 元数据 |
| Patch notes | win/mac | 任意状态 | 只改 notes | 允许 |
| Patch version | win/mac | — | 不可改 | 拒绝 422 |
| Patch file | win/mac | — | 不可改 | 拒绝 422 |

### 6.2 删除流程

1. `DELETE /admin/versions/{id}`
2. 校验 `is_latest = 0`（是 latest 拒绝）
3. TX：删 SQLite 行 + `rm -rf data/releases/{platform}/{version}/`
4. INSERT `audit_log`
5. 文件删除失败不回滚 SQLite（只记 audit log `file_cleanup_failed`，管理员手动清理）

### 6.3 修改 notes

```text
PATCH /admin/versions/{id}
Content-Type: application/json
{ "notes": "新的更新说明" }

→ 200 OK
{ "id": "...", "notes": "新的更新说明", "updated_at": "..." }
```

只允许改 `notes` 字段，其他字段（version、file_name、signature、is_latest）不可改。

---

## 七、客户端集成（改动极小）

### 7.1 Tauri 配置

`desktop/src-tauri/tauri.conf.json`：

```json
"plugins": {
  "updater": {
    "endpoints": [
      "https://update.example.com/api/v1/updates/{{target}}/{{arch}}/{{current_version}}"
    ],
    "pubkey": "（保持原 pubkey 不变）"
  }
}
```

把 endpoint 从 GitHub releases 改为自托管服务器。`pubkey` 跟私钥配对，不能换。

### 7.2 客户端轮询

`desktop/web/app.js` 现有：

```javascript
setTimeout(checkForUpdates, 3000);
// （可选）加：setInterval(checkForUpdates, 4 * 60 * 60 * 1000);
```

无需改动。

### 7.3 客户端 UI

现有 `showUpdateDialog(update)` 已支持：
- 显示版本号 + notes
- 立即更新 / 稍后提醒按钮
- 下载进度条
- 失败提示

可选增强（后续迭代）：
- 设置页面加"检查更新"按钮手动触发。
- 显示当前版本号 vs 最新版本号对比。

---

## 八、目录结构变化

### 8.1 改造前

```text
update-server/
├── README.md
├── package.json
├── data/
│   ├── latest.json
│   └── releases/
│       └── 0.1.8/
│           └── ...
├── scripts/
│   └── publish-release.mjs
├── specs/
├── src/
│   └── server.js
└── tests/
    └── server.test.mjs
```

### 8.2 改造后

```text
update-server/
├── README.md                          # 更新
├── package.json                       # 保持零依赖
├── data/                              # gitignore
│   ├── update-server.db               # SQLite
│   ├── releases/
│   │   ├── windows-x86_64/
│   │   │   ├── 0.2.2/
│   │   │   │   ├── SimplePlane_0.2.2_x64-setup.nsis.zip
│   │   │   │   └── SimplePlane_0.2.2_x64-setup.nsis.zip.sig
│   │   │   └── 0.2.3/
│   │   │       └── ...
│   │   └── darwin-aarch64/
│   │       ├── 0.2.2/
│   │       │   ├── SimplePlane_0.2.2_aarch64.app.tar.gz
│   │       │   └── SimplePlane_0.2.2_aarch64.app.tar.gz.sig
│   │       └── ...
│   └── uploads/                       # 临时上传分片
│       └── {upload_id}/
│           ├── package.part
│           └── signature.part
├── docs/
│   └── redesign.md                    # 本文档
├── public/                            # 控制面板静态资源
│   ├── index.html
│   ├── app.js
│   └── style.css
├── scripts/
│   ├── publish-release.mjs            # 保留（CLI 发布，兼容老流程）
│   └── migrate-latest-json.mjs        # 新增：把现有 latest.json 导入 SQLite
├── src/
│   ├── server.js                      # 入口，挂载多个 router
│   ├── db.js                          # 新增：SQLite 初始化 + 查询
│   ├── auth.js                        # 新增：session + bcrypt
│   ├── manifest.js                    # 新增：从 SQLite 构造 Tauri 响应
│   ├── uploads.js                     # 新增：分块上传协议
│   ├── admin.js                       # 新增：管理 API
│   ├── public.js                      # 重命名：公开接口（healthz/latest/updates/download）
│   └── util/
│       ├── version.js                 # 抽离：SemVer parse/compare
│       ├── path.js                    # 抽离：safeSegment/path 校验
│       └── http.js                    # 抽离：json/noContent/error
└── tests/
    ├── server.test.mjs                # 更新
    ├── uploads.test.mjs               # 新增
    ├── admin.test.mjs                 # 新增
    └── version.test.mjs               # 新增
```

### 8.3 `package.json` 变化

保持 `type: module` 和零运行时依赖。新增 `better-sqlite3`？

**决定**：**不用** `better-sqlite3`（原生编译，部署麻烦），改用 `node:sqlite`（Node 22+ 内置，实验性 API 但稳定可用）。如果担心实验性，备选 `sql.js`（纯 JS，但慢）。

```json
{
  "name": "simpleplane-update-server",
  "version": "0.2.0",
  "private": true,
  "type": "module",
  "engines": {
    "node": ">=22"
  },
  "scripts": {
    "start": "node --experimental-sqlite src/server.js",
    "migrate": "node --experimental-sqlite scripts/migrate-latest-json.mjs",
    "test": "node --test --experimental-sqlite"
  }
}
```

> 如果 `node:sqlite` API 后续变化，迁移到 `better-sqlite3` 只改 `src/db.js` 一个文件。

---

## 九、安全考量

### 9.1 强制 HTTPS

- 生产部署必须 Nginx/Caddy 终止 HTTPS，反向代理到 `127.0.0.1:8787`。
- 控制面板 cookie 带 `Secure` + `HttpOnly` + `SameSite=Lax`。
- 公开下载接口允许 HTTP（但建议也走 HTTPS，避免被中间人篡改；Tauri Updater 客户端会验签，即使被篡改也会拒绝）。

### 9.2 签名链不可绕过

- 客户端 `tauri.conf.json` 的 `pubkey` 是唯一信任锚。私钥只在管理员本地 / CI Secrets。
- 服务端永远不持有私钥，永远不代签。
- 即便服务端被完全攻破，攻击者上传的伪造包没有合法 `.sig`，客户端验签失败，拒绝安装。

### 9.3 上传接口防护

- 单 IP 登录失败 5 次 → 15 分钟锁定（内存计数器）。
- upload session 单管理员同时最多 5 个活跃（防止资源耗尽）。
- 单分片最大 32 MiB，单文件最大 1 GiB（NSIS zip 不会超过，留余量）。
- 文件名严格白名单：`^[A-Za-z0-9._-]+$`，且必须以 `.zip`/`.tar.gz`/`.sig` 结尾。

### 9.4 审计日志

所有管理操作写 `audit_log` 表：

| action | 字段 |
| --- | --- |
| `version.upload` | platform, version |
| `version.publish` | platform, version, previous_latest |
| `version.delete` | platform, version |
| `version.patch` | platform, version, field |
| `upload.session.create` | platform, version, upload_id |
| `upload.session.expire` | upload_id |
| `admin.login.success` / `admin.login.failed` | actor_ip |

### 9.5 速率限制

- `/api/v1/updates/*`：单 IP 每分钟 60 次（足够桌面客户端轮询，超出可能是异常）。
- `/admin/login`：单 IP 每分钟 5 次。
- `/admin/uploads`：单 session 每分钟 30 次操作。

---

## 十、实施分阶段

### Phase 1：数据库 + 基础改造（预计 0.5 天）

- [ ] 引入 `node:sqlite`，建表（`versions` / `upload_sessions` / `audit_log`）
- [ ] 抽离 `src/util/version.js`、`src/util/path.js`、`src/util/http.js`
- [ ] 把 `src/server.js` 拆为 `public.js` + `admin.js` + `uploads.js` + `auth.js` + `db.js` + `manifest.js`
- [ ] `manifest.js`：从 SQLite 构造 `/latest.json` 和 `/api/v1/updates/...` 响应
- [ ] 写 `scripts/migrate-latest-json.mjs`：把现有 `data/latest.json` 导入 SQLite
- [ ] 更新 `tests/server.test.mjs` 覆盖 SQLite 路径
- [ ] **验证**：`npm test` 通过 + 启动服务 + 浏览器访问 `/latest.json` 返回正确

### Phase 2：管理员认证 + 版本列表 API（预计 0.5 天）

- [ ] `src/auth.js`：bcrypt 验证 + session cookie 签发
- [ ] `POST /admin/login` / `POST /admin/logout` / `GET /admin/session`
- [ ] `GET /admin/versions` + `GET /admin/versions/{id}` + `PATCH /admin/versions/{id}`（只改 notes）+ `DELETE /admin/versions/{id}`（非 latest）
- [ ] `GET /admin/audit`
- [ ] 写 `tests/admin.test.mjs`：登录、CRUD、权限、latest 保护
- [ ] **验证**：未登录访问 `/admin/versions` 返回 401；登录后能 CRUD；删 latest 返回 409

### Phase 3：分块上传 + 断点续传（预计 1 天）

- [ ] `src/uploads.js`：完整 tus-like 协议（POST create / HEAD offset / PATCH chunk / POST complete）
- [ ] 临时文件落盘 `data/uploads/{id}/`
- [ ] upload session 24h 过期 + 惰性清理定时任务
- [ ] `POST /admin/versions/{id}/publish`：切换 `is_latest`，强校验单调递增
- [ ] 写 `tests/uploads.test.mjs`：创建/续传/complete/publish/过期
- [ ] **验证**：手动 curl 分块上传一个 20MB 文件 → 中断 → 续传 → complete → publish → `/api/v1/updates/...` 返回新版本

### Phase 4：控制面板 Web UI（预计 1 天）

- [ ] `public/index.html` + `public/app.js` + `public/style.css`
- [ ] 登录页 + 版本列表页 + 上传页（分块 + 进度 + 断点续传）
- [ ] 版本详情页（改 notes、删除）
- [ ] 审计日志页
- [ ] **验证**：浏览器全流程：登录 → 上传一个已签名的 0.2.3 → publish → 旧版客户端启动后检测到更新

### Phase 5：文档 + 部署脚本（预计 0.5 天）

- [ ] 更新 `update-server/README.md`：部署、环境变量、迁移步骤
- [ ] 新增 `update-server/docs/deployment.md`：Nginx 配置示例、HTTPS 证书申请、备份策略
- [ ] 把 `update-server/` 加入 git（当前未跟踪）
- [ ] 在 `docs/design/` 建一份总览文档，链接到本 redesign.md 和 `desktop/docs/auto-updater-plan.md`
- [ ] **验证**：从零部署到一台新 Linux 服务器：`git pull` → `npm install`（零依赖，只初始化 db）→ `npm start` → 上传一个包 → 客户端能更新

---

## 十一、环境变量

```bash
# 服务监听
HOST=127.0.0.1
PORT=8787

# 数据目录
DATA_DIR=/opt/simpleplane-update/data

# 公开下载基础 URL（影响返回给客户端的下载 URL）
PUBLIC_BASE_URL=https://update.example.com

# 管理员认证
ADMIN_PASSWORD_BCRYPT=$2b$12$...（用 bcrypt hash "your-password" 生成）
SESSION_SECRET=<32+ 字节随机字符串>

# 上传限制
MAX_FILE_SIZE=1073741824          # 1 GiB
MAX_CHUNK_SIZE=33554432           # 32 MiB
DEFAULT_CHUNK_SIZE=8388608        # 8 MiB
UPLOAD_SESSION_TTL_HOURS=24
MAX_ACTIVE_UPLOADS=5

# 速率限制
RATE_LIMIT_UPDATES_PER_MINUTE=60
RATE_LIMIT_LOGIN_PER_MINUTE=5

# 日志
LOG_LEVEL=info
```

---

## 十二、验证清单

完成 Phase 1-5 后按以下流程端到端验证：

### 12.1 单元 & 集成测试

- [ ] `npm test` 全绿
- [ ] `tests/version.test.mjs` 覆盖 SemVer parse/compare 边界（ prerelease、build metadata）
- [ ] `tests/admin.test.mjs` 覆盖：未登录 401、登录成功 200、错密码 401、5 次错密码锁定
- [ ] `tests/uploads.test.mjs` 覆盖：创建 session、连续 PATCH、非连续 offset 拒绝、complete 校验、publish 单调递增、delete latest 拒绝

### 12.2 端到端流程

- [ ] 启动 `update-server`，浏览器打开 `https://update.example.com/admin`
- [ ] 登录成功
- [ ] 上传 Windows 0.2.3（`.zip` + `.sig`）：分块上传进度正常、断点续传能恢复
- [ ] 尝试上传 Windows 0.2.1（小于当前 latest 0.2.2）→ 409 `version_older_than_latest`
- [ ] 尝试上传 Windows 0.2.3（重复）→ 409 `version_already_exists`
- [ ] Publish 0.2.3 → 成功，previous_latest = 0.2.2
- [ ] 尝试删除 0.2.3（current latest）→ 409 `cannot_delete_latest`
- [ ] 删除 0.2.0（历史版本）→ 成功
- [ ] 修改 0.2.2 notes → 成功
- [ ] 修改 0.2.2 version → 422

### 12.3 客户端验证

- [ ] 把 `desktop/src-tauri/tauri.conf.json` 的 endpoint 改为自托管 URL
- [ ] 安装旧版（0.2.2）桌面客户端
- [ ] 启动 → 3s 后弹更新对话框，显示 0.2.3 + notes
- [ ] 点"立即更新" → 进度条正常推进 → 下载完成 → 自动重启 → 版本号变 0.2.3
- [ ] 再次启动 → 不再提示更新（已是 latest）

### 12.4 安全验证

- [ ] 直接 `curl -X POST /admin/versions/...` 不带 cookie → 401
- [ ] 用错误的 `.sig`（伪造）上传 → 422 `signature_mismatch`
- [ ] 上传超大分片（>32 MiB）→ 413
- [ ] 5 次错密码 → 15 分钟锁定
- [ ] HTTPS 证书有效，HTTP 自动 301 到 HTTPS

---

## 十三、开放问题

以下问题先不决定，留待 Phase 1 实施前再拍板：

1. **是否保留 `scripts/publish-release.mjs` CLI**：保留作为 CI 自动发布的命令行入口（不依赖浏览器），还是完全由控制面板取代？倾向保留。
2. **CDN 加速**：`/download/*` 是否前置 CDN（Cloudflare/阿里云）？如果前置，`PUBLIC_BASE_URL` 改为 CDN 域名。后续迭代决定。
3. **多管理员**：当前只设计单管理员。未来要不要支持多管理员 + RBAC？等有需求再说。
4. **灰度发布**：当前只支持"全量 publish 为 latest"。未来要不要支持"先给 10% 用户推，再全量"？可以在 `versions` 表加 `rollout_percentage` 字段，`/api/v1/updates` 按比例返回 204 或 200。后续迭代。
5. **回滚**：当前禁止删 latest。要不要支持"快速回滚到上一个 latest"？可以在 `versions` 表加 `is_latest` 切换接口（把当前 latest 取消、把某个历史版本重设为 latest），但前提是新 latest 版本号 > 客户端当前版本，否则用户拿不到。**倾向不支持**：版本号是时间戳，回滚违背单调递增原则。真要回滚就 bump 一个 0.2.4 引用 0.2.2 的代码。

---

## 十四、相关文档

- `desktop/docs/auto-updater-plan.md` — 桌面客户端 Tauri Updater 集成方案
- `update-server/README.md` — update-server 使用说明（本次改造后会更新）
- `update-server/src/server.js` — 当前实现（改造基准）
- `desktop/src-tauri/tauri.conf.json` — Updater 插件配置（endpoint 指向自托管服务器）
- `desktop/web/app.js` — 客户端 `checkForUpdates()` / `showUpdateDialog()` 实现
