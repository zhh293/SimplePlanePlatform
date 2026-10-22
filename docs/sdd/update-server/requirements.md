# Update Server 自托管分发规格与验收约束

详细设计以 [redesign.md](../../../update-server/docs/redesign.md) 为准，实施项见 [tasks.md](tasks.md)，实测记录见 [verification.md](verification.md)。

- 服务端只分发，不执行客户端卸载/安装；卸载安装由 Tauri Updater 客户端完成。服务端返回 Tauri Updater 规范 JSON，不返回任何执行命令。
- Tauri 私钥只在管理员本地/CI，服务端永远不持私钥、永远不代签；服务端被攻破也无法投毒客户端。
- Mac/Windows 双平台独立版本序列，各自 SemVer 严格单调递增；上传版本号 ≤ 当前 latest 一律拒绝。
- 同一 `(platform, version)` 不可重复上传；版本号即时间戳，一次发布一个版本号。
- 公开端点只暴露最新版，客户端无论当前版本都只能看到 latest；204 表示无更新。
- 上传走分块 + 断点续传（tus-like 协议），upload session 持久化到 SQLite，服务端重启不丢断点；24h 未 complete 自动清理。
- 控制面板所有写操作必须管理员认证；公开接口 `/latest.json` / `/api/v1/updates/*` / `/download/*` 无认证只读。
- 认证必须走 SRP-6a PAKE 协议（前端两步握手：`/admin/srp/challenge` + `/admin/srp/verify`），密码从不离开浏览器。旧 `POST /admin/login` 返回 410 Gone。
- SRP verifier（不是密码 hash）由 CLI 工具生成、存到 0400 权限的文件、通过 systemd `LoadCredential` 注入到进程私有 namespace。`.env` 不含任何密码字段。
- 服务端进程内存中只持有 verifier（`g^x mod N`，不可反推密码），不持有 password 或其派生值。
- 当前 latest 版本不可删除；非 latest 版本可删除（文件 + 元数据一起删）；notes 字段可改，其他字段不可改。
- 完成上传（complete）不等于发布（publish）；publish 时再次校验单调递增，防止 complete 后 latest 被改。
- 保留零运行时依赖：用 Node 内置 `node:sqlite`、`crypto.scrypt`（替代 bcrypt，避免原生依赖）、`crypto HMAC`（session cookie 签名）。
- `/api/v1/updates/*` 响应 `Cache-Control: no-store`；`/download/*` 响应 `Cache-Control: public, max-age=31536000, immutable` + `Accept-Ranges: bytes`。

验收必须分别提供单元、集成、回归、全链路证据；未执行或失败的项目明确保留，不能用另一层测试代替，不把文档或编译成功当成全链路成功。
