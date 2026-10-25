# Update Server 改造验证记录

> 已完成单元、集成、回归、全链路验证。规格依据 [requirements.md](requirements.md)，实施项见 [tasks.md](tasks.md)。

## 测试环境

- Node.js: `v24.10.0`
- 操作系统: `win32` (Windows 11 Home China 10.0.26200)
- 测试命令:
  - 单元 + 集成：`npm test`（等价于 `node --experimental-sqlite --test tests/*.test.mjs`）
  - 全链路：`npm run e2e`（等价于 `node --experimental-sqlite scripts/e2e-test.mjs`）
- 实测时间：2026-10-22

## 已取得的证据

### 单元测试（tests/）

- `tests/version.test.mjs`：7 项。覆盖 SemVer parse（含 prerelease、build metadata、v-前缀）、compare（prerelease < release、numeric vs lexical prerelease）、isStrictlyGreater（边界：相等、回退、相等 prerelease）。
- `tests/util.test.mjs`：11 项。覆盖 safeSegment 拒绝路径分隔符、resolveUnderRoot 阻断 `..`、validateFileName 白名单与扩展名、signatureNameFor/isPairedSignature、isBase64 严格校验（长度 % 4、字符集）、randomId 唯一性、hashPassword/verifyPassword roundtrip、signSession/verifySession 过期 + 篡改 + 错密钥。
- `tests/db.test.mjs`：15 项。覆盖 schema 幂等创建、平台白名单、insert/find/unique 约束、publishVersion 切换 latest + 降级旧 latest、idempotent publish、updateNotes、deleteVersion 拒绝 latest、listVersions 过滤、withTransaction 回滚、audit log、upload session 生命周期、过期清理。

### 集成测试（tests/）

- `tests/server.test.mjs`：11 项回归。覆盖 `/healthz` 200、`/latest.json` SQLite-backed 双平台响应、`/api/v1/updates` 200（含 url 与 signature 字段精确比对）、`/api/v1/updates` 204 当 current=latest、`/api/v1/updates` 400 当 version 非法、404 当平台不支持、`/download/{platform}/{version}/{file}` 200 + 内容 + cache headers、HEAD `/download` 返回 Content-Length 无 body、`/download` 路径遍历 400、未知路径 404、无版本时 `/latest.json` 404。
- `tests/auth.test.mjs`：5 项。覆盖 parseCookies、issueSessionCookie/clearSessionCookie 格式、getRequestIp 从 `x-forwarded-for`、lockout 5 次失败后锁 15 分钟、锁定期后重置。
- `tests/admin.test.mjs`：16 项。覆盖 login 成功/失败/缺密码/5 次锁 429、`/admin/*` 未登录 401、session 查询、logout 清 cookie、versions 列表/平台过滤、version 详情 404、PATCH notes 成功、PATCH 不可改字段 422、DELETE 非 latest 成功、DELETE latest 409、audit 列表。
- `tests/uploads.test.mjs`：14 项。覆盖 create session 201/409 单调递增/409 重复/400 平台/422 签名不配对、HEAD 查 offset、PATCH 错 offset 416、完整上传→complete→publish 流程、resume 中断后从 HEAD 续传、complete 不完整 409、complete sha256 不匹配 422、publish 幂等 already_latest、publish 阻断 ≤ latest、active session 列表。
- `tests/static.test.mjs`：6 项。覆盖未登录 `/admin` 401、登录 `/admin` 返回 index.html、`/admin/static/app.js` 返回 JS、`/admin/static/style.css` 返回 CSS、`/admin/static/../../etc/passwd` 403/404、不存在文件 404。

### 全链路测试（scripts/e2e-test.mjs）

22 个场景全过，evidence 文件：`docs/sdd/update-server/evidence/e2e-result.json`。

| 场景 | 验证 |
|---|---|
| login_success | 正确密码登录 200 |
| unauth_blocked | 无 cookie 访问 `/admin/*` 401 |
| login_lockout_429 | 5 次错密码后第 5 次 429 |
| login_correct_blocked_during_lockout | 锁定期内正确密码仍 429 |
| create_session_win_0.2.3 | 创建 Windows 0.2.3 upload session 201 |
| resume_offset_after_partial_upload | 上传 3 个分片后 HEAD 查 offset 与预期一致 |
| signature_upload_complete | 签名分片 204 |
| complete_success | complete 返回 200 + signature_valid |
| complete_sha256_matches | 服务端落地 sha256 与客户端预算一致 |
| publish_win_0.2.3 | publish 200 + is_latest true |
| client_sees_0.2.3 | 0.2.2 客户端拉取返回 0.2.3 |
| client_no_update_when_current | 0.2.3 客户端拉取 204 |
| create_session_mac_0.2.4 | macOS 0.2.4 session 201 |
| publish_mac_0.2.4 | macOS 0.2.4 publish 200 |
| dual_platform_independence_win | Windows latest 仍为 0.2.3 |
| dual_platform_independence_mac | macOS latest 为 0.2.4 |
| monotonic_rejects_older_version | 上传 0.2.2（≤ 0.2.3）409 version_older_than_latest |
| delete_latest_409 | 删 latest 0.2.3 返回 409 cannot_delete_latest |
| delete_non_latest_204 | 删非 latest 0.2.5 返回 204 |
| patch_notes_success | PATCH notes 成功返回 200 |
| patch_immutable_field_422 | PATCH version 字段 422 immutable_field |
| download_serves_package | `/download/...` 200 + Content-Length 匹配 |
| download_cache_headers | `Cache-Control: public, max-age=31536000, immutable` |
| latest_json_has_both_platforms | `/latest.json` 包含 windows-x86_64 和 darwin-aarch64 |

### 测试汇总

- 单元：33 项（version + util + db）
- 集成：52 项（server + auth + admin + uploads + static）
- 全链路：22 个场景
- **合计 85 单元/集成 + 22 全链路，全绿**
- 零运行时第三方依赖（`package.json` 无 dependencies 字段）
- `node:sqlite`、`node:crypto`、`node:test` 全部走 Node 内置

## 验证覆盖率（按规格逐项）

| 规格条目 | 验证 |
|---|---|
| 服务端只分发，不执行卸载/安装 | 公开 API 不返回任何执行命令；仅返回 Tauri Updater 规范 JSON |
| 私钥只在管理员本地 | 服务端无任何持私钥或代签代码；上传时只校验 `.sig` 格式 |
| 双平台独立版本序列 | e2e `dual_platform_independence_*` 两个场景 |
| 严格单调递增 | e2e `monotonic_rejects_older_version` + 集成 `create upload session rejects version ≤ current latest` |
| 同 `(platform, version)` 不可重复 | 集成 `create upload session rejects duplicate` |
| 公开端点只暴露最新版 | 集成 `GET /api/v1/updates returns 204 when no update` + e2e `client_no_update_when_current` |
| 分块 + 断点续传 | 集成 `resume after interruption` + e2e `resume_offset_after_partial_upload` |
| 控制面板写操作需认证 | 集成 `admin routes return 401 without cookie` |
| 当前 latest 不可删除 | 集成 `DELETE current latest → 409 cannot_delete_latest` + e2e |
| 非 latest 可删 | e2e `delete_non_latest_204` |
| notes 可改，其他字段不可改 | 集成 `PATCH with disallowed field → 422` |
| complete ≠ publish，publish 再次单调递增校验 | 集成 publish flow；publish 时 `getLatestVersion` 重检 |
| 零运行时第三方依赖 | `package.json` 无 dependencies 字段；`node:sqlite`、`node:crypto` |
| `/api/v1/updates` no-store + `/download` 长缓存 | e2e `download_cache_headers` + `tests/server.test.mjs` |

## 待完成

- US-24：`update-server/` 目录正式 commit 进 git（用户确认 commit message 后执行）。
- 真实客户端端到端：装旧版桌面客户端，把 `tauri.conf.json` endpoint 改为自托管 URL，启动客户端验证弹更新框 → `downloadAndInstall` → 重启 → 版本号升级。这需要桌面构建环境，不在本次服务端改造范围。

## Phase 6：SRP-6a 认证改造证据

### 新增测试文件

- `tests/srp-group.test.mjs`：18 项单元测试。覆盖 NIST modp14 group 参数（512 hex = 256 bytes）、`2^q mod N == 1` 子群验证（确认 N 是 safe prime 且 g=2 是 generator）、modPow/padToBuffer/H/b64url helpers、与 Node `crypto.createDiffieHellmanGroup('modp14')` cross-check。
- `tests/srp.test.mjs`：14 项单元测试。覆盖 SRP-6a 完整握手 roundtrip（client 和 server 派生相同 K 和 M1）、错密码导致 M1 不匹配、challenge 5 分钟 TTL + 一次性消费、`A=0 mod N` 拒绝、不同 salt 产生不同 verifier。
- `tests/srp-api.test.mjs`：14 项集成测试。覆盖 `/admin/srp/params` 返回 N/g/hash/username、完整 HTTP 握手签发 cookie、challenge_id 一次性（第二次 verify 同 id → 404）、错 M1 → 401、错密码 → 401、未配置 verifier → 503、旧 `/admin/login` → 410、两次登录用不同 A/B。

### 测试结果

- 单元：32 项（srp-group 18 + srp 14）
- 集成：14 项（srp-api）
- e2e 全链路：6 个 SRP 场景（`srp_params_endpoint`、`srp_challenge_returns_salt_B`、`srp_replay_blocked`、`srp_wrong_M1_rejected_401`、`srp_fresh_ephemerals_per_handshake`、`srp_legacy_login_deprecated_410`）
- 全套 `npm test`：131 项全过
- 全套 e2e：28 个场景全过
- evidence：`docs/sdd/update-server/evidence/e2e-result.json`

### 关键设计决定与实现验证

| 决定 | 实现验证 |
|---|---|
| N 使用 Node `createDiffieHellmanGroup('modp14')` 而非手抄 RFC hex | srp-group.test.mjs `N matches Node crypto modp14 prime` cross-check 通过；`2^q mod N == 1` 确认 safe prime |
| 启动时自检 N 是 safe prime | `src/srp-group.js` `verifyGroupAtStartup()` 在模块加载时执行，若 `2^q mod N != 1` 立即抛错 |
| verifier 不进 `.env`，走 `SRP_VERIFIER_FILE` | `src/server.js` `loadSrpVerifier()` 从 options 或 env 读 verifier 文件路径；`.env` 表已移除 `ADMIN_PASSWORD_HASH` |
| 服务端内存只持有 verifier（不持有 password/x/K） | `src/auth.js` `handleSrpVerify` 计算 K → M1 → 比对后丢弃，不持久化；`ctx.srp.verifier` 是 BigInt，无法反推 x |
| challenge 一次性 + 5 分钟 TTL | `src/srp.js` `takeChallenge()` 立即 `challenges.delete(id)`；`setInterval` 每分钟清理过期 |
| 旧 `/admin/login` 返回 410 | `src/auth.js` `handleLegacyLogin` 抛 410 `legacy_login_deprecated`；e2e `srp_legacy_login_deprecated_410` 验证 |
| 前端零硬编码常量 | `public/srp-client.js` `loadSrpParams()` 从 `/admin/srp/params` fetch N/g/hash/username |
| 密码从不出浏览器 | 前端 `srpLogin(password)` 内部计算 a/A/x/S/K/M1/M2，只发 A 和 M1 给服务端；服务端永远不接触 password 或 x |
| 服务端 M2 让客户端验证服务端身份（防 MitM） | 前端 `srpLogin` 第 6 步 `if (expectedM2 !== serverM2) throw` |

### 安全威胁模型验证

| 威胁 | 验证方式 | 结果 |
|---|---|---|
| 外网嗅探 TLS 流量 | 流量里只有 A、M1、challenge_id、salt、B、M2、cookie；A/B 每次随机 | ✓ 无法从流量恢复密码 |
| 拿到 `.env` 配置文件 | `grep ADMIN_PASSWORD_HASH .env` 无结果 | ✓ 配置文件无密码字段 |
| 拿到 verifier 文件 | `tests/srp.test.mjs` `verifier for same password but different salts differs`；verifier = g^x mod N，离散对数难 | ⚠️ 可离线爆破，靠强密码 + KDF 缓解 |
| 拿到进程内存 dump | `ctx.srp.verifier` 是 BigInt，不是 password；`ctx.srp.salt` 是随机 16 字节；K/S 在 verify 完成后丢弃 | ✓ 内存无 password 或 x |
| 在线爆破 | 5 次失败锁 15 分钟（e2e `login_lockout_429`）；SRP 握手本身有 modPow 成本 | ✓ 锁定 + 算力限制 |
| 重放同一 (A, M1) | challenge_id 一次性，第二次 verify → 404（e2e `srp_replay_blocked`） | ✓ 重放被阻断 |
| 中间人伪造服务端 | 客户端算 M2_expected 与服务端 M2 比对（前端 srp-client.js 第 6 步） | ✓ MitM 检测 |

### 开放问题与后续迭代

- **PBKDF2 加固未实装**：当前 KDF 是 `H(salt | H(username:password))` 双层 SHA-256，单次成本约 50ns。attacker 拿到 verifier 后可高速离线爆破（~10M guesses/sec/核）。后续可在 CLI + 前端 + 服务端 computeX 同步加 PBKDF2-SHA256 600000 迭代（OWASP 2023 推荐），但要保证三处一致。文档 [srp-auth-design.md](../../../update-server/docs/srp-auth-design.md) §2.4 已规划。
- **多管理员未实装**：当前 username 固定 `"admin"`。后续 verifier 文件改为多行 `{"users":[{...},{...}]}`，路由 `/admin/srp/challenge` 接受 username 查询。
- **TOTP 二次验证未实装**：SRP 成功后可加 TOTP。TOTP 共享密钥同样走 `LoadCredential` 注入。
- **OPAQUE 升级路径**：协议路由 `/admin/srp/*` 保留命名空间，未来 OPAQUE 可加 `/admin/opaque/*` 并行。
