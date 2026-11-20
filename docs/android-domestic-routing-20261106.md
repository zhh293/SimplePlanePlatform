# Android 国内资源分流与 DNS 阻塞修复

## 报告的现象和定位范围

用户报告 B 站、腾讯文档、微信公众号在 Wi-Fi 和移动网络下都会间歇性打不开，
关闭 VPN 后立即恢复。另有截图显示 HTTP/3 CONNECT 到 `mtalk.google.com:5228`
等待 15 秒超时，但界面将其归类为“协议编解码失败 / 检查密钥”和整个连接失败。

本次以 `upstream/main` 的 `769306c` 为基线，在
`fix/android-domestic-routing` 分支处理。以下两个代码缺陷由回归测试复现；
本次 ADB 未发现设备，尚未取得手机故障时的实时日志，也没有完成真机复测。

## 已确认的缺陷与修改

1. Android 原有的国内域名表较小，遗漏部分 API/CDN 域名。
   `www.bilibili.com`、`docs.qq.com` 和 `mp.weixin.qq.com` 原本就直连，
   但 `api.biliapi.net`、`biliimg.com`、`bilivideo.net`、`gtimg.com`、
   `weixinbridge.com`、`tencent-cloud.net` 等资源域名会落到默认代理。
   FakeDNS 给出的 `198.18.0.0/15` 地址不是目标真实地址，后续 GeoIP 检查不能
   弥补域名遗漏。现在 Android 编译时嵌入仓库桌面端已有的 direct-list 快照，
   启动时索引后缀和精确域名，避免每条连接扫描整个列表。
   自定义规则仍优先，关闭 `cn_direct` 仍关闭自动直连。
   快照来源、日期和许可证保留在原 provider 目录；未引入在线下载依赖。
2. 直连和节点建连在 Tokio 网络工作线程中同步调用 Android
   `Network.getAllByName`。原生运行时只有两个工作线程，慢 DNS 会占用处理
   TUN、QUIC 和定时器的线程。现在 DNS 通过 `spawn_blocking` 执行，最多同时
   运行 16 个解析，排队和等待结果共享 10 秒期限。超时后正在运行的系统解析
   仍持有名额，直到实际结束，避免超时重试无限创建 DNS 工作线程。
   IP 字面量不经过 DNS；Android 仍只通过底层物理网络解析，不退回 VPN DNS。
3. HTTP/2、HTTP/3 CONNECT 超时使用独立的 `Timeout` 错误类型，
   不再将等待超时直接解释为协议或密钥错误。界面对 `proxy_error` 显示
   “VPN 已启动；代理请求失败”，真实核心 `error` 仍显示连接失败。
   该修改保留错误详情，没有将失败请求显示成成功。
4. APK 版本更新为 `0.1.3-domestic-fix` / versionCode 4。
   native 版本仍来自 Cargo 的 `0.1.0`，不能单独用于辨认 APK 版本。
   Android CI 增加 provider 目录路径触发器。

## 验证

- 基线 Rust 测试：100 通过，2 项按原配置忽略。
- 仅添加两项回归测试时：100 通过、2 失败、2 忽略。
  国内资源测试在 `api.biliapi.net` 得到 Proxy 而非 Direct；
  模拟 600 ms 的同步 DNS 时，单线程网络运行时的定时器延迟约 611 ms。
- 修复后 Rust 测试：104 通过、0 失败、2 忽略。
  覆盖 11 个国内页面/资源域名、FakeDNS 路径、精确与后缀匹配边界、
  自定义代理覆盖、关闭自动直连，以及慢 DNS 不阻塞网络任务。
- HTTP/3 等待 CONNECT 应答的超时测试另外验证返回 `CoreError::Timeout`，通过。
- `cargo fmt --all -- --check`、`cargo clippy --locked --all-targets -- -D warnings` 通过。
- Android `assembleDebug`、`testDebugUnitTest` 成功，配置测试 6 通过。
- arm64-v8a、armeabi-v7a、x86_64 三种 ABI 的 release 原生库编译成功；
  APK 内三份库与此次构建输出逐字节一致，均包含新的域名 provider。
- APK ZIP 完整性、v2 签名和包信息检查通过：`com.proxy.android`，
  versionCode 4，Android 7.0+，本机调试签名。

产物：`SimplePlane-0.1.3-domestic-fix-20261106.apk`，32,541,678 字节。

SHA-256：`8d8731ffdff572dbce2724a5c944364516fbfb9569e4872395be8846dcbe81e7`。

## 尚待真机验证

安装后断开并重新连接 VPN，在 Wi-Fi、移动网络分别检查 B 站页面与视频、
腾讯文档、微信公众号图片和正文。若仍异常，需要采集同一次请求的域名、
路由判定和连接错误，区分资源分流、解析、目标可达性和底层网络问题。

当前数据面仍未实现通用应用 UDP 转发（DNS 单独处理）；使用 UDP/QUIC 的应用
仍可能等待 TCP 回退，这次没有补齐该功能。节点 HTTP/3 出站与应用自己的
UDP 转发是两个不同层次。系统 DNS 调用开始后不能由 Tokio 强制中断，
此补丁限制等待期限与并发数，不代表系统解析本身已取消。

截图中的单次 CONNECT 超时不足以确定服务端、目标或移动链路哪一侧出了问题，
也不足以断言本次两个缺陷能解释所有手机故障。长期运行和网络切换仍需实测。
