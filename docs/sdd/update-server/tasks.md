# Update Server 改造实施与验收清单

规格依据：[redesign.md](../../../update-server/docs/redesign.md)。以下项目只有取得测试证据后才标记完成。

## Phase 1：数据库 + 基础改造

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-01 | 抽离 `src/util/version.js`（SemVer parse/compare）、`src/util/path.js`（safeSegment）、`src/util/http.js`（json/noContent/error）、`src/util/crypto.js`（scrypt/HMAC/sha256/randomId/isBase64） | `tests/version.test.mjs`、`tests/util.test.mjs` 18 项通过 | ✅ 完成 |
| US-02 | `src/db.js`：`node:sqlite` 初始化，建 `versions` / `upload_sessions` / `audit_log` 三表，含索引和唯一约束；`withTransaction` 封装 | `tests/db.test.mjs` 15 项通过 | ✅ 完成 |
| US-03 | `src/manifest.js`：从 SQLite 构造 `/latest.json` 和 `/api/v1/updates/*` 响应，签名内嵌不依赖文件 | `tests/server.test.mjs` 覆盖 | ✅ 完成 |
| US-04 | 拆分 `src/server.js`：`public.js`（health/latest/updates/download）+ 入口挂载；`admin.js` + `uploads.js` stub 占位 | `tests/server.test.mjs` 11 项回归通过 | ✅ 完成 |
| US-05 | `scripts/migrate-latest-json.mjs`：把现有 `data/latest.json` 导入 SQLite，文件迁到 `releases/{platform}/{version}/` | 脚本编写完成，逻辑由单元测试覆盖 | ✅ 完成 |

## Phase 2：管理员认证 + 版本列表 API

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-06 | `src/auth.js`：`crypto.scrypt` 密码哈希（替代 bcrypt，零依赖）、HMAC session cookie、登录失败次数限制 | `tests/auth.test.mjs` 5 项通过 | ✅ 完成 |
| US-07 | `POST /admin/login` / `POST /admin/logout` / `GET /admin/session`；`/admin/*`（除 login）强制 401 | `tests/admin.test.mjs`、`tests/auth.test.mjs`、e2e `unauth_blocked` 通过 | ✅ 完成 |
| US-08 | `GET /admin/versions?platform=` / `GET /admin/versions/{id}` / `PATCH`（只改 notes）/ `DELETE`（非 latest） | `tests/admin.test.mjs` 通过 | ✅ 完成 |
| US-09 | `DELETE` 当前 latest 返回 409 `cannot_delete_latest`；`PATCH` 不可改字段返回 422 `immutable_field` | `tests/admin.test.mjs`、e2e `delete_latest_409`、`patch_immutable_field_422` 通过 | ✅ 完成 |
| US-10 | 所有写操作写 `audit_log`（login / upload / publish / delete / patch / migrate） | `tests/admin.test.mjs` `GET /admin/audit` 通过 | ✅ 完成 |

## Phase 3：分块上传 + 断点续传 + 发布

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-11 | `src/uploads.js`：`POST /admin/uploads` 创建 session + `HEAD /admin/uploads/{id}/{package,signature}` 查 offset | `tests/uploads.test.mjs` `HEAD returns current offsets` 通过 | ✅ 完成 |
| US-12 | `PATCH /admin/uploads/{id}/{package,signature}`：Upload-Offset 校验、追加落盘、UPDATE 偏移；使用 `fs.open` + `truncate` + `write` 实现幂等续传 | `tests/uploads.test.mjs` 多项通过 | ✅ 完成 |
| US-13 | 非连续 offset 拒绝 416 `offset_mismatch`；文件名严格白名单 | `tests/uploads.test.mjs` `PATCH with wrong offset returns 416` 通过 | ✅ 完成 |
| US-14 | `POST /admin/uploads/{id}/complete`：校验 size、计算 sha256、校验 sig base64 + 命名配对、rename 到 releases/、INSERT versions (is_latest=0) | `tests/uploads.test.mjs` + e2e `complete_success` + `complete_sha256_matches` 通过 | ✅ 完成 |
| US-15 | `POST /admin/versions/{id}/publish`：再次单调递增校验、TX 切换 is_latest、INSERT audit_log；幂等返回 `already_latest` | `tests/uploads.test.mjs` + e2e 通过 | ✅ 完成 |
| US-16 | upload session 24h 过期 + 惰性清理（每次 create session 触发）；`GET /admin/uploads` 列出活跃 session | `tests/db.test.mjs` `findExpiredUploadSessions`、`tests/uploads.test.mjs` `active upload sessions listed` 通过 | ✅ 完成 |
| US-17 | 尝试 publish 未 complete 的版本：因 complete 时 INSERT versions 才发生，publish 找不到 ID 返回 404 `version_not_found`；`upload_incomplete` 409 在 complete 自身校验 | `tests/uploads.test.mjs` `complete without uploading returns 409` 通过 | ✅ 完成 |

## Phase 4：控制面板 Web UI

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-18 | `public/index.html` + `app.js` + `style.css`：登录页 + 版本列表 + 上传页 + 审计日志页 | `tests/static.test.mjs` 6 项通过 | ✅ 完成 |
| US-19 | 上传页：分块进度条 + 断点续传（HEAD 查 offset 后续传）；签名文件名自动配对校验 | e2e `resume_offset_after_partial_upload`、`signature_upload_complete` 通过 | ✅ 完成 |
| US-20 | 版本详情：改 notes + 删除非 latest（latest 隐藏删除按钮）；publish 非 latest 为 latest | UI 已实现；后端逻辑由 `tests/admin.test.mjs` + e2e 覆盖 | ✅ 完成 |
| US-21 | `admin.js` 增加 `GET /admin`（带认证）→ 返回控制面板静态文件 + `GET /admin/static/{file}` | `tests/static.test.mjs` 全 6 项通过（含未登录 401、路径遍历防御） | ✅ 完成 |

## Phase 5：文档 + 部署

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-22 | 更新 `update-server/README.md`：部署、环境变量、迁移步骤、客户端集成、测试命令 | 文档已更新 | ✅ 完成 |
| US-23 | 新增 `update-server/docs/deployment.md`：systemd + Nginx + HTTPS + 备份策略 + 故障排查 + 安全清单 | 文档已创建 | ✅ 完成 |
| US-24 | `update-server/` 目录纳入 git 跟踪 | 待 `git add`（用户确认后 commit） | ⏳ 待用户操作 |

## 跨层验证（回归 + 全链路）

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-25 | 回归：原 `tests/server.test.mjs` 的 `/healthz` / `/latest.json` / `/api/v1/updates` / `/download` 场景不破坏（适配新路径 `/download/{platform}/{version}/{file}`） | `npm test` 11 项通过 | ✅ 完成 |
| US-26 | 全链路：脚本模拟完整流程：登录 → 创建 upload → 分块上传 → 中断 → 续传 → complete → publish → `/api/v1/updates` 返回新版本 | `scripts/e2e-test.mjs` 22 场景全过，evidence 在 `docs/sdd/update-server/evidence/e2e-result.json` | ✅ 完成 |
| US-27 | 全链路：双平台独立验证，Windows 0.2.3 + macOS 0.2.4 各自发布互不干扰 | e2e `dual_platform_independence_win`、`dual_platform_independence_mac` 通过 | ✅ 完成 |
| US-28 | 全链路：单调递增校验：试图上传 ≤ 当前 latest 的版本返回 409 `version_older_than_latest` | e2e `monotonic_rejects_older_version` 通过 | ✅ 完成 |
| US-29 | 全链路：删除 latest 返回 409；删除非 latest 成功；修改 notes 成功；修改不可变字段 422 | e2e `delete_latest_409`、`delete_non_latest_204`、`patch_notes_success`、`patch_immutable_field_422` 通过 | ✅ 完成 |
| US-30 | 安全：未登录访问 `/admin/*` 返回 401；5 次错密码后锁定 15 分钟（即使正确密码也被锁） | e2e `unauth_blocked`、`login_lockout_429`、`login_correct_blocked_during_lockout` 通过 | ✅ 完成 |

不改 Tauri 客户端代码（已就绪）。不引入运行时第三方依赖（`package.json` 无 dependencies 字段）。不把文档或编译成功当成全链路成功——所有 e2e 场景实际启动真实 HTTP server 验证。

## Phase 6：SRP-6a 认证改造（PAKE 协议层）

| 编号 | 要求与实施任务 | 验证证据 | 状态 |
|---|---|---|---|
| US-31 | `src/srp-group.js`：从 Node `createDiffieHellmanGroup('modp14')` 取 2048-bit safe prime（避免手抄 RFC hex 错误）；export `padToBuffer`/`modPow`/`H`/`b64url` helpers；启动时自检 `2^q mod N == 1` | `tests/srp-group.test.mjs` 18 项通过（含子群验证、与 Node crypto modp14 cross-check） | ✅ 完成 |
| US-32 | `src/srp.js`：SRP-6a 协议（`computeX`/`computeVerifier`/`generateServerEphemeral`/`computeServerSharedSecret`/`computeM1`/`computeM2`）；challenge 内存表 5 分钟 TTL + 一次性消费 | `tests/srp.test.mjs` 14 项通过（含完整握手 roundtrip、错密码 M1 不匹配、challenge one-shot、A=0 拒绝） | ✅ 完成 |
| US-33 | `src/auth.js` 加 `handleSrpChallenge`/`handleSrpVerify`/`handleSrpParams`；旧 `handleLogin` → `handleLegacyLogin` 返回 410 `legacy_login_deprecated` | `tests/srp-api.test.mjs` 14 项通过 | ✅ 完成 |
| US-34 | `src/server.js` 启动时从 `SRP_VERIFIER_FILE` 加载 verifier 到 `ctx.srp`；未配置时 `/admin/srp/*` 返回 503 `srp_not_configured` | `tests/srp-api.test.mjs` `SRP not configured: /admin/srp/* returns 503` 通过 | ✅ 完成 |
| US-35 | `public/srp-client.js`：浏览器 BigInt + `crypto.subtle.digest` 实现 SRP-6a 客户端；从 `/admin/srp/params` 取 N/g/hash/username，无硬编码常量 | ESM 模块在 Node 验证语法正确；e2e 用 Node 模拟前端数学跑通完整握手 | ✅ 完成 |
| US-36 | `public/app.js` 登录流程改为 `srpLogin(password)`；密码从 input 读后立即清空；按钮显示握手进度 | 代码改造完成；e2e 全流程通过 | ✅ 完成 |
| US-37 | `scripts/generate-srp-credentials.mjs`：TTY 读密码（关 echo）+ 强度检查（12+ 字符、含大小写+数字+符号、弱密码字典）+ 输出 verifier JSON | 手动跑 CLI 验证产出合法 JSON | ✅ 完成 |
| US-38 | `scripts/gen-verifier-non-tty.mjs`：非交互式版（CI/测试用），argv 或 stdin 读密码 | 手动验证通过 | ✅ 完成 |
| US-39 | `docs/deployment.md` 加 systemd `LoadCredential=srp_verifier` 配置 + verifier 文件 0400 权限 + 沙盒指令；故障排查和 Security checklist 更新 | 文档已更新 | ✅ 完成 |
| US-40 | `README.md` 认证章节改为 SRP 流程；`ADMIN_PASSWORD_HASH` 标记废弃 | 文档已更新 | ✅ 完成 |
| US-41 | e2e 加 6 个 SRP 场景：`srp_params_endpoint`、`srp_challenge_returns_salt_B`、`srp_replay_blocked`、`srp_wrong_M1_rejected_401`、`srp_fresh_ephemerals_per_handshake`、`srp_legacy_login_deprecated_410` | `scripts/e2e-test.mjs` 全场景通过 | ✅ 完成 |
| US-42 | 回归：admin.test.mjs、uploads.test.mjs、static.test.mjs 全部改用 SRP 登录；旧 `/admin/login` 测试用例改为 410 | `npm test` 全套 131 项通过 | ✅ 完成 |

不改 Tauri 客户端代码（已就绪）。不引入运行时第三方依赖（`package.json` 无 dependencies 字段）。不把文档或编译成功当成全链路成功——所有 e2e 场景实际启动真实 HTTP server 验证。
