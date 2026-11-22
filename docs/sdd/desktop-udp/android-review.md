# Android UDP 追加 review 记录

日期：2026-11-21。用户要求将已有 Android 改动一并提交到 `feat/desktop-udp-sdd-canary-20261121`，在桌面提交 e8014bf 后以独立提交追加。本次整理提交现有工作区实现，没有修改 Android 应用页面或将其打包发布。

## 范围

- plane-core/src/udp.rs：IPv4 UDP 校验、四元组关联、DIRECT/PROXY/REJECT、来源约束、FakeIP 回包、队列与空闲限制。
- tcp_stack.rs / dispatcher.rs / lib.rs：UDP 数据面接线，DNS 保留本地路径，网络变更时清理关联。
- proxy_proto.rs：与 Java 对齐的 UDP_CONNECT=6、udp-v1 请求/应答能力确认。
- outbound.rs / outbound_h3.rs / transport.rs：H2/H3 UDP 建流、逐数据报发送与相关回归测试。

## 本次验证

使用 WSL Ubuntu 22.04、Rust 1.93.1，运行完整 plane-core 核心测试：

```text
CARGO_HOME=/mnt/e/SimplePlanePlatform/.cargo-local cargo test --offline --locked --manifest-path plane-core/Cargo.toml --target-dir .build/android-udp-adaptation/target
```

结果：**119 项中 117 通过、0 失败、2 项既有环境依赖测试 ignored**。忽略项为 crypto_remote_echo 和 configured_node_http_probe，分别需要外部测试服务/密钥配置，未计为通过。Doc-tests 无测试项。

通过项包含 H2/H3 数据报与能力确认、UDP 空包和最大长度、校验和/分片拒绝、DIRECT、REJECT、protect 失败、错误来源/超限回包、FakeIP、DNS、本地关联隔离和空闲回收等。测试中的 TUN harness 使用 UnixDatagram fd 接入生产 AndroidTun/stack/dispatcher，出站网络使用主机 socket/测试对端；**不代表 Android 手机 VpnService、JNI protect 回调或真实设备公网已验收**。

默认 WSL Cargo 缓存首次离线尝试缺少 rustc-hash，未进入编译；改用仓库内已有完整缓存后上述测试通过。没有修改依赖、锁文件或跳过失败用例以完成回归。

结构化结果与提交源码指纹见 [测试结果](evidence/android-review-tests.json)。原始日志保存在 `.build/udp-canary-20261121/android-review-tests-shared-cache.log`，原始文件哈希包含在结果中。当前提交供合并审查，不将桌面灰度和 Wintun 的通过结果套用到 Android 实机。
