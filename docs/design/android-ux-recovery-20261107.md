# Android 0.1.4：体验与断网恢复改进

日期：2026-11-07。基于上一轮 Android Studio 模拟器评估中的可复现问题实现。

## 已完成

| 原问题 | 本次行为 |
| --- | --- |
| 连接、节点、规则、导入和日志挤在一张长表单中 | 拆成连接、节点、路由、设置四个底部导航入口；首页保留状态、当前节点和本次累计流量。 |
| 节点密钥设置了 password 类型却显示明文 | 修正 `setSingleLine` 覆盖密码 transformation 的顺序；默认遮挡，提供主动显示按钮。重建页面后恢复遮挡。 |
| 输入内容后，字段提示消失 | 节点和规则表单改用 Material TextInputLayout 的常显标签；TLS、加密方式收进高级选项。 |
| 旋转后未保存编辑丢失 | 保存原始草稿，包括动态节点、空端口、未完成规则、导入文本、预设名称及当前页面，不将草稿自动写为有效配置。 |
| 系统断网后仍显示已连接 | 监听非 VPN 网络的 available/lost/capabilities 回调。丢失底层网络时显示“等待网络恢复”，保持 VPN 开启。 |
| 恢复网络后的首批请求撞到旧 QUIC 连接 | 底层网络变化时通过 JNI 通知 dispatcher，替换连接缓存并取消旧网络上的代理和直连流。新请求使用新网络重新建连，不迁移或重放旧请求。 |
| QUIC 超时被展示成协议编解码失败 | 显式超时及 QUIC 的类型化关闭原因分别归类为超时、网络中断；真正的帧和加密错误保留原类别。 |
| 深色模式无效、禁用按钮仍像可点击 | 使用 DayNight 主题和两套颜色；按钮使用 Material 的禁用状态颜色，并说明连接期间不能改配置。预设按钮纵向排列，适应大字体。 |
| 通知无法返回应用或断开 VPN | 增加内容 PendingIntent 和断开操作；设置页提供通知权限入口，拒绝权限不阻断 VPN。 |
| 停止时可能在主线程等待阻塞 DNS | Rust runtime 使用后台关闭方式取消异步任务，不同步等待尚未返回的系统 DNS 调用；DNS 的既有并发上限保留。 |

沿用现有 Kotlin Views、Material 和 Rust JNI，没有迁移 UI 框架或修改服务端协议。APK 版本为 `0.1.4` / versionCode `5`。

## 验证结果

- Android：构建成功；6 项配置单元测试通过；Lint 0 errors、8 warnings。警告涉及旧 targetSdk、依赖版本、已有图标资源及背景重复绘制。
- 仪器测试：3 项通过，覆盖 JNI 加载、密码遮挡，以及页面重建后原始多节点/规则/导入草稿和当前页面的恢复。
- Rust：在 x86_64 Android 模拟器执行测试二进制，106 passed、0 failed、2 ignored；Clippy `--all-targets -D warnings` 通过。
- 新增网络切换测试使用真实本地 HTTP/3 mock：旧流被关闭、第二条流创建新的受保护 socket、累计代理连接 2、失败连接 0。
- 三种 ABI 的 release 内核重新编译并打包：arm64-v8a、armeabi-v7a、x86_64。检查 arm64/x86_64 ELF LOAD 段对齐均为 `0x4000`。
- API 36.1、1080×2400 模拟器：检查首页、节点、连接期间禁用规则页、深色模式及 `font_scale=2.0` 设置页。
- 断网实测：关闭模拟器 Wi-Fi 和移动数据；系统报告 `Active default network: none`，VPN underlying networks 为空，页面显示“等待网络恢复”。恢复移动数据 5 秒后发出的第一批 4 个 HTTP/HTTPS 请求全部返回 200，使用 FakeDNS 地址 `198.18.0.5`，页面恢复“已连接”。这验证了该场景，不代表最短恢复时间或性能基准。
- 正常连接后另一次探测 4/4 返回 200。主动断开后页面回到“未连接”，日志确认 nativeStop 完成。

本地证据和可安装 APK 位于工作区同级 `android-review-20261106` 目录；日期沿用上一轮评估目录。主要证据：

| 文件 | 内容 |
| --- | --- |
| `20-new-home.png`、`21-new-nodes.png` | 新首页和默认遮挡密钥的节点表单 |
| `24-new-routing-running.png` | 连接期间的说明和禁用样式 |
| `25-new-offline.png`、`26-new-recovered.png` | 断网和恢复后的真实页面 |
| `27-new-dark-home.png`、`28-new-large-settings.png` | 深色模式和两倍字体 |
| `30-final-disconnected.png` | 主动断开后的页面 |
| `probe-new-recovery-first.jsonl` | 恢复后第一批请求，4/4 成功 |
| `probe-new-connected.jsonl` | 稳定连接时的请求，4/4 成功 |
| `new-offline-connectivity.txt`、`final-app-logcat.txt` | 系统网络状态、两次连接缓存清理及 nativeStop |
| `android-instrumentation-final.txt`、`rust-tests.txt`、`rust-clippy.log`、`build-network-fix.log` | 测试和构建结果 |

## 验证边界与后续优先级

通知内容和操作已编译，并在服务通知对象中确认存在操作；本轮没有代替用户授予通知权限，因此没有完成通知栏实际点击回归。可在“设置 → 开启状态通知”手动允许后使用。

没有将模拟器结果视为真机 Wi-Fi/蜂窝无缝切换、长时间后台存活、耗电、16 KB 页设备或慢 DNS 故障注入的验证。一般 UDP 转发、完整 IPv6 支持、订阅/二维码导入、按应用分流、节点手动选择和测速仍属于后续功能。

VPN 启动过程中已有请求可能受到系统网络切换影响。一次在点击连接后立即启动、早于 TUN 稳定的探测出现 DNS 暂时失败，保留在 `probe-during-vpn-start.jsonl`；它不作为已建立 VPN 的连通性结果。本次没有实现透明迁移在途请求。

下一阶段建议按顺序推进：

1. 真机网络切换、后台存活与通知操作验收，完成 SDK/发布兼容性升级。
2. 节点选择、订阅与二维码、按应用分流，降低日常配置成本。
3. 按既有设计补一般 UDP、完整 IPv6，以及持续流量速率和耗电测量。

本次的密钥修复限于界面遮挡，不等于完成凭据存储加密或备份策略整改。
