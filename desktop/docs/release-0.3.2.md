# SimplePlane Desktop 0.3.2

Windows TUN 在断网重连或物理出口变化后自动重建路由、DNS 和代理连接，减少手动关闭再开启 TUN 的需要。

## 更新内容

- 检测物理接口、IPv4 地址、网关、DNS 变化及 DHCP 重置 DNS；网络稳定后在原管理员进程中恢复，无需再次申请 UAC。
- 保留 FakeIP 域名映射，关闭旧连接并按当前节点策略重新连接；手动节点重建失败时也会退避重试。
- 精确清理本进程创建的 Windows 路由，退出时保留 DHCP 当前默认网关，避免恢复旧网关。
- 保留 0.3.1 的共享路由策略、规则热更新、直连出口刷新和 macOS 回环地址修复。

## 验证

本地通过 77 项 Rust 单元测试、2 项 Rust 集成测试、20 项 Java 节点测试及 8 项桌面配置测试。IP/网关变化和离线重连使用模拟测试覆盖；真实 Windows 上两轮 DHCP DNS 重置后，完整恢复耗时分别为 20.569 秒、19.913 秒，Bing 与百度均可访问。旧 TCP 连接需要应用重试。

GitHub 打包流程在 Windows x64、macOS ARM64 上运行节点恢复、TUN、桌面和界面回归测试，再生成安装包及 updater 签名。自动网络变化检测适用于 Windows；macOS 包包含共享代理核心的恢复接口和既有修复。

## 上传更新服务

- Windows updater：`SimplePlane_0.3.2_x64-setup.exe` 及同名 `.sig`。
- macOS ARM64 updater：`SimplePlane.app.tar.gz` 及同名 `.sig`；归档内应用版本为 0.3.2。
- Windows MSI、macOS DMG 供独立安装使用。

安装包必须配套使用同一次构建的签名。分支构建通过 GitHub Actions artifacts 提供下载，不自动发布正式 Release。

详细恢复设计和本机验证见 [测试记录](../../docs/design/tun-network-recovery-2026-11-07.md)。
