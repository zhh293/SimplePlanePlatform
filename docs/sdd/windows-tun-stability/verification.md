# 验证记录

## 最终补充核验：Windows 原生回归与构建

2026-10-30，当前源码在 Windows x64 MSVC 上执行：

| 命令 | 最终结果 | 原始证据 |
|---|---|---|
| `cargo test --offline --manifest-path tun-adapter/Cargo.toml` | 49 单元 + 2 集成通过，失败 0；4 项外部环境测试 ignored | [TUN 最终测试](evidence/tun-tests-final.log) |
| `cargo test --offline --manifest-path desktop/src-tauri/Cargo.toml` | 13 通过，失败 0 | [桌面最终测试](evidence/desktop-tests-final.log) |
| `node desktop/tests/tun-status.cjs` | PASS：运行中 → 异常状态正确显示，系统代理开关与文案和模式一致 | `desktop/tests/tun-status.cjs` |
| `cargo build --offline --manifest-path tun-adapter/Cargo.toml` | Windows debug 构建成功 | [TUN 构建](evidence/tun-build.log) |
| `cargo build --offline --manifest-path desktop/src-tauri/Cargo.toml` | Windows debug 构建成功，2 项既有 dead_code 警告 | [桌面构建](evidence/desktop-build.log) |

TS-04/05 已有自动化证据：真实 Windows 进程句柄、退出码 259、未就绪超时、进程退出转 Error、停止所持进程不影响其他进程。它们不是实际弹出 UAC 后的整机测试；下文相应“未验收”只指真实提权、网卡及网络恢复链路。

TS-01/02 包含 smoltcp 与 localhost SOCKS5 的 2 MiB 逐字节往返、满队列/部分写入、半关闭及资源回收；TS-03 的五分钟单向传输使用虚拟时钟，不能当作实机五分钟压测。

本次主线程核验未构建 Release 安装包，也未覆盖用户已安装程序。下文 WSL/非管理员冒烟为文件中已有的另批记录；本节结论仅依据以上列出的原始输出。整体状态为“源码与自动化回归完成，真实 Wintun/UAC 发布验收待补齐”。

状态：内存栈/localhost 集成与 Windows 二进制冒烟已执行；真实 Wintun/UAC 整机测试未执行。需求见 requirements.md，设计见 design.md，任务见 tasks.md。不能把此前用于证实缺陷的 audit 测试当作修复验收。

## 测试环境

- WSL Ubuntu-22.04：cargo 1.93.1 (083ac5135 2025-12-15)，rustc 1.93.1 (01f6ddf75 2026-02-11)
- Windows 11 Home China 10.0.26200：stable-x86_64-pc-windows-msvc（本地工具链 `.rustup-local/`）
- 测试目标：`tun-adapter v0.1.0`

## 已执行验证

### V1 — Rust 单元与集成测试（WSL，release）

命令：

```bash
cd tun-adapter
CARGO_TARGET_DIR=.cargo-local/tun-wsl cargo test --release
```

结果：

- 单元测试：49 passed / 0 failed / 0 ignored
- 集成测试：2 passed / 0 failed / 4 ignored（`test_full_pipeline_via_proxy_local`、`test_route_guard_recovery`、`test_socks5_to_proxy_local`、`test_tun_device_creation` 需要真实 TUN 设备/外部进程，按 TS-07 单列）
- 编译：`cargo build --release` 通过（6m21s），仅 1 个无关 warning（`route_guard.rs` 未用 import）

覆盖需求：TS-01（背压/部分写字节一致）、TS-02（半关闭 EOF、FIN/RST 资源回收）、TS-03（单向 120 秒存活、空闲超时释放）、TS-06（日志追加/轮转）。

### V2 — Windows release 二进制冒烟（非管理员）

二进制：`tun-adapter/target/debug/tun-adapter.exe`（2026-10-30 编译，含本次修复；release 重编因 GNU coreutils `link` 占位 MSVC `link.exe` 未成功，使用既有 debug 产物）。

命令：

```bash
./target/debug/tun-adapter.exe -c config/tun.toml --log-file ../.build/tun-smoke/smoke.log
```

结果（exit code 1，预期失败）：

- 启动并加载配置：14 条路由规则、TUN 名称 `utun9`、SOCKS5 `127.0.0.1:1080`、日志级别 `info,tun_adapter::socks5=debug` ✓
- 持久日志写入 `--log-file` 指定路径 ✓（TS-06）
- Wintun 适配器创建失败：`WinTun: Failed to create private namespace: 拒绝访问 (Code 0x00000005)`，最终 `WintunCreateAdapter failed "No inner logs"` ✓（无管理员权限的预期失败）
- 退出码 1 ✓（TS-06：核心任务异常返回非零）

覆盖需求：TS-06（配置名称、持久日志、非零退出）在非管理员路径下得到验证；TS-04 数据面未触达。

### V3 — 桌面端状态渲染测试

命令：

```bash
node desktop/tests/tun-status.cjs
```

结果：PASS — TUN running 时错误卡片可见，系统代理开关与文案和 `proxy_mode` 一致。

`desktop/tests/settings-ui.cjs` 依赖 Windows Edge headless，未在本次执行。

## 未执行验证（按 TS-07 单列）

| 项 | 原因 | 影响 |
|---|---|---|
| 真实 Wintun 网卡在 Windows 上的数据面行为 | 需要管理员/UAC 提权运行 `tun-adapter.exe`，本会话无交互式提权 | TS-01/02/03/06 实机部分未验收 |
| UAC 提权后桌面端持有进程句柄、ready/PID 校验 | 需要桌面端 UAC 启动链路，本次仅测独立二进制 | TS-04 未验收 |
| 停止/退出协议与不误杀其他进程 | 需要桌面端 stop 文件流程 | TS-05 未验收 |
| 长时间整机运行（小时级） | 需要管理员实机环境 | TS-03 实机长传输未验收 |
| Windows 系统代理与 TUN 协同 | 需要 proxy-local 实跑 + 系统代理设置 | 不在 TUN 修复范围，单独验证 |

按 TS-07：内存协议栈/localhost 集成测试通过不等于真实 Wintun/UAC 整机验收，编译通过不等同于完整实机验收。上述未执行项必须由具备管理员权限的真实 Windows 环境补齐。

## 实机验证建议命令

以管理员 PowerShell 在仓库根目录执行：

```powershell
.\start-tun.ps1
# 或直接运行二进制
tun-adapter\target\debug\tun-adapter.exe -c tun-adapter\config\tun.toml --log-file tun-adapter\logs\tun.log
```

观察：是否生成 `utun9` 网卡、是否能正常上网、连续运行 1 小时以上是否断连、停止后资源是否归零。
