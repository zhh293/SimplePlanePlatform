# Update Server 认证改造：SRP-6a + 前端协议层

> 详细设计文档。实施项与测试证据见 [SDD tasks.md](../../docs/sdd/update-server/tasks.md)。
>
> 目标：把现有 `crypto.scrypt` 单层哈希登录方案，升级为 SRP-6a + systemd `LoadCredential` 注入 verifier + 前端自实现 SRP 客户端，做到"密码从不出浏览器、服务端只持有 verifier（数学上等价于公钥）、磁盘上零敏感字段、进程内存里也没有密码 hash"。

---

## 一、背景与威胁模型

### 1.1 现状缺陷

当前 `src/auth.js` 用 `crypto.scrypt` 哈希密码，存到环境变量 `ADMIN_PASSWORD_HASH`：

- **配置文件含敏感字段**：`/etc/simpleplane-update.env` 包含 hash，root 可读，可被离线爆破。
- **服务端"知道"密码 hash**：等价于密码的派生值，攻击者拿到就能做 dictionary attack。
- **网络层有改进空间**：虽然走 HTTPS，但密码 hash 从前端传到后端时仍是"密码的派生值"——一旦 TLS 被中间人或日志泄露，攻击者获得 hash 即可离线爆破。
- **进程内存 dump** 含 hash（虽短期，但可被 forensics 提取）。

### 1.2 目标威胁模型

| 攻击者能力 | 是否防御 |
|---|---|
| 外网嗅探 + 伪造 TLS（拿到完整流量） | ✅ 防（流量里没有密码，也没有密码 hash，只有 SRP 证明） |
| 拿到服务端配置文件（`/etc/simpleplane-update.env`） | ✅ 防（配置文件不含 verifier） |
| 拿到服务端文件系统（`/etc/simpleplane-update/verifier`） | ⚠️ 部分防（verifier 可离线爆破，但密码强度 12+ 字符后算力不可行） |
| 拿到服务端进程内存 dump | ✅ 防（内存里只有 verifier，没有 password 也没有 hash） |
| 拿到 root 权限 + 强制服务端配合 | ❌ 不防（root 可改服务端代码任意伪造认证流程） |
| 在线爆破（反复尝试不同密码） | ✅ 防（IP 级 5 次失败锁 15 分钟 + SRP 握手成本） |

### 1.3 不防御的威胁

- **root 权限 + 持久化服务端**：root 能改 `src/srp.js`，下次握手把 verifier 直接发给 attacker。任何本机认证方案都防不住，PAKE 也好 OPAQUE 也好。要防需要硬件安全模块（HSM/TPM）+ 远程证明，超范围。
- **客户端被攻陷**：admin 的浏览器被植入恶意 JS，密码在浏览器内存里被读走。靠前端 CSP + 代码签名缓解。
- **社工**：admin 自己被骗输密码到钓鱼页面。靠域名管理 + admin 教育缓解。

### 1.4 与 OPAQUE 的对比

OPAQUE（RFC 9380）在"拿到 verifier 仍不可离线爆破"上更强，但：

| 维度 | SRP-6a | OPAQUE |
|---|---|---|
| 数学基础 | 离散对数（标量域） | ristretto255 椭圆曲线 + OPRF |
| 前端实现 | ~200 行纯 JS（BigInt + SHA-256） | ~600+ 行，需 ristretto255 库 |
| Web Crypto 支持 | 是（`crypto.subtle` + `bigint`） | 否（ristretto255 不在 WebCrypto） |
| 依赖 | 零 | 至少一个 ristretto255 库 |
| 离线爆破 | verifier 可爆破（密码强则安全） | 不可爆破 |
| RFC | RFC 5054 | RFC 9380 |

**决策：SRP-6a + 强密码策略（12+ 字符、含大小写+数字+符号）**。后续如出现强需求，预留升级 OPAQUE 的协议路径（`/admin/srp/*` 与 `/admin/opaque/*` 并存）。

---

## 二、协议规范

### 2.1 选用的参数

- **SRP-6a**（不是 SRP-6，k 的算法不同）。
- **Group**：RFC 5054 §2.2 的 1536-bit MODP Group（NIST P-1536）。
- **Hash**：SHA-256（`node:crypto` 内置）。
- **Username**：固定为 `"admin"`（单管理员场景；多管理员后续扩展时再加用户字段）。
- **Padding**：所有 group 元素（A、B、M1、M2）按 N 字节左零填充传输，避免长度泄露。
- **Encoding**：所有传输用 base64url。

参数：

```
N (1536-bit safe prime, hex):
    FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E0
    8867E12B4C76F25C1E0BC0A64D1C9E1B1B1C2C0E2C0E2C0E2C0E2C0E2C0E2C0E2C0E2C0E2C0E2C0
    ...完整值见 RFC 5054...

g = 2
k = H(N | g)  # SHA-256，左零填充到 N 字节再拼接
```

### 2.2 完整数学流程

#### 注册阶段（离线 CLI 工具，`scripts/generate-srp-credentials.mjs`）

输入：密码 `P`（从 stdin 读，不显示在命令行）。

```
salt = randomBytes(32)
x = H(salt | H("admin:" | P))     # padded Hash 即 SHA-256
v = g^x mod N                     # verifier
```

输出：

```json
{
  "username": "admin",
  "salt": "<base64 salt>",
  "verifier": "<base64 verifier>"
}
```

管理员把这串 JSON 写到 `/etc/simpleplane-update/verifier`，权限 `0400` owner=simpleplane。

#### 登录阶段（前端 + 后端握手）

记号：`H(x)` = SHA-256，`|` = 字节拼接，`^` = 幂，`mod N` = 取模。

**Step 1：客户端发起**

前端用户输密码 `P`。

前端随机生成 `a`（256-bit 随机，保证 `a mod (N-1) ≠ 0`），计算 `A = g^a mod N`。

```
POST /admin/srp/challenge
Content-Type: application/json
{ "username": "admin", "A": "<base64url A>" }
```

**Step 2：服务端响应挑战**

服务端从 verifier 文件读 `(salt, v)`。随机生成 `b`（256-bit），计算：

```
k = H(N_padded | g_padded)
B = (k * v + g^b) mod N
u = H(A_padded | B_padded)   # 必须非零，否则重新生成 b
```

服务端缓存 `(A, b, v, k, u)` 到内存会话表（TTL 5 分钟），key = 服务端生成的 `challenge_id`。

```
200 OK
{ "challenge_id": "<base64url>", "salt": "<base64 salt>", "B": "<base64url B>" }
```

**Step 3：客户端计算共享密钥 + 证明**

前端拿到 `(salt, B)`，校验 `B mod N ≠ 0`：

```
x = H(salt | H("admin:" | P))
u = H(A_padded | B_padded)
S = (B - k * g^x) ^ (a + u * x) mod N
K = H(S_padded)
M1 = H(H(N) | H(g) | H("admin") | salt_padded | A_padded | B_padded | K_padded)
```

清掉内存里的 `P`、`x`、`a`、`S`（强制 GC + 覆写）。

```
POST /admin/srp/verify
Content-Type: application/json
{ "challenge_id": "<id>", "M1": "<base64url M1>" }
```

**Step 4：服务端验证 + 签发 session**

服务端从内存会话表拿 `(A, b, v, k, u)`，计算：

```
S_server = (A * v^u) ^ b mod N
K_server = H(S_server_padded)
M1_expected = H(H(N) | H(g) | H("admin") | salt_padded | A_padded | B_padded | K_server_padded)
```

用 `crypto.timingSafeEqual` 比对 `M1` vs `M1_expected`：

- 相等：握手成功。清掉内存里的 `(b, S, K)`。签 session cookie（沿用现有 `signSession` HMAC 方案）+ 写 audit log `admin.login.success` + 返回 `M2`。
- 不等：清 challenge + 返回 401 `invalid_credentials` + 写 audit log `admin.login.failed`。

`M2 = H(A_padded | M1_padded | K_padded)`。

```
200 OK
Set-Cookie: sp_admin_session=<signed>; HttpOnly; SameSite=Lax; Path=/; Max-Age=86400
{ "user": "admin", "expires_at": "...", "M2": "<base64url M2>" }
```

**Step 5：客户端验证服务端**

前端算 `M2_expected = H(A_padded | M1_padded | K_padded)`，用 `timingSafeEqual` 比对。不等则视为服务端被中间人，丢弃 session。

### 2.3 安全性质

- **密码不出浏览器**：`P` 从前端到后端全程只在前端内存中存在。后端永远不接触 `P`、`x`、`a`、`S`、`K`。
- **verifier 不可反推密码**：`v = g^x mod N`，已知 v 求 x 是离散对数难题。
- **离线爆破需知 verifier**：attacker 拿到 `(salt, v, A, B, M1, M2)` 全套，可以离线爆破——对每个候选 `P'`，算 `x' = H(salt | H("admin:" | P'))`、`v' = g^x' mod N`，比对 `v' == v`。这是 SRP 的固有限制。缓解：强密码 + scrypt KDF（在前端算 `H("admin:" | P)` 时多轮 scrypt）。
- **网络流量泄露不可爆破**：流量里有 `A` 和 `M1`，但 `A = g^a mod N` 是随机化的，每个会话不同，attacker 无法用流量做离线爆破。
- **服务端伪造抵御**：M2 让客户端验证服务端，防止中间人假装服务端。
- **重放不可行**：challenge_id TTL 5 分钟，验证后立即失效；A、B 每次不同。

### 2.4 前端 KDF 强化

为减缓离线爆破（attacker 拿到 verifier 后），前端在算 `x = H(salt | H("admin:" | P))` 之前，先把 `P` 做 scrypt 强化：

```
P_hard = scrypt(P, salt, N=2^15, r=8, p=1, dkLen=32)   // 前端
x = H(salt | H("admin:" | P_hard))
```

scrypt 在浏览器里用 `crypto.subtle` + Web Worker（不阻塞 UI）或纯 JS 实现。20-30ms 一次，单核约 50ms，attacker 离线爆破速度 ~20 次/秒/核。

### 2.5 密码强度策略

注册 CLI `scripts/generate-srp-credentials.mjs` 强制：

- 长度 ≥ 12 字符（推荐 16+）
- 至少 1 个大写字母 + 1 个小写字母 + 1 个数字 + 1 个符号
- 拒绝常见弱密码字典（top 10000）

不强可绕过（`--insecure-weak`），但 audit log 记录。

---

## 三、存储设计

### 3.1 verifier 文件位置

```
/etc/simpleplane-update/verifier       # 生产
~/.simpleplane-update/verifier         # 开发
```

文件格式（JSON，单行紧凑）：

```json
{"username":"admin","salt":"<base64>","verifier":"<base64>","kdf":"scrypt-n15-r8-p1","created_at":"2026-10-22T10:00:00Z"}
```

权限：`0400`，owner = `simpleplane` 用户，group = `simpleplane`。

### 3.2 systemd `LoadCredential` 注入

`/etc/systemd/system/simpleplane-update.service`：

```ini
[Service]
User=simpleplane
Group=simpleplane
WorkingDirectory=/opt/simpleplane-update/update-server
EnvironmentFile=/etc/simpleplane-update.env
ExecStart=/usr/bin/node --experimental-sqlite src/server.js

# 注入 verifier 到进程私有 namespace（${CREDENTIALS_DIRECTORY}）
LoadCredential=srp_verifier:/etc/simpleplane-update/verifier
Environment=SRP_VERIFIER_FILE=%d/srp_verifier

# 沙盒
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
NoNewPrivileges=yes
CapabilityBoundingSet=
RestrictAddressFamilies=AF_INET AF_INET6
SystemCallFilter=@system-service
SystemCallFilter=~@privileged

# 资源限制
LimitNOFILE=1024
LimitNPROC=64

[Install]
WantedBy=multi-user.target
```

systemd 在启动服务时，把 `/etc/simpleplane-update/verifier` 挂载到进程私有的 `${CREDENTIALS_DIRECTORY}`（通常 `/run/credentials/srp_verifier`），文件在 `/run/credentials/...` 下，进程看不到原路径。磁盘上的 verifier 文件 root 才能读。

### 3.3 服务端加载流程

`src/server.js` 启动时：

```javascript
const verifierPath = process.env.SRP_VERIFIER_FILE || process.env.SRP_VERIFIER_PATH;
if (!verifierPath) {
  console.error('SRP_VERIFIER_FILE not set; admin login disabled');
  // 服务可继续启动（公开接口仍可用），但 /admin/* 全部 503
} else {
  const raw = fs.readFileSync(verifierPath, 'utf8');
  const parsed = JSON.parse(raw);
  ctx.srp = {
    username: parsed.username,
    salt: Buffer.from(parsed.salt, 'base64'),
    verifier: Buffer.from(parsed.verifier, 'base64'),
    kdf: parsed.kdf,
  };
  // 立即 unlink fd：让原文件可被替换而不影响本进程
  // （已读入内存，verifier 在 ctx.srp 中）
}
```

进程内存中 `ctx.srp.verifier` 是 `g^x mod N`，attacker dump 内存拿到 verifier 不可直接登录（需解 SRP 谜题）。

### 3.4 verifier 轮换

`POST /admin/srp/rotate`（要求当前会话已认证）：

1. 管理员在前端输新密码，前端做 SRP 客户端得到新 `(salt', v')`。
2. POST 新 `(salt', v')` 到 `/admin/srp/rotate`。
3. 服务端校验当前 session 有效（已通过 SRP 登录），写入新 verifier 文件（原子替换），reload `ctx.srp`。
4. 旧 session 全部失效（session secret 轮换，旧 HMAC 失效）。
5. audit log 记录 `admin.verifier.rotated`。

注意：systemd `LoadCredential` 是启动时注入的，运行时改 `/etc/simpleplane-update/verifier` 不会自动重载。轮换有两种实现：

- **方案 A（推荐）**：服务端直接写 `/etc/.../verifier` 文件 + reload `ctx.srp`。要求进程有权限写 verifier 文件（PermissionError 时报 500 + 提示 admin 重启）。
- **方案 B**：服务端写文件 + 触发 `systemctl restart`（重启即重新 LoadCredential）。需要 polkit 规则允许 simpleplane 用户重启自己的服务。

先用方案 A。文件 owner=simpleplane，进程可写。

### 3.5 配置文件 `.env` 字段

`/etc/simpleplane-update.env`（不再含任何密码字段）：

```bash
HOST=127.0.0.1
PORT=8787
DATA_DIR=/opt/simpleplane-update/data
PUBLIC_BASE_URL=https://update.example.com
SESSION_SECRET=<32-byte hex>
MAX_FILE_SIZE=1073741824
MAX_CHUNK_SIZE=33554432
DEFAULT_CHUNK_SIZE=8388608
UPLOAD_SESSION_TTL_HOURS=24
MAX_ACTIVE_UPLOADS=5
# 不再有 ADMIN_PASSWORD_HASH
```

`SESSION_SECRET` 仍是 HMAC 密钥用于签 session cookie，泄露后果是 attacker 可伪造已登录会话（但不增加 verifier 泄露风险）。建议也走 `LoadCredential=session_secret:/etc/simpleplane-update/session-secret`。

---

## 四、API 设计

### 4.1 新增 SRP 路由

| 方法 | 路径 | 认证 | 用途 |
|---|---|---|---|
| POST | `/admin/srp/challenge` | 无 | 客户端发 username + A，返回 challenge_id + salt + B |
| POST | `/admin/srp/verify` | 无 | 客户端发 challenge_id + M1，返回 session + M2 |
| POST | `/admin/srp/rotate` | session | 轮换 verifier |
| GET | `/admin/srp/params` | 无 | 返回 group N、g、hash 算法（让客户端 CLI 同步参数） |

### 4.2 废弃路由

`POST /admin/login`（旧 scrypt 方案）**保留但标记 deprecated**，返回 410 Gone。前端不再调用。3 个月后删代码。

### 4.3 速率限制

- `/admin/srp/challenge`：单 IP 每分钟 10 次（防滥用 b 计算）。
- `/admin/srp/verify`：单 IP 每分钟 10 次 + 失败 5 次锁定 15 分钟。
- `/admin/srp/rotate`：单 session 每小时 2 次（防激进轮换引发操作风险）。

### 4.4 错误码

| HTTP | code | 触发场景 |
|---|---|---|
| 400 | `invalid_A` | A == 0 mod N |
| 400 | `invalid_username` | username 不是 "admin" |
| 400 | `missing_A` | 请求体缺 A |
| 401 | `invalid_credentials` | M1 不匹配 |
| 404 | `challenge_not_found` | challenge_id 不存在或已过期 |
| 410 | `legacy_login_deprecated` | POST /admin/login（旧方案） |
| 429 | `rate_limited` | 超出速率限制 |
| 429 | `too_many_attempts` | 5 次失败后锁定 |
| 503 | `srp_not_configured` | 服务启动时 verifier 未加载 |

### 4.5 响应示例

**Step 1 - challenge**：

```http
POST /admin/srp/challenge HTTP/1.1
Content-Type: application/json

{"username":"admin","A":"<base64url A>"}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"challenge_id":"<base64url>","salt":"<base64>","B":"<base64url B>"}
```

**Step 2 - verify**：

```http
POST /admin/srp/verify HTTP/1.1
Content-Type: application/json

{"challenge_id":"<id>","M1":"<base64url M1>"}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Set-Cookie: sp_admin_session=<signed>; HttpOnly; SameSite=Lax; Path=/; Max-Age=86400
Cache-Control: no-store

{"user":"admin","expires_at":"2026-10-23T10:00:00Z","M2":"<base64url M2>"}
```

---

## 五、前端实现

### 5.1 文件结构

```
public/
├── index.html              # 登录页改造：两步 SRP 握手
├── app.js                  # 主流程，调 srpClient
├── srp-client.js            # SRP-6a 客户端数学
├── srp-group.js             # NIST 1536-bit MODP group 常量
└── style.css                # （样式不变）
```

### 5.2 SRP 客户端核心（`public/srp-client.js`）

```javascript
import { N, g, H, padTo, b64url } from './srp-group.js';

export async function srpLogin(password) {
  // 1. 生成客户端 ephemeral a
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);

  // 2. scrypt 强化密码（防离线爆破）
  const salt_response = await fetch('/admin/srp/challenge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', A: b64url(A) }),
  }).then(r => r.json());
  const { challenge_id, salt, B } = salt_response;
  const saltBytes = base64ToBytes(salt);
  const BBig = bytesToBigint(base64urlToBytes(B));
  if (BBig.mod(N).equals(0)) throw new Error('invalid B');

  // 3. 算 x（用 scrypt 强化）
  const P_hard = await scrypt(password, saltBytes, { N: 1 << 15, r: 8, p: 1, dkLen: 32 });
  const x = H(saltBytes | H('admin:' | P_hard));
  const u = H(padTo(A) | padTo(BBig));
  const k = H(padTo(N) | padTo(g));
  const S = modPow(BBig.minus(k.multiply(modPow(g, x, N))), a.add(u.multiply(x)), N);
  const K = H(padTo(S));
  const M1 = H(H(N) | H(g) | H('admin') | saltBytes | padTo(A) | padTo(BBig) | padTo(K));

  // 4. 发 M1
  const verifyRes = await fetch('/admin/srp/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ challenge_id, M1: b64url(M1) }),
  });
  if (verifyRes.status !== 200) {
    throw new Error('SRP verify failed: ' + verifyRes.status);
  }
  const body = await verifyRes.json();

  // 5. 验证 M2
  const M2_expected = H(padTo(A) | padTo(M1) | padTo(K));
  if (!timingSafeEqualBigint(M2_expected, bytesToBigint(base64urlToBytes(body.M2)))) {
    throw new Error('server M2 verification failed');
  }

  // 6. 清内存
  password.fill(0);
  zeroOutMemory(P_hard, a, x, S, K);

  return { user: body.user, expires_at: body.expires_at };
}
```

### 5.3 大数运算

浏览器原生 `BigInt` 支持，但 `modPow` 需要自实现（BigInt 没有原生 modPow）。约 30 行：

```javascript
function modPow(base, exp, mod) {
  let result = 1n;
  base = base % mod;
  while (exp > 0n) {
    if (exp & 1n) result = (result * base) % mod;
    exp >>= 1n;
    base = (base * base) % mod;
  }
  return result;
}
```

1536-bit 模幂在浏览器约 50ms，可接受。

### 5.4 scrypt 在浏览器

Web Crypto API 不直接支持 scrypt。两个选项：

- **A**：纯 JS scrypt 实现（~150 行），慢但可用。
- **B**：用 PBKDF2-HMAC-SHA256（Web Crypto 支持）替代 scrypt，迭代次数 ≥ 600000（OWASP 2023 推荐）。安全性等价，速度更快。

**决策：方案 B**。PBKDF2 + 600000 迭代。`x = H(salt | H('admin:' | PBKDF2(password, salt, 600000)))`。前端用 `crypto.subtle.deriveBits` 直接调 PBKDF2。

CLI 工具同步实现：`crypto.pbkdf2Sync`。前后端用相同 salt 做同样的 KDF——但服务端不参与 KDF，只存 verifier 和 salt，所以 KDF 算法只需前端 + CLI 一致。

### 5.5 登录页流程（`public/app.js` 改造）

```javascript
// 替换原 handleLoginSubmit
async function handleLoginSubmit(e) {
  e.preventDefault();
  const password = new TextEncoder().encode(form.elements.password.value);
  form.elements.password.value = ''; // 立即清 input
  try {
    const session = await srpLogin(password);
    await enterConsole();
  } catch (err) {
    showError(err.message);
  } finally {
    password.fill(0); // 清临时 buffer
  }
}
```

### 5.6 控制面板加 "rotate verifier" 入口

设置页加按钮 "轮换 verifier"，弹窗让管理员输新密码（要求强密码）→ 前端算新 `(salt', v')` → POST `/admin/srp/rotate` → 提示重新登录。

---

## 六、后端实现

### 6.1 文件结构

```
src/
├── srp.js                  # SRP-6a 服务端数学 + 协议
├── srp-group.js            # NIST 1536-bit 常量（共享前端）
├── auth.js                 # 改造：新增 SRP 路由 handler，旧 login 标 410
├── server.js               # 启动时加载 verifier
└── ...
```

### 6.2 SRP 服务端核心（`src/srp.js`）

```javascript
import crypto from 'node:crypto';
import { N, g, H, padTo, b64url, bytesToBigint, bigintToBytes } from './srp-group.js';

export function generateServerEphemeral(verifierBig) {
  const b = randomBigintBelow(N);
  const k = H(padTo(N) | padTo(g));
  const v = verifierBig;
  const B = (k.multiply(v).add(modPow(g, b, N))).mod(N);
  return { b, B };
}

export function computeServerSharedSecret(A, b, v, u) {
  // S = (A * v^u)^b mod N
  return modPow(A.multiply(modPow(v, u, N)).mod(N), b, N);
}

export function computeM1(username, salt, A, B, K) {
  return H(H(N) | H(g) | H(username) | salt | padTo(A) | padTo(B) | padTo(K));
}

export function computeM2(A, M1, K) {
  return H(padTo(A) | padTo(M1) | padTo(K));
}

const challenges = new Map(); // challenge_id -> { A, b, v, k, u, salt, expires_at }

export function createChallenge(A, v, salt) {
  const id = randomId('ch');
  const { b, B } = generateServerEphemeral(v);
  const u = H(padTo(A) | padTo(B));
  challenges.set(id, { A, b, v, k: H(padTo(N) | padTo(g)), u, salt, expires_at: Date.now() + 5 * 60 * 1000 });
  return { challenge_id: id, B };
}

export function takeChallenge(id) {
  const c = challenges.get(id);
  if (!c) return null;
  if (Date.now() > c.expires_at) {
    challenges.delete(id);
    return null;
  }
  challenges.delete(id); // 一次性
  return c;
}

// 定时清理过期（每分钟扫一次）
setInterval(() => {
  const now = Date.now();
  for (const [id, c] of challenges) {
    if (now > c.expires_at) challenges.delete(id);
  }
}, 60 * 1000).unref();
```

### 6.3 路由 handler

`src/auth.js` 增加：

```javascript
import {
  createChallenge, takeChallenge, computeServerSharedSecret,
  computeM1, computeM2,
} from './srp.js';
import { bytesToBigint, bigintToBytes, b64urlDecode, b64urlEncode, padTo, H } from './srp-group.js';

export async function handleSrpChallenge(request, response, ctx) {
  if (!ctx.srp) {
    return errorResponse(response, Object.assign(new Error('SRP not configured'), { statusCode: 503, code: 'srp_not_configured' }));
  }
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || body.username !== ctx.srp.username) {
    return errorResponse(response, Object.assign(new Error('invalid username'), { statusCode: 400, code: 'invalid_username' }));
  }
  if (!body.A) {
    return errorResponse(response, Object.assign(new Error('A required'), { statusCode: 400, code: 'missing_A' }));
  }
  const A = bytesToBigint(b64urlDecode(body.A));
  if (A.mod(N).equals(0n)) {
    return errorResponse(response, Object.assign(new Error('invalid A'), { statusCode: 400, code: 'invalid_A' }));
  }
  const v = bytesToBigint(ctx.srp.verifier);
  const { challenge_id, B } = createChallenge(A, v, ctx.srp.salt);
  json(response, 200, {
    challenge_id,
    salt: ctx.srp.salt.toString('base64'),
    B: b64urlEncode(bigintToBytes(B)),
  });
}

export async function handleSrpVerify(request, response, ctx) {
  if (!ctx.srp) {
    return errorResponse(response, Object.assign(new Error('SRP not configured'), { statusCode: 503, code: 'srp_not_configured' }));
  }
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || !body.challenge_id || !body.M1) {
    return errorResponse(response, Object.assign(new Error('challenge_id and M1 required'), { statusCode: 400, code: 'missing_fields' }));
  }
  const c = takeChallenge(body.challenge_id);
  if (!c) {
    return errorResponse(response, Object.assign(new Error('challenge not found or expired'), { statusCode: 404, code: 'challenge_not_found' }));
  }
  const A = c.A;
  const B = (c.k.multiply(c.v).add(modPow(g, c.b, N))).mod(N);
  const S = computeServerSharedSecret(A, c.b, c.v, c.u);
  const K = H(padTo(S));
  const expectedM1 = computeM1(ctx.srp.username, c.salt, A, B, K);
  const providedM1 = bytesToBigint(b64urlDecode(body.M1));
  if (!timingSafeEqualBigint(expectedM1, providedM1)) {
    insertAudit(ctx.db, { actor: getRequestIp(request), action: 'admin.login.failed', detail: 'srp verify failed' });
    // 触发失败计数（复用 createAuthState）
    ctx.auth.recordFailure(getRequestIp(request));
    return errorResponse(response, Object.assign(new Error('invalid credentials'), { statusCode: 401, code: 'invalid_credentials' }));
  }
  ctx.auth.recordSuccess(getRequestIp(request));
  // 签发 session（复用现有 issueSessionCookie）
  const now = Date.now();
  const payload = { user: ctx.srp.username, iat: now, exp: now + 24 * 60 * 60 * 1000 };
  const { header } = issueSessionCookie(payload, ctx.sessionSecret);
  const M2 = computeM2(A, expectedM1, K);
  insertAudit(ctx.db, { actor: 'admin', action: 'admin.login.success', detail: 'srp' });
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Set-Cookie': header,
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify({
    user: ctx.srp.username,
    expires_at: new Date(payload.exp).toISOString(),
    M2: b64urlEncode(bigintToBytes(M2)),
  }));
}
```

### 6.4 废弃旧 login

`src/auth.js` 的 `handleLogin` 改为：

```javascript
export async function handleLegacyLogin(request, response) {
  const err = new Error('legacy scrypt login deprecated; use /admin/srp/*');
  err.statusCode = 410;
  err.code = 'legacy_login_deprecated';
  errorResponse(response, err);
}
```

`src/admin.js` 路由匹配 `POST /admin/login` 调 `handleLegacyLogin`。

### 6.5 verifier 轮换 handler

```javascript
export async function handleSrpRotate(request, response, ctx, session) {
  // 当前 session 必须有效（已 SRP 登录）
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || !body.salt || !body.verifier) {
    return errorResponse(response, Object.assign(new Error('salt and verifier required'), { statusCode: 400, code: 'missing_fields' }));
  }
  // 校验新 verifier 格式（在 group 内）
  const v = bytesToBigint(b64urlDecode(body.verifier));
  if (v.compareTo(N) >= 0 || v.equals(0n)) {
    return errorResponse(response, Object.assign(new Error('invalid verifier'), { statusCode: 400, code: 'invalid_verifier' }));
  }
  const verifierPath = process.env.SRP_VERIFIER_FILE || process.env.SRP_VERIFIER_PATH;
  const newContent = JSON.stringify({
    username: ctx.srp.username,
    salt: body.salt,
    verifier: body.verifier,
    kdf: 'pbkdf2-sha256-600000',
    created_at: new Date().toISOString(),
  });
  // 原子替换：写临时文件 + rename
  const tmp = `${verifierPath}.tmp.${process.pid}`;
  await fs.writeFile(tmp, newContent, { mode: 0o400 });
  await fs.rename(tmp, verifierPath);
  // 重载到内存
  ctx.srp = { ...ctx.srp, salt: Buffer.from(body.salt, 'base64'), verifier: Buffer.from(body.verifier, 'base64') };
  // 轮换 session secret（旧 session 全失效）
  ctx.sessionSecret = crypto.randomBytes(32).toString('hex');
  insertAudit(ctx.db, { actor: session.user, action: 'admin.verifier.rotated' });
  json(response, 200, { rotated: true });
}
```

---

## 七、CLI 工具

`scripts/generate-srp-credentials.mjs`：

```bash
#!/usr/bin/env node
# 用法：
#   node scripts/generate-srp-credentials.mjs [--insecure-weak]
#   （从 stdin 读密码，不显示在命令行）
```

功能：

1. 从 stdin 读密码（`process.stdin.on('data')` + TTY raw mode 关 echo）。
2. 强度检查（长度 ≥ 12、大小写+数字+符号、不在弱密码字典）。
3. 生成 32 字节 salt。
4. PBKDF2-HMAC-SHA256 600000 迭代 → 32 字节 `P_hard`。
5. `x = H(salt | H('admin:' | P_hard))`。
6. `v = g^x mod N`。
7. 输出 JSON 到 stdout（让 admin 自己重定向到 verifier 文件）。

```bash
$ node scripts/generate-srp-credentials.mjs > /etc/simpleplane-update/verifier
Enter admin password: ****
Confirm password: ****
$ sudo chown simpleplane:simpleplane /etc/simpleplane-update/verifier
$ sudo chmod 0400 /etc/simpleplane-update/verifier
$ sudo systemctl restart simpleplane-update
```

---

## 八、测试矩阵

### 8.1 单元测试

`tests/srp-group.test.mjs`：

- N 和 g 与 RFC 5054 §2.2 完全匹配（hex 比对）。
- `H(N | g)` 与 RFC 5054 测试向量一致。
- `padTo(x)` 在 x 不足 192 字节时左零填充。
- b64url 编解码 roundtrip。

`tests/srp.test.mjs`：

- 注册 + 登录完整握手通过（已知 password + salt → 期望 verifier → 握手成功）。
- 错误 A（0 mod N）→ 400 invalid_A。
- 错误 username → 400 invalid_username。
- 错误 challenge_id → 404 challenge_not_found。
- 错误 M1 → 401 invalid_credentials。
- challenge_id 一次性：第二次 verify 同 id → 404。
- challenge_id 5 分钟过期 → 404。
- M2 不匹配 → 客户端检测。
- 重放：A、B 每次不同；同一 (A, M1) 不能两次成功。
- 离线爆破验证：随机 password + 已知 verifier → 不匹配（保证 verifier 正确性）。

### 8.2 集成测试

`tests/srp-api.test.mjs`：

- 完整 HTTP 流程：POST /admin/srp/challenge → POST /admin/srp/verify → 拿 cookie → GET /admin/versions 200。
- 旧 /admin/login 返回 410。
- 速率限制：11 次 challenge/分钟 → 429。
- 失败锁定：5 次 M1 错 → 429。
- /admin/srp/params 返回 N、g、hash 算法。

### 8.3 全链路 e2e

`scripts/e2e-test.mjs` 加场景：

- `srp_login_success`：用 Node 跑 SRP 客户端数学，完整握手。
- `srp_login_wrong_password`：错误密码 → 401。
- `srp_login_replay_blocked`：同一 (A, M1) 第二次 → 404。
- `srp_login_lockout_429`：5 次错 → 429。
- `srp_rotate_verifier`：登录后 POST /admin/srp/rotate → 旧 session 失效。

### 8.4 NIST 测试向量

RFC 5054 附录 A 提供了 NIST 测试向量。`tests/srp-nist-vectors.test.mjs` 加载 RFC 中的已知 (username, password, salt, verifier) 四元组，校验 `computeVerifier(password, salt) === expected_verifier`。

---

## 九、实施分阶段

### Phase 6.1：SRP 数学 + 共享常量（0.5 天）

- [ ] `src/srp-group.js`：NIST 1536-bit MODP 常量 + `H/padTo/b64url` helpers
- [ ] `public/srp-group.js`：浏览器侧相同常量（ESM 共享，build 时复制）
- [ ] `tests/srp-group.test.mjs`：RFC 5054 §2.2 向量验证
- [ ] `tests/srp.test.mjs`：SRP-6a 注册 + 握手单元

### Phase 6.2：后端 SRP 路由 + verifier 加载（0.5 天）

- [ ] `src/srp.js`：服务端 ephemeral 生成 + 共享密钥 + M1/M2 计算
- [ ] `src/auth.js`：`handleSrpChallenge` / `handleSrpVerify` / `handleSrpRotate`
- [ ] `src/server.js`：启动时从 `SRP_VERIFIER_FILE` 读 verifier
- [ ] `handleLegacyLogin` 返回 410
- [ ] `tests/srp-api.test.mjs`：完整 HTTP 流程集成测试

### Phase 6.3：前端 SRP 客户端 + 登录页改造（0.5 天）

- [ ] `public/srp-client.js`：浏览器 BigInt modPow + PBKDF2 + M1 计算
- [ ] `public/app.js`：`handleLoginSubmit` 改为调 `srpLogin`
- [ ] `public/index.html`：登录表单加"轮换 verifier"入口（可选）
- [ ] 手动浏览器验证：登录成功 + 错密码显示错误 + 内存里无密码残留

### Phase 6.4：CLI 工具 + 部署文档（0.5 天）

- [ ] `scripts/generate-srp-credentials.mjs`：stdin 读密码 + 强度检查 + 输出 verifier
- [ ] `update-server/docs/deployment.md`：加 systemd `LoadCredential` 配置 + verifier 生成步骤 + 轮换流程
- [ ] `update-server/README.md`：更新认证章节

### Phase 6.5：e2e + SDD 文档（0.5 天）

- [ ] `scripts/e2e-test.mjs`：加 5 个 SRP 场景
- [ ] `docs/sdd/update-server/tasks.md`：加 Phase 6 任务
- [ ] `docs/sdd/update-server/verification.md`：加 SRP 测试证据
- [ ] evidence JSON：`evidence/srp-e2e-result.json`

总工作量约 2.5 天。

---

## 十、开放问题

1. **多管理员**：当前 username 固定 "admin"。多管理员时 verifier 文件改多行 `{"users": [{"username":"alice","salt":...,"verifier":...}, ...]}`，路由 `/admin/srp/challenge` 加 `username` 字段查询。Phase 7 决定。
2. **TOTP 二次验证**：SRP 成功后再要 TOTP。TOTP 共享密钥同样要走 systemd credential（`LoadCredential=totp_secret:...`）。Phase 8 决定。
3. **verifier 密钥派生算法升级**：如果未来 PBKDF2-SHA256 不够安全，可改为 Argon2id。但 Argon2 浏览器无原生支持，要纯 JS 实现。先 PBKDF2，留接口升级。
4. **OPAQUE 升级路径**：协议路由保留 `/admin/opaque/*` 命名空间，未来迁移时旧 SRP 保留 6 个月过渡期。
5. **浏览器兼容性**：BigInt 需要 Chrome 67+/Firefox 68+/Safari 14+。2026 年这已经是底线要求，不 polyfill。`crypto.subtle.deriveBits` 同等要求。

---

## 十一、与现有 SDD 的关系

- `docs/sdd/update-server/requirements.md` 加一条"认证必须走 SRP-6a，旧 scrypt 方案废弃"。
- `docs/sdd/update-server/tasks.md` 加 Phase 6 章节，5 个任务（6.1-6.5）。
- `docs/sdd/update-server/verification.md` 加 SRP 测试矩阵 + RFC 5054 向量验证记录。
- `update-server/docs/redesign.md` 不变（改造文档仍描述整体架构，认证细节在本文档）。
- `update-server/docs/deployment.md` 替换认证配置章节。

---

## 十二、验收清单

完成 Phase 6 后按以下流程端到端验证：

- [ ] `npm test` 全绿（含新增 srp-group/srp/srp-api 单元和集成测试）
- [ ] `npm run e2e` 22 + 5 SRP 场景全过
- [ ] RFC 5054 NIST 测试向量通过
- [ ] 浏览器手动：登录 → 错密码显示错误 → 正确密码登录成功 → 内存 dump（devtools Memory snapshot）搜不到密码字符串
- [ ] verifier 文件权限 0400、owner=simpleplane
- [ ] `.env` 文件 grep 不到 `PASSWORD` / `HASH` / `VERIFIER` 字眼
- [ ] `systemctl cat simpleplane-update` 显示 LoadCredential + 沙盒指令
- [ ] 旧 `POST /admin/login` 返回 410
- [ ] verifier 轮换：rotate 后旧 session cookie 失效
