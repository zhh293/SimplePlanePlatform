# Bootstrap URL Discovery for Update Server

> 让桌面客户端无需重新打包即可切换 update-server 域名。

## 背景

`desktop/docs/auto-updater-plan.md` 描述了 Tauri Updater 集成。原方案把 endpoint 硬编码到 `tauri.conf.json`：

```json
"endpoints": ["https://github.com/zhh293/SimplePlanePlatform/releases/latest/download/latest.json"]
```

问题：换 update-server 域名/IP 要重新打包桌面端，对用户升级体验不友好。

## 方案：引导 URL（Bootstrap）

桌面客户端启动时先 fetch 一个固定 URL 的 "bootstrap" JSON，里面包含当前 update-server 的真实地址：

```json
{
  "update_server": {
    "url": "https://update.simpleplane.example.com"
  }
}
```

客户端拿到 URL 后构造完整 endpoint：
```
{update_server.url}/api/v1/updates/{{target}}/{{arch}}/{{current_version}}
```

然后用 Tauri Updater 的 Rust API `UpdaterBuilder::endpoints(vec![Url])` 覆盖 `tauri.conf.json` 里的默认 endpoint，调 `.check()` 拿 update info。

### 为什么安全

- **pubkey 不动**：`tauri.conf.json` 里内嵌的 pubkey 是 trust anchor。bootstrap 只换 download URL，不换 pubkey。
- **attacker 控制 bootstrap 的最坏后果**：把客户端指向 attacker 控制的 update-server。但 attacker 拿不出合法签名的安装包（私钥只在管理员本地/CI），Tauri Updater 验签失败，拒绝安装。
- 等价于 DNS 劫持的风险，可接受。

### 为什么不直接让用户配置

- 普通用户不会改配置。
- 引导方案对用户透明，无感切换。
- 多个 bootstrap URL 做 fallback（GitHub raw + GitHub Pages 镜像），任一可用即可。

## 实现

### Rust 侧（`desktop/src-tauri/src/commands.rs`）

新增两个 invoke 命令：

- `check_update_via_bootstrap(bootstrap_urls: Vec<String>) -> Result<CheckUpdateResult, String>`
  - fetch 第一个可用 bootstrap URL → 拿 update-server URL
  - 用 `UpdaterExt::updater().endpoints(vec![Url::parse(...)?])?.build()?.check().await?` 检查更新
  - 返回 `UpdateAvailable { endpoint, version, notes, pub_date }` 或 `NoUpdate`
  - 把 `endpoint` 返回给 JS，让后续 `install_update` 直接用，不重新 fetch bootstrap

- `install_update(endpoint: String, on_event: Channel<UpdateProgress>) -> Result<(), String>`
  - 用同一 endpoint 重新 check（避免跨 invoke 持有 `Update` 对象，Tauri State 不支持非 `Send + Sync + 'static`）
  - 调 `update.download_and_install(on_chunk, on_finish)` 下载+验签+安装
  - 通过 `Channel` 把进度事件流式发到 JS（`Started { content_length }` / `Progress { chunk_length }` / `Finished`）
  - JS 侧 await 完成后调 `process.relaunch()` 重启

### JS 侧（`desktop/web/app.js`）

`checkForUpdates()` 改造：
1. 从 `window.__TAURI__.config.plugins.updater.bootstrap.endpoints` 读 bootstrap URL 列表
2. `invoke('check_update_via_bootstrap', { bootstrapUrls })`
3. 返回 `UpdateAvailable` 时调 `showUpdateDialog({ version, body: notes, endpoint, pub_date })`

`showUpdateDialog` 改造：
1. 弹更新对话框（同原 UI）
2. 用户点"立即更新"→ 创建 `Channel` + `onmessage` 处理进度
3. `invoke('install_update', { endpoint, onEvent: channel })`
4. 完成后 `window.__TAURI__.process.relaunch()`

### 配置（`desktop/src-tauri/tauri.conf.json`）

```json
"plugins": {
  "updater": {
    "endpoints": [
      "https://github.com/zhh293/SimplePlanePlatform/releases/latest/download/latest.json"
    ],
    "pubkey": "（不变）",
    "bootstrap": {
      "endpoints": [
        "https://raw.githubusercontent.com/zhh293/SimplePlanePlatform/main/bootstrap.json",
        "https://zhh293.github.io/SimplePlanePlatform/bootstrap.json"
      ],
      "cache_ttl_seconds": 3600
    }
  }
}
```

- `endpoints`（plugin 官方字段）保留作为 fallback：如果 bootstrap 失败（网络不通、所有 bootstrap URL 都挂），退化到 GitHub Release 拉取。
- `bootstrap` 是自定义字段，不被 plugin 识别，只给我们自己的代码读。

### Bootstrap 文件（仓库根 `bootstrap.json`）

```json
{
  "update_server": {
    "url": "https://update.simpleplane.example.com"
  }
}
```

提交到仓库根，GitHub raw + GitHub Pages 自动镜像。

## 部署流程

### 第一次部署 update-server

1. 部署 update-server 到 `https://update.simpleplane.example.com`（按 `update-server/docs/deployment.md`）。
2. 编辑仓库根 `bootstrap.json`，把 `update_server.url` 改为实际域名。
3. 提交 + push main。GitHub raw + Pages 自动更新。
4. 桌面客户端下次启动时 fetch bootstrap → 拿到新 URL → 切到自托管 update-server。

### 后续换域名

1. 编辑 `bootstrap.json` 改 URL。
2. push main。
3. 客户端自动切换，无需重新打包。

### 紧急回滚到 GitHub Release

1. 编辑 `bootstrap.json`，把 `update_server.url` 改为 `https://github.com/zhh293/SimplePlanePlatform/releases/latest/download`（让 `/api/v1/updates/...` 路径在 GitHub 上 404，客户端会 fallback 到 `tauri.conf.json` 的 `endpoints`）。
2. push main。
3. 客户端 fetch bootstrap 失败或拿到无效 URL → 走 fallback endpoint（GitHub Release `latest.json`）。

## API 兼容性

- Tauri Updater plugin 的 Rust API（`UpdaterExt`、`UpdaterBuilder::endpoints`、`Update::download_and_install`）基于 plugin 2.10.1 源码对照实现。
- `Channel<UpdateProgress>` 的 serde schema 与 plugin 官方 `DownloadEvent` 一致（`tag = "event", content = "data"`，`rename_all = "camelCase"`），让 JS 侧 `onmessage` handler 与原 plugin 的 JS API 行为对称。

## 编译注意事项

- 加了 `reqwest = "0.12"` 和 `url = "2"` 依赖。reqwest 与 tauri-plugin-updater 内部一致版本，不增加二进制大小。
- features 选 `rustls-tls` 避免原生 OpenSSL 依赖（Windows 不需要 vcpkg）。
- `default-features = false` 关掉 reqwest 默认的 native-tls。
