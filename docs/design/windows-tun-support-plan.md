# Windows TUN 支持改造方案

## 1. 目标

完善 Windows TUN 支持，使发布包自带 Wintun 运行组件，首次启用时能正确获取管理员权限，并可靠完成虚拟网卡、路由、DNS 的启动和恢复网络状态。本方案不改变代理协议和远端服务协议。

## 2. 当前问题

当前链路是：Tauri 桌面端 -> tun-adapter.exe -> tun2 -> wintun-bindings -> wintun.dll。

已确认的问题：

- tun2 依赖 Wintun API 绑定，但不会自动把 wintun.dll 放入发布目录。
- 资源脚本尝试复制 tun-adapter/wintun.dll，但仓库和当前 resources 目录中都没有该 DLL。
- Tauri 配置没有显式声明 resources/wintun.dll。
- Windows 普通权限启动失败后，会尝试运行 SimplePlane-TUN 计划任务，但安装流程没有创建该计划任务或 Windows Service。
- 5 秒内未确认网卡时，代码仍可能把 TUN 标记为运行中，产生假成功。
- 共享 tun.toml 使用 utun9，这是 macOS 接口名称；Windows 应使用 SimplePlane。

## 3. 目标架构

### Windows

桌面程序以普通权限运行，通过 UAC 或 Windows Service 控制管理员权限的 TUN 进程。Service 负责启动/停止 tun-adapter、监控子进程、创建 Wintun 网卡以及网络恢复；桌面端负责发送命令、读取状态和展示日志。

建议服务名：SimplePlaneTun；显示名：SimplePlane TUN Service。

### macOS

保持现有路径：SimplePlane.app -> sudo -n tun-adapter -> macOS utun。Windows Service、Wintun DLL、Windows 注册表和 Windows 命令不得进入 macOS 执行路径。

## 4. Wintun 与发布包

### 4.1 DLL 来源

使用官方 WireGuard Wintun 发布包中的对应架构 DLL：

- x64：wintun/bin/amd64/wintun.dll
- ARM64：wintun/bin/arm64/wintun.dll

### 4.2 目录布局

Windows 运行目录必须包含：

    resources/
    ├─ tun-adapter.exe
    ├─ wintun.dll
    └─ 其他运行时资源

wintun.dll 必须与 tun-adapter.exe 位于同一目录，不依赖系统 PATH，也不要求用户单独安装 Wintun。

### 4.3 构建脚本

修改 desktop/scripts/collect-resources.ps1：

1. 检查 tun-adapter.exe 和 wintun.dll。
2. 缺少任意一个文件时，Windows 构建直接失败。
3. 将 DLL 复制到 desktop/src-tauri/resources/wintun.dll。
4. 输出 DLL 架构、版本和 SHA-256。

修改 desktop/src-tauri/tauri.conf.json，显式加入 resources/tun-adapter.exe 和 resources/wintun.dll。macOS 资源脚本不复制或校验 Wintun DLL。

## 5. 提权方案

### 5.1 P0：UAC 直接启动

先通过 Windows Shell Execute 或 runas 请求一次 UAC，启动：

    tun-adapter.exe --config <用户配置目录>\tun.toml

要求：

- UAC 被拒绝时不能显示 TUN 运行中。
- 启动后必须确认进程、虚拟网卡、网卡地址和路由均存在。
- 标准输出和错误输出必须进入日志。
- 删除对不存在的 SimplePlane-TUN 计划任务的假设。

### 5.2 P1：Windows Service

正式发布使用按需启动的 SimplePlaneTun Service。安装、升级和卸载必须幂等：已安装且兼容时复用，路径或协议不兼容时停止后升级，卸载时先停止再删除。服务不存在时必须明确报告尚未安装。

桌面端与 Service 建议使用本机命名管道，不开放 TCP 控制端口。建议操作包括 start、stop、status、logs。命名管道只允许本机用户或管理员访问。

## 6. Windows 启动流程

点击一键 TUN 后依次执行：

1. 检查 proxy-local 是否监听。
2. 检查 tun-adapter.exe、wintun.dll 和 tun.toml。
3. 检查 SimplePlaneTun Service；不存在则请求 UAC 安装。
4. 请求服务启动 TUN。
5. 确认 tun-adapter 进程存在。
6. 确认 SimplePlane 网卡存在且地址为 198.18.0.1。
7. 确认 0.0.0.0/1、128.0.0.0/1 和远端节点绕行路由存在。
8. 全部通过后才向 UI 报告运行中。

任意一步失败，必须停止进程、删除新增路由、删除新增 NRPT 规则并恢复 DNS。

## 7. 配置改造

平台默认网卡名必须按目标系统生成：

    Windows: SimplePlane
    macOS:   utun9

不能把 Windows 名称写入 macOS 配置。Windows 开启全局路由前，proxy_remote_ips 必须包含远端节点解析后的 IP，避免代理自身连接再次进入 TUN 形成路由环路。

Windows 诊断至少检查：

    Get-NetAdapter -Name SimplePlane
    Get-NetIPAddress -InterfaceAlias SimplePlane
    Get-NetRoute -DestinationPrefix 0.0.0.0/1
    Get-NetRoute -DestinationPrefix 128.0.0.0/1

不能只使用 tasklist 判断 TUN 成功。

## 8. 任务拆分

### P0：使 Windows TUN 可启动

- [ ] 获取并固定 x64/ARM64 Wintun DLL。
- [ ] 修改 Windows 资源收集脚本。
- [ ] 修改 Tauri Windows 资源声明。
- [ ] 修正 Windows TUN 配置默认名称。
- [ ] 增加 DLL、二进制、网卡和路由诊断。
- [ ] 实现明确的 UAC 提权启动。
- [ ] 启动成功必须确认真实网卡和路由。

### P1：完善生命周期

- [ ] 实现 SimplePlaneTun Windows Service。
- [ ] 增加服务安装、升级、卸载流程。
- [ ] 增加命名管道控制协议。
- [ ] Service 崩溃和桌面端退出时恢复网络。
- [ ] 增加 Windows 防火墙和网络类别诊断。

### P2：增强稳定性

- [ ] 支持多出口网卡选择，避免误选其他虚拟网卡。
- [ ] 支持远端域名动态解析和多 IP 绕行。
- [ ] 增加服务版本协商和自动修复。
- [ ] 增加 Windows 11 24H2、虚拟机和 VPN 并存测试。

## 9. macOS 兼容性要求

1. Wintun 代码必须使用 cfg(target_os = windows)。
2. Windows Service 安装器不进入 macOS 构建流程。
3. macOS 继续使用 utun、sudo -n、networksetup 和现有 DNS/路由恢复逻辑。
4. macOS 发布包不要求 wintun.dll。
5. 跨平台配置测试分别断言 Windows 和 macOS 的默认网卡名称。

## 10. 验收标准

### Windows

- [ ] 安装包包含 tun-adapter.exe 和匹配架构的 wintun.dll。
- [ ] 普通权限启动桌面程序，首次开启 TUN 出现 UAC。
- [ ] UAC 批准后创建 SimplePlane 网卡。
- [ ] 网卡地址为 198.18.0.1，主路由和远端绕行路由存在。
- [ ] 浏览器和不支持代理设置的应用可以访问测试目标。
- [ ] 停止 TUN 后网卡、路由、DNS/NRPT 恢复。
- [ ] 缺少 DLL、拒绝 UAC、启动超时均不会显示运行中。

### macOS

- [ ] 仍能创建 utun。
- [ ] 系统代理模式不受影响。
- [ ] TUN 仍能执行现有 sudo 流程。
- [ ] 停止 TUN 后 DNS 和路由恢复。
- [ ] macOS 打包不依赖 Wintun DLL。

## 11. 实施顺序

1. 固定 Wintun DLL 并补齐 Windows 资源打包。
2. 修正 Windows 配置模板和平台默认值。
3. 实现一次性 UAC 提权启动。
4. 完成网卡、路由和 DNS 的真实验证。
5. 在 Windows 环境完成 P0 验收。
6. 实现 Windows Service，替换一次性 UAC 启动。
7. 补充多网卡、VPN 和 Windows 11 24H2 兼容性处理。

## 12. 回滚方案

如果 Windows Service 改造出现问题，保留 P0 的 UAC 直接启动模式作为临时后备。回滚时必须先停止 TUN、恢复路由和 DNS，再卸载服务或替换程序文件。

参考：

- tun2 Windows 文档：需要将匹配架构的 wintun.dll 放到可执行文件同目录，并以管理员权限运行。
- Mihomo TUN 文档：Windows 使用 Wintun，并通过管理员权限完成系统级路由配置。
