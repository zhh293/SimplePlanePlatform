# TUN 与桌面直连表、代理表脱节修复

## 已确认的问题

桌面设置中的 `proxy.yml.route` 已包含用户直连表、代理表和内置域名 provider，但 `write_tun_node_config()` 原来只补充代理节点 bypass 地址，TUN 仍使用独立的 `tun.toml.routing`。

本机生成的 `tun-nodes.toml` 只有九条旧规则，主要覆盖美团、`.cn`、内网和百度；没有读取完整的国内域名表。2026-11-07 的运行日志确认：`www.bilibili.com`、`i1.hdslb.com`、`bvc.bilivideo.com`、`cm.bilibili.com` 被调度为 `PROXY`。

TUN 的 SOCKS5 连接以 `127.0.0.2` 为源地址。Java 为防止再次走系统 DNS/路由进入 TUN，会跳过这些连接的 DIRECT 决策。因此 Java 的直连表不能在后续链路补救 TUN 已作出的 PROXY 决策。

## 修复行为

- 桌面生成的 TUN 配置增加 `route_source`，指向与界面共用的 `proxy.yml`。该共享策略作为 TUN 的权威路由源，替代生成文件中遗留的静态 routing 列表。
- 兼容已安装的旧桌面端：TUN 加载名为 `tun-nodes.toml` 的配置时，如果同目录存在 `proxy.yml`，自动采用该共享策略。普通独立 `tun.toml` 不会自动切换策略源。
- 支持共享策略的用户规则、直连表、代理表、系统直连列表、内置/文件域名 provider 和默认动作；保持现有 Java 的用户规则顺序：显式规则 → directList → directProviders → proxyProviders → proxyList → 默认动作。普通列表域名保留精确匹配，`*.` 使用域名后缀语义。
- 内置 provider 与 Java 使用同一份仓库快照。启动时编译为哈希集合，域名按标签逐级查找；不会为每条连接线性扫描十多万条域名。
- 补充精确 IP、端口范围、protocol、match 等共享策略需要的匹配方式；域名匹配统一去首尾空白、转小写并去末尾点。
- 每秒检查共享配置和外部 provider 文件的修改时间，后台编译完整规则后发布新快照。已建立连接保持原路径，新连接使用新规则；非法更新保留最后一份有效快照并限频重试。
- `--check-route <域名> [--check-port <端口>]` 可只检查规则，不创建 TUN、不修改 DNS 或路由。

本次没有改变 Java 端既有的规则热更新行为；新增的自动更新针对 TUN 的共享策略。系统 bypass 路由、远端连接参数、TUN MTU 等仍按原配置处理。

## 验证

- Windows：63 项单元测试通过，2 项可运行集成测试通过；4 项需要独立网络环境的集成测试保持忽略。
- 桌面配置模块：8 项测试通过。
- 新 release 可执行文件使用本机真实 `tun-nodes.toml` 和 `proxy.yml`，以下 12 个域名检查通过：

| 决策 | 域名 |
| --- | --- |
| DIRECT | www.bilibili.com、i1.hdslb.com、bvc.bilivideo.com、cm.bilibili.com、www.taobao.com、www.jd.com、www.qq.com、www.baidu.com、www.zhihu.com |
| PROXY | www.google.com、www.youtube.com、api.openai.com |

覆盖了显式代理/拒绝覆盖内置直连表、完整域名与通配符语义、provider 的 `full:` 和标签边界、外部文件更新、非法规则保留旧快照，以及文件监控实际应用新规则。

## 本机部署状态

修复版已替换到 `E:/SimplePlane/resources/tun-adapter.exe`，同时更新工作区的 `desktop/src-tauri/resources/tun-adapter.exe`。

- 新版 SHA-256：`192C991BA141F3A62584A4A6D5393784C94C19363C474B28600064400030279C`
- 旧版备份：`E:/SimplePlane/resources/tun-adapter.before-shared-routing-20261107-121608.exe`
- 当前运行中的旧 TUN 进程未被终止。需要在客户端断开并重连一次 TUN，新进程才会加载修复版；仅保存界面配置不能升级已运行的旧代码。
- 更新后的 TUN 后续可自动加载保存的路由修改，已有连接需自然结束或由应用重新建立后才改变路径。

## 独立发现的直连 DNS 问题

两轮真实访问探测中，当前旧 TUN 进程访问 `www.baidu.com` 都被重置，日志明确报告 `Direct DNS failed for www.baidu.com`。同机绑定实际 WLAN 的 DNS 查询和 HTTPS 访问均成功。

当时 WLAN 为接口 3、地址 `172.16.27.172`、网关 `172.16.24.1`，DHCP DNS 为 `202.118.66.6`。物理出口查询百度 DNS 约 7–11 ms，HTTPS 首次响应约 0.69–1.26 秒；旧 TUN 路径两次都在返回 HTTP 前失败。

这说明卡顿不一定只有规则缺失一个原因。旧实现会在启动时固定物理接口、源地址和 DNS，运行中不会刷新；当前错误的具体内部原因尚未取得更细的日志，不能直接宣称已证明是网络切换。重连会重新捕获出口信息，但重连后的真实访问效果仍需验证。

另外一次物理出口访问 `i1.hdslb.com` 的单个 CDN IP 超时，另一轮成功；淘宝物理直连也出现约 1.2–5.8 秒波动。因此，命中 DIRECT 本身不能保证每个 CDN 地址或访问时段都快速。

证据目录：`../../.build/tun-route-fix/`，包括 `native-route-checks.json`、`domestic-before-and-physical.json`、`physical-egress.json` 和 `deployment.json`。路由检查是配置级验证，不等同于已更新运行中旧进程或完成新版本的在线验收。
