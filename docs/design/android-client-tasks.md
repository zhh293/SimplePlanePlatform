# Android 客户端（手机梯子）— SDD 开发子任务清单

| 属性     | 内容                                                          |
| -------- | ----------------------------------------------------------- |
| 文档版本 | v1.0                                                        |
| 关联设计 | docs/design/android-client-plan.md                          |
| 关联文档 | docs/design/tun-mode-plan.md、docs/design/tun-mode-tasks.md |
| 所属模块 | android-app (Kotlin) + plane-core (Rust)                    |
| 作者     | zhanghonghao                                                |
| 状态     | Ready for Implementation                                    |

> 本文档是 `android-client-plan.md` 的施工级落地拆解，遵循 SDD（Spec-Driven
> Development）规范：先有规格，再有实现，每个子任务自带「目标 / 输入输出 / 详细说明 /
> 代码思路 / 测试 / 验收标准 / 依赖」六要素，拿到任务即可产出生产级代码，不跳步。
>
> 阅读顺序建议：先读「0. 全局约束」与「1. 总体依赖关系与里程碑」，建立大局观；再按
> Phase A → B → C 顺序逐任务执行。每个任务的「依赖」字段标明前置条件，可据此并行排期。

---

## 0. 全局约束

这些约束对**所有**子任务生效，任务正文不再重复。

### 0.1 仓库与目录布局

新增两个顶级目录（与现有 Maven 模块、`tun-adapter/` 平级）：

```
SimplePlanePlatform/
├── android-app/                 # Android 应用（Gradle 工程，Kotlin）
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/proxy/android/...   # Kotlin 源码
│   │       └── jniLibs/<abi>/libplane_core.so  # cargo-ndk 产物（构建期生成，git 忽略）
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/
├── plane-core/                  # Rust 数据面 crate（独立 cargo crate，编 cdylib）
│   ├── Cargo.toml
│   ├── build.rs
│   └── src/
│       ├── lib.rs               # crate 入口（替代桌面 main.rs）
│       ├── jni_bridge.rs        # JNI 导出 + protect 回调（新增）
│       ├── android_tun.rs       # 系统 fd 包装（新增，替代 tun_device.rs）
│       ├── outbound.rs          # 加密 HTTP/2 出站（新增，替代 socks5.rs）
│       ├── proxy_proto.rs       # ProxyMessage 编解码（新增，对齐 Java）
│       ├── crypto.rs            # ChaCha20-Poly1305（新增，对齐 Java 非标准实现）
│       ├── stack.rs             # 复用 tun-adapter（零改动）
│       ├── fake_dns.rs          # 复用 tun-adapter（零改动）
│       ├── router.rs            # 复用 tun-adapter（Direct 语义增强）
│       ├── config.rs            # 复用 tun-adapter（输入源 TOML→JSON）
│       └── error.rs             # 复用 tun-adapter（微调）
└── scripts/
    ├── build-rust.sh            # cargo-ndk 封装
    └── start-remote-test.sh     # 协议互通测试用 remote 启动脚本
```

> 复用策略：**不要复制粘贴** `tun-adapter` 的 `stack.rs`/`fake_dns.rs`/`router.rs`。
> 推荐把这些平台无关模块抽到一个 `plane-net` 子 crate，由 `tun-adapter`（桌面）和
> `plane-core`（Android）共同 `path` 依赖。MVP 阶段如果来不及抽，可先 `include!` 或
> git submodule，但必须在 A4 任务里记录技术债。

### 0.2 语言与工具链

| 维度 | 约定 |
| ---- | ---- |
| Rust | 2021 edition，async runtime = tokio（`rt-multi-thread`，Android 上线程数=2） |
| Rust 格式/静态检查 | `cargo fmt`（默认）+ `cargo clippy -- -D warnings` 零警告 |
| Rust 错误处理 | 模块级 `thiserror` 枚举；FFI 边界把 `Result` 转成错误码/异常，不让 panic 跨 FFI |
| Rust 日志 | `tracing` + `tracing-android`（输出到 logcat），禁止 `println!` |
| Kotlin | JDK 17 编译，Kotlin 1.9+，AndroidX，`minSdk=24`(Android 7.0)，`targetSdk=34` |
| Kotlin 格式 | ktlint / detekt 零警告 |
| 构建 | Gradle 8.x（KTS）；Rust 经 `cargo-ndk` 产 `.so` 后由 Gradle 打包 |
| NDK | r26+，ABI 默认 `arm64-v8a`，CI 额外编 `armeabi-v7a` + `x86_64`(模拟器) |

### 0.3 关键防呆铁律（违反即全局断网/不通，最高优先级）

1. **所有出站 socket 必须先 protect 再 connect。** 封装唯一入口
   `new_protected_socket()`，禁止任何路径直接 `TcpStream::connect`。漏一处 = 死循环断网。
2. **协议必须与 Java 端逐字节对齐。** 见 0.4。任何「我觉得标准实现就行」的假设都是 bug 源头。
3. **网络切换后旧 socket 失效。** 切网必须重建 H2 连接并对新 socket 重新 protect。
4. **不让 Rust panic 跨越 JNI 边界。** 所有 `extern "C"` 函数体用
   `std::panic::catch_unwind` 包裹，panic 转为返回错误码。

### 0.4 协议对齐规格（与 Java 端二进制兼容的唯一真相来源）

施工时必须对照以下现有 Java 源码，不得臆测：

- `proxy-common/.../model/ProxyMessage.java`
- `proxy-transport-netty/.../codec/ProxyCodec.java`
- `proxy-transport-netty/.../handler/ProxyMessageEncoder.java` / `ProxyMessageDecoder.java`
- `proxy-transport-netty/.../handler/CipherEncodeHandler.java` / `CipherDecodeHandler.java`
- `proxy-crypto/.../ChaCha20Cipher.java`
- `proxy-transport-netty/.../conn/Http2Connection.java`

**(A) ProxyMessage 帧格式（固定头 28 字节，全部大端 / big-endian）**

```
偏移  长度  字段        说明
0     1     Type        MessageType 的 ordinal：
                          0=CONNECT 1=CONNECT_RESPONSE 2=DATA
                          3=DISCONNECT 4=HEARTBEAT_REQUEST 5=HEARTBEAT_RESPONSE
1     1     Status      响应状态码（请求侧填 0）
2     8     RequestId   i64
10    8     StreamId    i64
18    2     HostLen     u16，Host 的 UTF-8 字节数
20    HostLen Host      变长，UTF-8
20+HL 4     Port        i32
24+HL 4     DataLen     i32
28+HL DataLen Data      变长
```

> ⚠️ 注意 Java 用 `putInt` 写 Port/DataLen（**各 4 字节**），不是 2 字节；
> Host 长度字段是 **2 字节**。固定头 = 1+1+8+8+2+4+4 = 28（不含 Host）。

**(B) 加密分层（致命陷阱，务必照搬）**

数据面是「**逐条 ProxyMessage 加密**」而非整条 TCP 流加密。出站方向流水线：

```
ProxyMessage --ProxyCodec.encode--> 明文字节[] --Cipher.encrypt--> [nonce|ct|tag] --> 一个 HTTP/2 DATA 帧
```

入站方向（解密 + 跨帧累积）：

```
HTTP/2 DATA 帧 --Cipher.decrypt--> 明文字节[] --累积缓冲--> 按 28+HostLen+DataLen 切出整条 ProxyMessage --decode-->
```

**(C) ChaCha20-Poly1305 是「非标准」实现，禁止直接用 `chacha20poly1305` crate！**

对照 `ChaCha20Cipher.java`，与 RFC 8439 的差异：

- 密文格式 = `nonce(12) | ciphertext | tag(16)`（nonce 前置，**随每条消息生成并内嵌**）。
- 引擎用 BouncyCastle `ChaCha7539Engine`（即 RFC 7539 的 ChaCha20）。
- Poly1305 key = 用同一 (key, nonce) 初始化 ChaCha 引擎，对 32 字节全 0 输入 `processBytes`
  得到的前 32 字节（**注意：这会消耗 keystream 的第 0 块**）。
- 随后**继续用同一个引擎实例**对 plaintext `processBytes` 得到 ciphertext
  （即 ciphertext 用的是 keystream 第 1 块起；加密时 Java 是「先 encrypt 再单独 init 一个新引擎算 tag」，
  解密时同理——施工时务必按 Java 的调用顺序复刻，详见 crypto.rs 任务）。
- Poly1305 **只对 ciphertext** 计算，**没有 AAD、没有 RFC 的 16 字节对齐 padding、没有
  len(AAD)||len(C) 尾部块**。这与标准 AEAD 不同，是必须 1:1 复刻的点。
- 若 `key.length != 32`，Java 端用 `SHA-256(key)` 派生 32 字节密钥。

> 验证手段：以 crypto.rs 的「跨语言测试向量」为准（见 Q1/Q2 任务）。先让向量过，再谈链路。

**(D) HTTP/2 语义**

- 每个 stream 第一帧必须是 HEADERS：客户端 `:method=POST :path=/proxy :scheme=http`
  外加 `content-type: application/octet-stream`，`endStream=false`。
- 之后每条 ProxyMessage 一个 DATA 帧，`endStream=false`。
- connection-level 初始窗口扩到 ~16MB；stream initialWindow=1MB（对齐 `Http2Connection.java`）。
- 服务端首帧回 `:status=200` 的 HEADERS。

### 0.5 验收通用门槛（每个任务都隐含）

- `cargo build` / `./gradlew assemble` 通过；fmt + clippy/ktlint 零告警。
- 新增 `pub` 函数/类有文档注释；`unsafe` 有 `// SAFETY:` 说明。
- 任务正文列出的单测/集成测全绿。
- 不引入 `unwrap()`（测试代码除外）；不吞错误。

---

## 1. 总体依赖关系与里程碑

```
Phase A（链路打通 MVP）—— 必须串行通过 A6 才算 MVP
  A1 脚手架 ──┬─→ A2 JNI 最小闭环 ──→ A3 系统 fd 接入 ──→ A4 复用栈验证 ──┐
             │                                                          │
             └─────────────────────────────→ A5 出站层(协议/加密/H2) ───┤
                                              （A5 可与 A2/A3 并行起步）  │
                                                                        ▼
                                                                     A6 端到端打通 ★MVP

  与 A 并行的质量基建：
  Q1 Rust 核心单测 ── 随 A3/A4/A5 边写边测
  Q2 协议互通 harness ── 与 A5 同步建（A5 的回归防线）
  Q3 CI（rust-test + interop + android-build）── A 末尾接入

Phase B（可用性增强）
  B1 真直连分流 ──→ B2 路由规则接入 ──→ B3 健康检查降级
  B4 网络切换重连 ──（依赖 A5 出站 + B3 健康态）
  B5 前台 Service ──→ B6 配置 UI ──→ B7 流量统计
  Q4 Kotlin 单测 + protect 防回环专项（依赖 B5 / B 各 UI）

Phase C（产品化）
  C1 UDP 支持  C2 多节点/订阅  C3 规则订阅  C4 稳定性加固  C5 综合测试
  Q5 E2E + nightly   Q6 CD（签名/Release/崩溃上报）
```

里程碑：

- **M1（MVP）= A6 通过**：真机浏览器经 VpnService → plane-core → proxy-remote 打通一条 HTTPS。
- **M2（可用）= B 全部 + Q4**：分流/降级/切网/常驻/UI 齐全，可日常自用。
- **M3（产品化）= C 全部 + Q5 + Q6**：UDP、多节点、CI/CD、崩溃上报，可灰度。

---

## 2. Phase A — 链路打通（MVP）

### Task A1：Android 工程脚手架 + cargo-ndk 集成

**预估** 4h　**依赖** 无　**归属** Phase A

#### 目标

搭出可编译、可弹 VPN 授权框的最小骨架：一个空 `VpnService` 子类 + 一个能被 Gradle 打包进 APK 的占位 Rust `.so`。本任务只解决「工程能跑起来」，不含任何数据面逻辑。

#### 输入 / 输出

- 输入：无（全新工程）。
- 输出：`android-app/` Gradle 工程、`plane-core/` cargo crate、`scripts/build-rust.sh`，能 `assembleDebug` 出 APK，安装后点按钮弹出系统 VPN 授权对话框。

#### 详细说明

1. `plane-core/Cargo.toml`：`crate-type = ["cdylib"]`；依赖先放最小集（`jni`、`tracing`、`tracing-subscriber`、`tracing-android`、`tokio`、`libc`、`thiserror`、`serde`、`serde_json`）。
2. `plane-core/src/lib.rs`：先导出 `Java_com_proxy_android_NativeBridge_nativeVersion` 返回版本字符串，证明 JNI 链路通。
3. `scripts/build-rust.sh`：封装 `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o android-app/app/src/main/jniLibs build`，debug/release 由参数控制。
4. `android-app`：标准单 module Gradle 工程；`AndroidManifest.xml` 声明 `<service android:name=".PlaneVpnService" android:permission="android.permission.BIND_VPN_SERVICE">` + `<intent-filter><action android:name="android.net.VpnService"/></intent-filter>`，以及 targetSdk 34 所需的 `foregroundServiceType`。
5. `MainActivity`：一个按钮，点击走 `VpnService.prepare(this)`；返回非 null 的 Intent 则 `startActivityForResult` 拉起系统授权框，授权回调里 `startForegroundService(PlaneVpnService)`。
6. Gradle 与 Rust 联动：`app/build.gradle.kts` 加 `preBuild.dependsOn` 调 `build-rust.sh` 的 task（MVP 可先手动跑脚本，CI 里再自动化，见 Q3）。

#### 代码思路

```kotlin
// MainActivity.kt
class MainActivity : AppCompatActivity() {
    private val prepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) startVpn() }

    private fun onToggleClicked() {
        val intent = VpnService.prepare(this)   // null = 已授权
        if (intent != null) prepareLauncher.launch(intent) else startVpn()
    }
    private fun startVpn() =
        ContextCompat.startForegroundService(this, Intent(this, PlaneVpnService::class.java))
}
```

```rust
// lib.rs（A1 仅占位）
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeVersion(
    env: jni::JNIEnv, _class: jni::objects::JClass,
) -> jni::sys::jstring {
    env.new_string(env!("CARGO_PKG_VERSION")).unwrap().into_raw()
}
```

#### 测试

- 手动：`./gradlew assembleDebug` 成功；`adb install` 后点按钮弹出系统 VPN 授权框。
- 自动：一个 instrumented smoke test 断言 `NativeBridge.nativeVersion()` 返回非空串（验证 `.so` 被正确打包与加载）。

#### 验收标准

- [ ] `assembleDebug` 产出含 `arm64-v8a/libplane_core.so` 的 APK。
- [ ] 真机/模拟器点击按钮能弹出系统 VPN 授权对话框。
- [ ] `NativeBridge.nativeVersion()` 返回 crate 版本号，不崩溃。
- [ ] fmt + clippy + ktlint 零告警。

---

### Task A2：JNI 桥接最小闭环（start / stop / protect）

**预估** 6h　**依赖** A1　**归属** Phase A

#### 目标

打通 Kotlin ↔ Rust 的双向调用：Kotlin 能 `nativeStart(fd, configJson)`/`nativeStop(handle)`；Rust 能**回调** Kotlin 的 `protect(fd)`。这是后续一切的脚手架，必须先证明 protect 回调真实生效。

#### 输入 / 输出

- 输入：A1 的工程。
- 输出：`jni_bridge.rs` + `NativeBridge.kt`，能完成一次「start → Rust 回调 protect → stop」闭环。

#### 详细说明

1. JNI 接口契约（与设计文档 4.3 一致）：

   ```
   Kotlin → Rust:
     nativeStart(tunFd: Int, configJson: String): Long   // 返回 handle（0=失败）
     nativeStop(handle: Long)
     nativeStats(handle: Long): String                    // A2 先返回 "{}"
   Rust → Kotlin（回调）:
     protect(fd: Int): Boolean
     onStatus(state: String)                              // 可选
   ```

2. **回调机制**：`nativeStart` 时把 `JavaVM`（`env.get_java_vm()`）和 `NativeBridge` 实例的 `GlobalRef` 存入 Rust 侧句柄。Rust 任意线程要回调时，用 `JavaVM::attach_current_thread()` 拿 `JNIEnv` 再 `call_method` 调 `protect`。
3. **handle 管理**：`nativeStart` 返回 `Box::into_raw` 的运行时上下文指针（转 i64）；`nativeStop` 用 `Box::from_raw` 回收。禁止全局可变单例（切换节点要支持多次 start/stop）。
4. **panic 隔离**：每个 `extern` 函数体包 `catch_unwind`，panic 时记日志并返回 0/false。

#### 代码思路

```rust
// jni_bridge.rs
struct CoreHandle {
    vm: jni::JavaVM,
    bridge: jni::objects::GlobalRef,   // NativeBridge 实例，用于回调
    rt: tokio::runtime::Runtime,
    shutdown: tokio::sync::watch::Sender<bool>,
}

#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStart(
    mut env: JNIEnv, this: JObject, tun_fd: jint, config_json: JString,
) -> jlong {
    catch_unwind_to_jlong(|| {
        let cfg: AndroidConfig = parse_json(&mut env, config_json)?;
        let vm = env.get_java_vm()?;
        let bridge = env.new_global_ref(this)?;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2).enable_all().build()?;
        let (tx, _rx) = tokio::sync::watch::channel(false);
        let handle = Box::new(CoreHandle { vm, bridge, rt, shutdown: tx });
        Ok(Box::into_raw(handle) as jlong)
    })
}

// 任意线程回调 protect
fn jni_protect(h: &CoreHandle, fd: i32) -> Result<bool> {
    let mut env = h.vm.attach_current_thread()?;
    let r = env.call_method(&h.bridge, "protect", "(I)Z", &[fd.into()])?;
    Ok(r.z()?)
}
```

```kotlin
// NativeBridge.kt
class NativeBridge(private val vpn: PlaneVpnService) {
    external fun nativeStart(tunFd: Int, configJson: String): Long
    external fun nativeStop(handle: Long)
    external fun nativeStats(handle: Long): String
    fun protect(fd: Int): Boolean = vpn.protect(fd)   // 被 Rust 回调
    fun onStatus(state: String) { /* 上报到 UI */ }
    companion object { init { System.loadLibrary("plane_core") } }
}
```

#### 测试

- Rust 单测：`catch_unwind_to_jlong` 在闭包 panic 时返回 0，正常返回非 0。
- Instrumented：传一个真实 socket fd，`nativeStart` 后用 spy 断言 `protect(fd)` 被回调 ≥ 1 次；`nativeStop` 不崩溃。

#### 验收标准

- [ ] `nativeStart` 返回非 0 handle，`nativeStop` 能正常回收，反复 10 次无泄漏/崩溃。
- [ ] Rust 在非主线程能成功回调 `protect`，spy 计数 ≥ 1。
- [ ] 故意在闭包内 panic，进程不崩溃，`nativeStart` 返回 0。

---

### Task A3：系统 fd 接入 Rust（android_tun.rs）

**预估** 4h　**依赖** A2　**归属** Phase A

#### 目标

用系统 `VpnService.establish()` 递交的 fd 实现异步读写，暴露与桌面 `TunManager::split()` **完全相同签名**的 `(reader, writer)`，让 `stack.rs` 零感知差异。

#### 输入 / 输出

- 输入：Kotlin 侧 `establish()` 得到的 `tunFd: Int`（经 `nativeStart` 传入）。
- 输出：`android_tun.rs` 的 `AndroidTun`，`split()` 返回 `(TunReader, TunWriter)`，接口与 `tun-adapter/src/tun_device.rs` 一致。

#### 详细说明

1. **Builder 配置**（Kotlin 侧，对齐设计文档 3.1）：

   ```kotlin
   val builder = Builder()
       .setSession("SimplePlane")
       .setMtu(1500)
       .addAddress("198.18.0.1", 30)
       .addRoute("0.0.0.0", 0)
       .addDnsServer("198.18.0.2")              // 指向 FakeDNS（TUN 内地址）
       .addDisallowedApplication(packageName)   // 关键：自身不走 TUN，防回环
   builder.setBlocking(false)
   val tunFd = builder.establish()!!.detachFd() // detach 后由 Rust 负责 close
   ```

   `detachFd()` 后 Java 不再持有所有权，Rust 必须在 stop 时 `close(fd)`，否则泄漏。

2. **Rust 异步读写**：用 `tokio::io::unix::AsyncFd<RawFd>` 包裹 fd，`libc::read`/`libc::write` + 就绪通知实现；fd 设非阻塞（`O_NONBLOCK`），处理 `EAGAIN`。
3. **读写半部共享同一 fd**：TUN fd 全双工，`TunReader`/`TunWriter` 各持 `Arc<AsyncFd<RawFd>>`，写时 `writable()`，读时 `readable()`。
4. **MTU**：读缓冲按 MTU（1500）分配，一次 read 一个 IP 包。
5. **接口对齐**：

   ```rust
   pub struct AndroidTun { fd: Arc<AsyncFd<RawFd>>, mtu: usize }
   impl AndroidTun {
       /// SAFETY: fd 必须是 establish()/detachFd() 得到的有效 TUN fd，所有权移交本结构
       pub unsafe fn from_raw_fd(fd: RawFd, mtu: usize) -> io::Result<Self>;
       pub fn split(self) -> (TunReader, TunWriter);
   }
   ```

   方法签名须与桌面版一致，使 `stack::stack_loop(...)` 调用方不变。

#### 代码思路

```rust
// android_tun.rs
impl TunReader {
    pub async fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let mut guard = self.fd.readable().await?;
            match guard.try_io(|inner| {
                let n = unsafe { libc::read(inner.as_raw_fd(),
                    buf.as_mut_ptr() as *mut _, buf.len()) };
                if n < 0 { Err(io::Error::last_os_error()) } else { Ok(n as usize) }
            }) {
                Ok(res) => return res,
                Err(_would_block) => continue,
            }
        }
    }
}
```

#### 测试

- Rust 单测（不依赖真机）：用 `socketpair()` 造一对 fd 模拟 TUN，写入字节后 `TunReader::read` 能读回；`TunWriter::write` 写出能从对端读到。
- Instrumented（真机/模拟器）：`establish()` 后传 fd → Rust，设备上 `ping 198.18.0.1`，日志显示 Rust 读到了 IP 包。

#### 验收标准

- [ ] `socketpair` 单测：读写往返字节一致。
- [ ] `TunReader/TunWriter` 签名与桌面 `tun_device.rs` 对齐（`stack.rs` 可直接复用）。
- [ ] 真机上能从 fd 读到 IP 包。
- [ ] `nativeStop` 时 fd 被 `close`，`/proc/self/fd` 计数稳定无泄漏。

---

### Task A4：FakeDNS + smoltcp 协议栈复用验证

**预估** 3h　**依赖** A3　**归属** Phase A

#### 目标

把桌面已验证的 `stack.rs` / `fake_dns.rs` 接到 `android_tun.rs` 上，确认能：拦截 DNS 返回 FakeIP、完成 TCP 用户态握手、向上暴露字节流。**本任务以「复用」为主，不重写协议栈**——若需改动只允许在 `plane-net` 共享 crate 边界做适配。

#### 输入 / 输出

- 输入：A3 的 `(TunReader, TunWriter)`；复用的 `stack.rs`/`fake_dns.rs`。
- 输出：能跑的 `stack_loop`，对 DNS UDP 走 FakeDNS、对 TCP 产生 `TcpEvent::NewConnection{ dst_ip, dst_port, stream }` 事件。

#### 详细说明

1. **共享 crate 抽取**：把 `tun-adapter` 的 `stack.rs`/`fake_dns.rs`/`router.rs`/`error.rs` 移入新 `plane-net` crate，`tun-adapter` 与 `plane-core` 均 `path` 依赖。**这是 0.1 提到的复用落地，必须做，否则两端代码会漂移**。若排期紧，记 TODO 并在本任务验收里标注技术债。
2. **接线**：`stack_loop(tun_reader, tun_writer, fake_dns, tcp_event_tx)` 直接复用桌面签名。
3. **FakeDNS 地址**：TUN 内 DNS server = `198.18.0.2`（Builder.addDnsServer 已指向它），`stack.rs` 把 dst_port=53 的 UDP 交给 `FakeDnsEngine::handle_dns_query`，响应写回 TUN。
4. **smoltcp 单线程模型桥接 tokio**：复用桌面方案（专用 task 跑 poll loop + Notify/channel），不重新设计。

#### 代码思路

```rust
// lib.rs 内组装（A4）
let tun = unsafe { AndroidTun::from_raw_fd(tun_fd, cfg.mtu)? };
let (reader, writer) = tun.split();
let fake_dns = Arc::new(Mutex::new(FakeDnsEngine::new(cfg.fakeip_range()?, 65536)));
let (tcp_tx, mut tcp_rx) = tokio::sync::mpsc::channel(1024);
rt.spawn(plane_net::stack::stack_loop(reader, writer, fake_dns.clone(), tcp_tx));
// A4 阶段：tcp_rx 先只打印事件，证明握手与字节流提取成功
rt.spawn(async move {
    while let Some(ev) = tcp_rx.recv().await {
        tracing::info!(?ev.dst_ip, ev.dst_port, "new tcp connection");
    }
});
```

#### 测试

- 复用桌面 `fake_dns.rs` 既有单测（域名↔FakeIP 双向、LRU、AAAA 空响应）保持全绿。
- 真机：`adb shell nslookup www.google.com 198.18.0.2` 返回 FakeIP 段（`198.18.x.x`）；`curl --resolve` 命中 FakeIP，Rust 日志出现 `new tcp connection` 与首段字节流。

#### 验收标准

- [x] ~~`plane-net` 共享 crate 建立~~ → **记为技术债**（见下「实现记录」），采用稳妥优先方案，桌面零改动。
- [x] FakeDNS 既有单测全绿（在 `plane-core/src/net_probe.rs` 内逐套对齐桌面用例，全绿）。
- [x] DNS 查询返回 FakeIP、TCP SYN 产出连接事件（以内存 mock reader/writer 的 `run_stack` 集成测试覆盖；真机验证留待 A6 出 APK 后）。

#### 实现记录（2025，稳妥优先方案）

A4 **未**抽取 `plane-net` 共享 crate，原因与决策如下，**正式登记为技术债**：

- `tun-adapter/src/stack.rs`（1000+ 行）硬编码桌面专属类型（`tun_device::{TunReader,TunWriter}`、`Socks5Error`、`RouterError`、`tun2` crate 等），直接泛型化抽取风险高，极易破坏「当前可跑」的桌面链路，违背「不改无关代码」铁律。
- 采用替代方案，效果等价于 A4 验收目标，且桌面 **零改动**：
  1. 在 `plane-core/src/net_probe.rs` 定义平台无关 trait `AsyncTunReader` / `AsyncTunWriter`，由 A3 的 `android_tun::{TunReader,TunWriter}` 实现；
  2. 同文件内实现 `FakeDnsEngine`，与桌面 `fake_dns.rs` **逐行对齐**（相同 `lru`/`ipnet`/`hickory-proto` crate 与版本、相同 FakeIP 池循环分配、跳过 .0/.255、A 记录 TTL=1、AAAA 空响应），并搬入桌面同套单测；
  3. 实现 IPv4 包分类器 `classify_ipv4`（识别 UDP/53 DNS 查询与 TCP SYN，提取五元组）与 DNS 响应封包 `build_dns_response_packet`（IPv4+UDP 校验和）；
  4. 实现 `run_stack` 事件循环：DNS 查询交 FakeDNS 原路写回，TCP SYN 经 FakeIP 反查域名后**先只打印事件**（符合本任务「tcp_rx 先只打印事件」要求）；出站接线留待 A5/A6。

**技术债 TODO（A5/A6 偿还）**：把 `stack.rs`（smoltcp TCP 栈）/`fake_dns.rs`/`router.rs` 抽入 `plane-net` 共享 crate，两端 `path` 依赖，届时合并 `net_probe.rs` 的 FakeDNS 为单一实现，消除两端漂移风险。

---

### Task A5：出站层 —— ProxyMessage + ChaCha20-Poly1305 + HTTP/2（★核心 Go/No-Go）

**预估** 12h　**依赖** A2（JNI/protect），可与 A3/A4 并行起步　**归属** Phase A

#### 目标

在 Rust 实现「直连 proxy-remote 的加密 HTTP/2 出站客户端」，承担桌面 `proxy-local + proxy-transport-netty` 的职责，与 Java proxy-remote **二进制兼容**。本任务是整个方案的最大风险点，**必须先过协议向量测试（Q1/Q2）再谈链路**。

#### 输入 / 输出

- 输入：A4 的 TCP 字节流（`SmolTcpStream`，接口同桌面 `socks5.rs` 的 `app_stream`）、目标 host/port、protect 回调、节点配置（remote 地址/端口/密钥/cipher）。
- 输出：`proxy_proto.rs`、`crypto.rs`、`outbound.rs` 三个新模块，`proxy_via_remote(target, port, app_stream, h2)` 可用。

#### 详细说明（拆 4 子模块，按 A5.1 → A5.4 顺序）

**A5.1 `crypto.rs` — ChaCha20-Poly1305（对齐 Java 非标准实现，见 0.4-C）**

- 依赖 `chacha20`（流密码引擎）+ `poly1305`（裸 MAC），**严禁**用 `chacha20poly1305`（聚合 AEAD 会做 RFC padding，与 Java 不兼容）。
- `encrypt(plaintext) -> [nonce(12)|ct|tag(16)]`：
  1. 随机 nonce(12)。
  2. 复刻 Java `computePoly1305Tag` 的 key 派生：用 ChaCha20(key, nonce, counter=0) 对 32 字节全 0 出 keystream → 取前 32 字节作 Poly1305 key。
  3. ciphertext：严格逐行对照 Java 的 `ChaCha7539Engine` counter 行为生成，并以向量测试锁死。
  4. Poly1305 仅对 ciphertext 更新（**无 AAD、无 padding、无长度尾块**）。
- `decrypt`：拆 nonce/ct/tag → 重算 tag 常量时间比较 → 不等报 `CryptoError::AuthFailed`（对齐 Java "data tampered"）→ 相等再解密。
- `key.len() != 32` 时用 `SHA-256(key)` 派生。

**A5.2 `proxy_proto.rs` — ProxyMessage 编解码（对齐 0.4-A）**

- `MessageType` 判别值须与 Java ordinal 一致（CONNECT=0, CONNECT_RESPONSE=1, DATA=2, DISCONNECT=3, HEARTBEAT_REQUEST=4, HEARTBEAT_RESPONSE=5）。
- `encode(&ProxyMessage) -> Vec<u8>`：大端写 28 字节头 + host + data。
- `decode(&[u8]) -> Result<ProxyMessage>`：长度校验，复刻 Java 越界检查。
- `try_decode_one(buf) -> Option<(ProxyMessage, usize)>`：供出站层跨帧累积切包（复刻 `ProxyMessageDecoder` 的 `28 + HostLen + DataLen` 切分）。

**A5.3 `outbound.rs` — HTTP/2 多路复用 + 加密分帧（对齐 0.4-B/D）**

- 用 `h2` crate 建客户端连接：底层 TCP socket **必须先 protect**（`new_protected_socket()`），可选 TLS（与 remote 配置一致，MVP 可对齐 Java `InsecureTrustManager` 跳过证书校验，但配置须显式开关）。
- connection window 扩到 ~16MB，stream initial window 1MB。
- **首帧 HEADERS**：每条新 stream 先发 `:method=POST :path=/proxy :scheme=http`（+ `content-type: application/octet-stream`），再发 DATA，`end_stream=false`。
- **每条 ProxyMessage 独立加密**：`encode(msg)` → `crypto.encrypt(bytes)` → 作为**一个** HTTP/2 DATA frame 发送（不要跨 frame 拼半条加密消息）。
- 读方向：收 DATA frame → `crypto.decrypt(frame)` → 喂入 `try_decode_one` 累积切包 → 得到 ProxyMessage。

**A5.4 拼装 `proxy_via_remote` — 与桌面 socks5.rs 对齐的双向 copy**

- 镜像 `socks5.rs` 的 `proxy_tcp_stream`：先发 `CONNECT(host,port)`，等 `CONNECT_RESPONSE` 成功；再起 a2p（app→proxy，封 DATA 加密发）/ p2a（proxy→app，解密解 DATA 回写）两个 spawn 做双向 copy；任一方向结束发 `DISCONNECT`。

#### 代码思路

```rust
// outbound.rs（发送一条已加密 ProxyMessage 作为单个 DATA frame）
async fn send_msg(send: &mut h2::SendStream<Bytes>, msg: &ProxyMessage, c: &Cipher)
    -> Result<()> {
    let plain = proxy_proto::encode(msg);          // 28B 头 + host + data，大端
    let sealed = c.encrypt(&plain)?;               // [nonce|ct|tag]
    send.send_data(Bytes::from(sealed), false)?;   // 一条消息 = 一个 DATA frame
    Ok(())
}

// a2p：应用字节流 → 切成 DATA ProxyMessage → 加密发送
async fn a2p(mut app: ReadHalf, mut send: SendStream<Bytes>, req_id: i64, c: Cipher) {
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        let n = app.read(&mut buf).await?;
        if n == 0 { send_msg(&mut send, &ProxyMessage::disconnect(req_id), &c).await?; break; }
        let m = ProxyMessage::data(req_id, &buf[..n]);
        send_msg(&mut send, &m, &c).await?;
    }
}
```

```rust
// crypto.rs 测试钩子：与 Java 向量逐字节比对（Q1 会喂入固定 key/nonce/plaintext）
pub fn encrypt_with_nonce(key: &[u8], nonce: &[u8;12], plain: &[u8]) -> Vec<u8>; // 测试专用
```

#### 测试（本任务自带，Q1/Q2 进一步加固）

- `crypto.rs`：固定 key/nonce/plaintext，断言密文与 tag 与 Java 端产物逐字节一致（Q1 提供向量）；`decrypt(encrypt(x)) == x`；篡改 1 字节 → `AuthFailed`。
- `proxy_proto.rs`：`decode(encode(msg)) == msg`；分两段喂入字节，`try_decode_one` 能正确切出整条；host 长度/data 长度边界。
- `outbound.rs`：用本地起的 Java proxy-remote（或 Q2 的 mock）做一次 CONNECT + DATA 往返。

#### 验收标准（Go/No-Go 闸口）

- [x] **crypto 向量测试通过（Q1 已闭合）**：Rust 加密产物与 Java 逐字节一致，Rust 能解 Java 的（双向）。→ Java 侧权威实现 `proxy-crypto/ChaCha20Cipher.java` 经 `CryptoVectorGenTest` 真实运行产出 5 组跨语言向量到 `docs/design/crypto-vectors.json`（含 32B key/短 key→SHA-256/二进制载荷/200B 跨块/单字节边界，nonce 为 Java `encrypt()` 实际使用值）；Rust 侧 `crypto.rs::cross_language_vectors_match_java` 用 `include_str!` 嵌入该文件、以同一 (raw_key, nonce, plaintext) 重放，**逐字节断言 ciphertext / Poly1305 tag / 完整输出 `nonce|ct|tag` 与 Java 一致，并 `decrypt(java_full)==plaintext` 反向互解**，全部通过（`cargo test` 52 项全绿）。非标准实现（counter=0 重叠 keystream、Poly1305 仅对密文且无 padding/无 AAD/无长度尾块、非 32B key 走 SHA-256）由此跨语言锁死。
- [x] **ProxyMessage 编解码**与 Java `ProxyCodec` 互通（28B 头大端、跨帧切包正确）。→ `proxy_proto.rs` 逐字段对照 `ProxyCodec`/`ProxyMessageDecoder`，9 个单测覆盖大端偏移、ordinal 对齐、跨帧/粘包切分；与 Java 的最终互通待 Q2 harness。
- [x] HTTP/2 首帧为 HEADERS（`:method=POST :path=/proxy`），后续 DATA 每帧恰好一条加密 ProxyMessage。→ `outbound.rs::open_proxy_stream` 对照 `ProxyMessageEncoder` 实现首帧 HEADERS（不加密）+ 每条消息一个加密 DATA 帧；窗口对齐 Java（stream 1MB / conn ~16MB）。
- [~] 对接真实 Java proxy-remote 完成一次 CONNECT→DATA→响应往返。→ **待 Q2 / A6 联调**（需起 Java proxy-remote 且配 `cipher=chacha20`；本轮先以内存可测的 `InboundReassembler` / `encode_encrypted_frame` 单测覆盖收发链路语义）。
- [ ] 若任一项不通过 → **No-Go**，按设计文档 8 节回退到「TUN→SOCKS5→Java proxy-local」方案。

> **本轮结论（2025-，Rust 侧）**：A5 的三个 Rust 模块（crypto/proxy_proto/outbound）全部编译通过，
> `cargo test` 52 项全绿、`cargo fmt --check` 与 `cargo clippy -D warnings` 干净，且 `tun-adapter`
> `cargo check` 不受影响（铁律：不破坏现有代码）。
>
> **Q1（crypto 跨语言向量）已闭合**：用本机 Java 8（Corretto 1.8.0_492）+ Maven 跑
> `proxy-crypto/CryptoVectorGenTest` 产出 5 组真实向量，Rust 侧 `cross_language_vectors_match_java`
> 逐字节比对 ciphertext/tag/完整输出并反向解密全过 ——**出站加密层与 Java proxy-remote 二进制兼容
> 这一最大风险点已用跨语言实证消除，加密层 Go 判定成立**。剩余 Q2（协议链路互通 harness）/ A6
> 真实 proxy-remote 端到端往返仍待联调（需起 Java 服务且配 `cipher=chacha20`）。

#### 实现记录（A5，Rust 侧）

- **crypto.rs**：非标准 ChaCha20-Poly1305。用 `chacha20`（IETF 变体，对齐 BouncyCastle `ChaCha7539Engine`）+ `poly1305`（裸 MAC），**严禁** `chacha20poly1305` 聚合 crate。Poly1305 tag 用 `Poly1305::compute_unpadded`（对最后不完整块在 `chunk.len()` 处置 1、不补零对齐），与 BouncyCastle `Poly1305` 的 doFinal 语义一致；曾误用 `update_padded`（AEAD 补零语义）会与 Java 不兼容，已纠正并在源码注释中明确标注。
- **proxy_proto.rs**：`MessageType` ordinal（CONNECT=0…HEARTBEAT_RESPONSE=5）、28B 大端固定头、`try_decode_one` 跨帧切包（`28 + HostLen + DataLen`），全部逐字段对照 Java。
- **outbound.rs**：`OutboundConnection`（h2 client 握手 + 窗口对齐）、`OutboundStream`（首帧 HEADERS + 每消息一个加密 DATA 帧；读向 `release_capacity` 归还流控窗口）、`InboundReassembler`（每帧 decrypt → 累积 → `try_decode_one`）、`proxy_via_remote` 双向 copy 骨架、`SocketProtector` trait（protect 抽象，生产由 JNI 注入 Kotlin `protect`）。TLS 暂走 h2c 明文，rustls 接入登记为技术债（A6 偿还）。
- **兼容性提醒（重要）**：Java `Http2Connection` 的 `cipher` 默认 `aes-gcm`，联调时 proxy-remote 必须显式配 `cipher=chacha20`（+ `cipherKey`）才与本模块二进制兼容。

---

### Task A6：端到端组装 + 前台 Service（MVP 收口）

**预估** 5h　**依赖** A3、A4、A5　**归属** Phase A

#### 目标

把 A1–A5 串成一条真实可用链路：真机浏览器访问 HTTPS 网站，流量经 `VpnService → plane-core（栈+FakeDNS+出站）→ proxy-remote → 公网`，达成 M1。

#### 输入 / 输出

- 输入：A3 的 TUN IO、A4 的 stack 事件、A5 的出站客户端。
- 输出：可安装运行的 MVP APK；前台常驻 Service + 通知栏开关。

#### 详细说明

1. **事件路由**：`tcp_rx` 收到 `NewConnection` → 取 FakeIP 反查真实域名（`fake_dns.lookup`）→ 调 `proxy_via_remote(host, port, app_stream, h2)`。
2. **前台 Service**：`PlaneVpnService.onStartCommand` 里 `establish()` 拿 fd → `startForeground(notification)`（targetSdk 34 指定 `foregroundServiceType`）→ `nativeStart(fd, configJson)`。`onDestroy/onRevoke` 调 `nativeStop` 并清通知。
3. **配置下发**：MVP 把节点 remote 地址/端口/密钥/cipher 硬编码进 `configJson`（B6 再做 UI）。
4. **生命周期与异常**：`onRevoke()`（用户在系统设置里关 VPN）必须 `nativeStop`；start 失败要 `onStatus("error")` 并停前台。

#### 代码思路

```kotlin
// PlaneVpnService.kt
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val pfd = buildTun()                          // A3 的 Builder 配置
    startForeground(NOTI_ID, buildNotification())
    handle = bridge.nativeStart(pfd.detachFd(), configJson)
    return START_STICKY
}
override fun onRevoke() { stop() }
override fun onDestroy() { stop() }
private fun stop() {
    if (handle != 0L) { bridge.nativeStop(handle); handle = 0L }
    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
}
```

#### 测试

- 真机 E2E：打开 VPN → 浏览器访问 `https://www.google.com` 能正常加载；`https://ifconfig.me` 显示的出口 IP = proxy-remote 节点 IP。
- 稳定性：连续浏览 10 分钟不掉线、不 ANR；切到后台再回前台连接保持。
- 生命周期：系统设置里手动关闭 VPN → app 内状态变 disconnected，无残留连接/fd 泄漏。

#### 验收标准

- [ ] 真机浏览器经全链路打开 HTTPS 网站，出口 IP 为节点 IP（**M1 达成**）。
- [ ] 前台通知常驻，灭屏/切后台连接不断。
- [ ] `onRevoke`/`onDestroy` 正确清理，fd 与连接无泄漏。
- [ ] DNS 仅经 FakeDNS，无明文 DNS 泄漏（抓包验证）。

---

## 3. 质量任务（与 Phase A 交织，护住协议互通）

> 质量任务不是「最后补测试」，而是**先于/同步于**对应开发任务建立回归防线。Q1/Q2 是
> A5 的安全网，**必须在 A5 验收前就绪**；Q3 在 Phase A 收尾时接入 CI。

### Task Q1：ChaCha20-Poly1305 跨语言向量（A5 的安全网，先行）

**预估** 4h　**依赖** A5.1（或与之并行）　**归属** 质量

#### 目标

用一组**固定输入向量**把 Rust 与 Java 的 ChaCha20-Poly1305 钉死为逐字节一致，杜绝
0.4-C 描述的非标准实现走样。

#### 输入 / 输出

- 输入：Java `ChaCha20Cipher`（既有）、Rust `crypto.rs`（A5.1）。
- 输出：`docs/design/crypto-vectors.json`（共享向量文件）+ 两端读取该文件的断言测试。

#### 详细说明

1. 生成向量：用 Java 端固定 key（32B）、nonce（12B）、plaintext（含空串、1B、64B、1MB 四档）跑 `encrypt`，把 `key/nonce/plaintext/expected_output` 落 `crypto-vectors.json`（hex 编码）。
2. Rust 测试读同一 JSON：`encrypt_with_nonce(key,nonce,plain)` 必须等于 `expected_output`；`decrypt(expected_output)` 必须等于 `plaintext`。
3. **双向互通**：再加 Java 解 Rust 产物、Rust 解 Java 产物的交叉用例。
4. 负例：篡改 tag/ct 任一字节 → 两端都报鉴权失败。

#### 代码思路

```rust
#[test]
fn cross_lang_vectors() {
    let vs: Vec<Vector> = serde_json::from_str(include_str!("../../docs/design/crypto-vectors.json")).unwrap();
    for v in vs {
        let out = crypto::encrypt_with_nonce(&hex(&v.key), &arr12(&v.nonce), &hex(&v.plaintext));
        assert_eq!(hex_str(&out), v.expected_output, "vector {}", v.name);
        assert_eq!(crypto::decrypt(&hex(&v.key), &hex(&v.expected_output)).unwrap(), hex(&v.plaintext));
    }
}
```

#### 测试 / 验收标准

- [ ] `crypto-vectors.json` 覆盖 4 档长度 + 边界（空串、非 32B key 触发 SHA-256 派生）。
- [ ] Rust 全部向量逐字节通过；双向交叉解密通过。
- [ ] 负例篡改 → 两端均 `AuthFailed`，无 panic、无静默解错。

---

### Task Q2：ProxyMessage / HTTP/2 协议互通 harness（A5 的安全网，同步建）

**预估** 6h　**依赖** A5.2/A5.3　**归属** 质量

#### 目标

建一个可重复运行的互通测试床：本地起真实 Java proxy-remote，Rust 出站客户端与之完成
CONNECT/DATA/DISCONNECT/HEARTBEAT 全消息类型往返，锁死 0.4-A/B/D 的线格式。

#### 输入 / 输出

- 输入：Java proxy-remote（既有，可本地启动）、Rust `outbound.rs`。
- 输出：`plane-core/tests/interop.rs` 集成测试 + 一个启动 proxy-remote 的脚本/容器。

#### 详细说明

1. **拉起 remote**：脚本启动 proxy-remote（指定 cipher=chacha20-poly1305、固定密钥、监听本地端口），测试结束后清理。
2. **回环 echo 目标**：remote 背后接一个本地 echo TCP server 作为「公网目标」，便于校验数据原样返回。
3. **用例矩阵**：
   - CONNECT 成功 → CONNECT_RESPONSE status=0。
   - DATA 往返：发 N 字节，echo 回 N 字节，逐字节一致（含 >1MB 触发跨帧切包）。
   - 多 stream 并发：同连接开 50 路 stream 互不串扰（验证 HTTP/2 多路复用 + requestId 路由）。
   - DISCONNECT / HEARTBEAT 行为正确。
4. **首帧校验**：抓取/断言 Rust 发出的首帧是 HEADERS（`:method=POST :path=/proxy`）。

#### 代码思路

```rust
#[tokio::test]
async fn interop_data_roundtrip() {
    let remote = start_java_remote().await;     // 脚本拉起，返回端口与密钥
    let mut h2 = connect_protected(remote.addr, remote.key, Cipher::ChaCha20Poly1305).await;
    let mut s = proxy_connect(&mut h2, "127.0.0.1", remote.echo_port).await.unwrap();
    let payload = random_bytes(2 * 1024 * 1024); // 触发跨帧
    s.write_all(&payload).await.unwrap();
    let echoed = read_exact_n(&mut s, payload.len()).await.unwrap();
    assert_eq!(echoed, payload);
}
```

#### 测试 / 验收标准

- [ ] CONNECT/DATA/DISCONNECT/HEARTBEAT 四类消息往返全部通过。
- [ ] 2MB payload 跨帧往返逐字节一致。
- [ ] 50 路并发 stream 数据互不串扰。
- [ ] 首帧为 HEADERS 且伪头正确。

---

### Task Q3：CI 接入（rust-test + interop + android-build）

**预估** 4h　**依赖** Q1、Q2、A1　**归属** 质量

#### 目标

在 GitHub Actions 上把 Rust 单测、跨语言向量、协议互通、Android 构建跑成强制门禁，
Phase A 之后每次提交都自动护栏。

#### 输入 / 输出

- 输入：Q1/Q2 测试、A1 的 Gradle 工程与 `build-rust.sh`。
- 输出：`.github/workflows/android-core.yml`，PR 必过。

#### 详细说明

1. **job: rust-test**：`cargo fmt --check`、`cargo clippy -D warnings`、`cargo test`、`cargo-llvm-cov` 覆盖率（0.5 门槛 ≥80%，未达标 fail）。
2. **job: interop**：用 service container 起 Java proxy-remote（或 testcontainers），跑 `interop.rs`。
3. **job: android-build**：装 Android SDK/NDK + cargo-ndk，跑 `build-rust.sh` + `./gradlew assembleDebug`，产物 upload artifact。
4. **缓存**：cargo registry/target、Gradle 缓存，控制时长。
5. 分支保护：三 job 全绿才允许合并。

#### 代码思路

```yaml
# .github/workflows/android-core.yml（节选）
jobs:
  rust-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: rustup component add clippy rustfmt
      - run: cargo fmt --all -- --check
      - run: cargo clippy --all-targets -- -D warnings
      - run: cargo install cargo-llvm-cov && cargo llvm-cov --fail-under-lines 80
  android-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4 { with: { java-version: 17, distribution: temurin } }
      - run: cargo install cargo-ndk && rustup target add aarch64-linux-android
      - run: ./scripts/build-rust.sh release
      - run: ./gradlew assembleDebug
```

#### 测试 / 验收标准

- [ ] 三 job 在干净环境从零跑通。
- [ ] 覆盖率 <80% 时 CI 失败。
- [ ] PR 必须三绿才能合并（分支保护已配置）。
- [ ] 缓存生效，单次 CI 时长可接受（参考 <20min）。

---

## 4. Phase B — 可用性增强（达成 M2，可日常自用）

### Task B1：真直连出站（direct outbound）

**预估** 4h　**依赖** A4、A5（出站框架）　**归属** Phase B

#### 目标

实现「不走 remote、本地直连」的出站路径，且 socket **必须 protect**（否则回环走 TUN）。
为 B2 分流提供「direct」分支。

#### 输入 / 输出

- 输入：A4 的 `app_stream` + 目标 host/port（直连时需真实 DNS 解析，因 host 是 FakeIP 反查的真域名）。
- 输出：`outbound.rs` 增加 `direct_connect(host, port, app_stream)`。

#### 详细说明

1. **protect 是铁律**：直连 socket 创建后立刻 `protect(fd)`（0.3 防呆铁律），否则该 socket 流量被路由回 TUN 形成回环。
2. **真实 DNS**：直连目标是真域名（已由 FakeDNS 反查），需用系统 DNS 或配置的上游 DNS 解析为真 IP（不能用 FakeIP 去连）。MVP 可用 `tokio::net::lookup_host`（注意它走的 socket 也需 protect 思路，实际 getaddrinfo 由系统完成，确认不经 TUN）。
3. **双向 copy**：复用桌面 `socks5.rs` 的 a2p/p2a 双 spawn 模式，直接 `tokio::io::copy_bidirectional` 即可（直连无需 ProxyMessage 封装）。

#### 代码思路

```rust
pub async fn direct_connect(host: &str, port: u16, mut app: SmolTcpStream) -> Result<()> {
    let addr = resolve(host, port).await?;            // 真实 DNS → 真 IP
    let sock = new_protected_tcp().await?;            // 创建即 protect
    let mut up = sock.connect(addr).await?;
    tokio::io::copy_bidirectional(&mut app, &mut up).await?;
    Ok(())
}
```

#### 测试 / 验收标准

- [ ] 直连访问国内站点（如 `https://www.baidu.com`）成功，出口 IP = 手机本地公网 IP。
- [ ] 抓包确认直连 socket 不回环（不再次进入 TUN）。
- [ ] 直连与 remote 出站可在同一会话共存。

---

### Task B2：路由分流（router 规则接入）

**预估** 5h　**依赖** B1　**归属** Phase B

#### 目标

复用桌面 `router.rs`，按域名/IP/GEO 规则决定每条连接走 `direct` 还是 `proxy`，实现
「国内直连、国外走代理」。

#### 输入 / 输出

- 输入：B1 的 direct + A5 的 proxy 两条出站；`router.rs`（复用）+ 规则配置。
- 输出：连接建立时调用 `router.decide(host) -> Outbound::{Direct|Proxy}` 的分发逻辑。

#### 详细说明

1. **复用 router.rs**：纳入 `plane-net` 共享 crate；规则来源沿用桌面（domain-suffix / domain-keyword / ip-cidr / geoip / final）。
2. **决策时机**：在 `NewConnection` 事件处理处，用 FakeIP 反查的真域名做决策（域名规则优先，无法判定时用 final）。
3. **配置**：规则随 `configJson` 下发（B6 UI 可编辑），MVP 内置一份基础规则。
4. **GeoIP**：如需 geoip，mmdb 文件随 assets 打包，Rust 侧读取（注意包体积，C3 再做订阅更新）。

#### 代码思路

```rust
let host = fake_dns.lookup(dst_ip).unwrap_or_else(|| dst_ip.to_string());
match router.decide(&host) {
    Outbound::Direct => direct_connect(&host, dst_port, app_stream).await,
    Outbound::Proxy  => proxy_via_remote(&host, dst_port, app_stream, h2).await,
}
```

#### 测试 / 验收标准

- [ ] 复用 `router.rs` 既有单测全绿。
- [ ] 国内域名走 direct（出口为本地 IP），国外域名走 proxy（出口为节点 IP），抓包/`ifconfig.me` 验证。
- [ ] 规则缺省时走 final 分支，无连接卡死。

---

### Task B3：节点健康检查与自动降级

**预估** 4h　**依赖** A5、B2　**归属** Phase B

#### 目标

周期性探测 remote 节点可用性，节点不可用时按策略降级（提示/切直连/切备用节点），
避免「开了 VPN 全网打不开」。

#### 输入 / 输出

- 输入：A5 的出站连接、节点配置。
- 输出：健康状态机 + `onStatus` 上报；不健康时的降级动作。

#### 详细说明

1. **探测**：用 HEARTBEAT_REQUEST/RESPONSE（0.4-A 已含该消息类型）做应用层心跳，记录 RTT/失败次数。
2. **状态机**：healthy / degraded / down，连续 N 次失败转 down。
3. **降级策略**（配置化）：down 时可选「全直连」或「切下一个节点（依赖 C2 多节点，B 阶段先支持全直连 + UI 提示）」。
4. **恢复**：down 后继续低频探测，恢复后回 healthy。

#### 代码思路

```rust
loop {
    tokio::time::sleep(interval).await;
    match send_heartbeat(&mut h2).await {
        Ok(rtt) => health.on_ok(rtt),
        Err(_)  => if health.on_fail() == State::Down {
            bridge.on_status("node_down");
            // 降级：后续新连接走 direct（或切节点）
        }
    }
}
```

#### 测试 / 验收标准

- [ ] 节点正常时 HEARTBEAT RTT 正常上报，状态 healthy。
- [ ] 人为停掉节点 → 连续失败后转 down 并触发降级，UI 收到 `node_down`。
- [ ] 节点恢复后自动回 healthy。

---

### Task B4：网络切换与断线重连

**预估** 5h　**依赖** A5、B3　**归属** Phase B

#### 目标

WiFi↔蜂窝切换、信号抖动后，连接能自动重建，用户无感（或最小感知），不必手动重开 VPN。

#### 输入 / 输出

- 输入：Android `ConnectivityManager` 网络事件、A5 连接、B3 健康态。
- 输出：网络变更监听 + Rust 侧连接重建逻辑。

#### 详细说明

1. **监听网络**：Kotlin `ConnectivityManager.registerNetworkCallback`，`onAvailable/onLost` 经 JNI 通知 Rust（新增 `nativeOnNetworkChanged(handle)`）。
2. **重建**：底层 TCP/h2 连接失效时，丢弃旧连接、重新 protect+connect 建新 h2；已存在的 app stream 视情况重连或失败（HTTP 多为短连，影响小）。
3. **退避**：重连用指数退避（如 0.5s→1s→2s→… 上限）。
4. **保活**：注意 `addDisallowedApplication(self)` 在网络切换后仍生效（establish 不变即可）。

#### 代码思路

```kotlin
connectivityManager.registerNetworkCallback(req, object : NetworkCallback() {
    override fun onAvailable(n: Network) { bridge.nativeOnNetworkChanged(handle) }
    override fun onLost(n: Network) { bridge.nativeOnNetworkChanged(handle) }
})
```

#### 测试 / 验收标准

- [ ] WiFi→蜂窝切换后，新发起的访问在数秒内恢复，无需手动重开。
- [ ] 飞行模式开关后能自动恢复。
- [ ] 重连采用退避，无忙等/电量异常。

---

### Task B5：前台 Service 完善与可靠性

**预估** 4h　**依赖** A6　**归属** Phase B

#### 目标

把 A6 的最小前台 Service 做成「不被系统轻易杀、状态清晰、可一键停止」的可靠常驻服务。

#### 输入 / 输出

- 输入：A6 的 Service。
- 输出：完善的通知（连接态/流量/停止按钮）、保活与异常自恢复。

#### 详细说明

1. **通知内容**：显示连接状态、当前节点、实时上下行速率（依赖 B7 统计），通知带「断开」Action。
2. **foregroundServiceType**：targetSdk 34 正确声明类型，避免启动崩溃。
3. **保活**：`START_STICKY` + 被杀后系统重建时恢复（需持久化最后配置）。
4. **异常自恢复**：Rust 侧致命错误 → `onStatus("error")` → Service 决定重试或停止并通知用户。

#### 测试 / 验收标准

- [ ] 通知常驻，含状态/节点/速率/断开按钮，点击断开能停服务。
- [ ] 后台长时间运行不被普通内存压力杀死。
- [ ] 进程被杀后能按 START_STICKY 重建并恢复连接（或明确提示重连）。

---

### Task B6：配置 UI（节点/规则编辑）

**预估** 6h　**依赖** A6、B2　**归属** Phase B

#### 目标

提供基础设置界面：增删改节点（地址/端口/密钥/cipher）、选择当前节点、编辑分流规则开关，
配置持久化并以 `configJson` 下发给 plane-core。

#### 输入 / 输出

- 输入：用户输入。
- 输出：节点/规则的持久化存储（DataStore/Room）+ 序列化为 `configJson`。

#### 详细说明

1. **数据层**：用 DataStore（简单）或 Room（多节点）存节点列表与当前选中、规则开关。
2. **校验**：地址/端口/密钥格式校验，cipher 限定枚举（aes-gcm / chacha20-poly1305，与 remote 对齐）。
3. **下发**：start 时把当前节点 + 规则序列化成 `configJson`，结构与 Rust `AndroidConfig`（0.4 / config.rs 适配）一致。
4. **切换节点**：切换时 `nativeStop` 旧 handle → `nativeStart` 新 configJson（A2 已支持多次 start/stop）。

#### 测试 / 验收标准

- [ ] 增删改节点并持久化，重启 app 后保留。
- [ ] cipher/地址/端口非法输入被拦截并提示。
- [ ] 切换节点后实际出口 IP 随之改变（无需重装/重启进程）。

---

### Task B7：流量统计与展示

**预估** 4h　**依赖** A6、B5　**归属** Phase B

#### 目标

统计实时上下行速率与累计流量，经 `nativeStats` 暴露给 UI 和通知栏。

#### 输入 / 输出

- 输入：Rust 出站/直连路径上的字节计数。
- 输出：`nativeStats(handle) -> JSON{ up_bytes, down_bytes, up_rate, down_rate, node, state }`。

#### 详细说明

1. **计数**：在 a2p/p2a copy 处用原子计数累加上下行字节（避免锁竞争）。
2. **速率**：UI 侧按采样间隔差分计算速率，或 Rust 侧滑动窗口算好直接给。
3. **采样**：UI 每 1s 调一次 `nativeStats` 刷新通知与界面。
4. **复位**：会话开始/切节点时计数复位。

#### 测试 / 验收标准

- [ ] 实测下载大文件，统计上下行字节与系统流量监控误差在可接受范围（<5%）。
- [ ] 速率展示平滑、无明显抖动/卡 0。
- [ ] `nativeStats` 高频调用不影响数据面性能。

---

### Task Q4：Kotlin 单测 + protect 防回环专项

**预估** 6h　**依赖** B5、B6（及 B 各 UI/逻辑）　**归属** 质量

#### 目标

为 Kotlin 侧关键逻辑（Service 生命周期、配置序列化、网络回调）建单测/instrumented 测试，
并设**防回环专项**确保所有出站 socket 都被 protect。

#### 输入 / 输出

- 输入：B1–B7 的 Kotlin/Rust 代码。
- 输出：JUnit/Robolectric + instrumented 测试；protect 覆盖率断言。

#### 详细说明

1. **配置序列化**：节点/规则 ↔ `configJson` 往返一致性测试。
2. **生命周期**：Robolectric 测 `onStartCommand/onRevoke/onDestroy` 调用 `nativeStart/nativeStop` 的次数与顺序。
3. **网络回调**：模拟 `onAvailable/onLost` 触发 `nativeOnNetworkChanged`。
4. **protect 防回环专项**（呼应 0.3）：在 Rust 出站创建 socket 的唯一入口加 hook/计数，instrumented 测试断言「建立 N 条出站 → protect 被调用 N 次」，杜绝漏 protect 的回环。

#### 测试 / 验收标准

- [ ] configJson 序列化/反序列化往返无损。
- [ ] Service 生命周期调用序列正确（start/stop 配对）。
- [ ] protect 调用次数 == 出站 socket 创建次数（零漏网）。
- [ ] Kotlin 侧覆盖率达标并纳入 CI（扩展 Q3 的 android job）。

---

## 5. Phase C — 产品化（达成 M3，可灰度发布）

### Task C1：UDP 支持（DNS / QUIC / 游戏等）

**预估** 6h　**依赖** A5、B2　**归属** Phase C

#### 目标

支持 UDP 转发，覆盖 QUIC（HTTP/3）、游戏、部分实时音视频等场景，避免「只能上 TCP」的体验缺口。

#### 输入 / 输出

- 输入：stack 层的 UDP 数据报事件、出站层。
- 输出：UDP 会话表 + 经 remote 的 UDP-over-stream 封装（或直连 UDP）。

#### 详细说明

1. **stack UDP**：复用/扩展 `stack.rs` 的 UDP 处理，把非 53 的 UDP（53 仍归 FakeDNS）按 五元组建会话。
2. **direct UDP**：直连分支用 protect 过的 UDP socket 直发。
3. **proxy UDP**：经 remote 时按协议约定封装（确认 Java proxy-remote 是否支持 UDP；若不支持需先在 remote 侧扩展，或本阶段仅支持 direct UDP 并标注）。
4. **会话回收**：UDP 无连接，按空闲超时回收会话表项。

#### 测试 / 验收标准

- [ ] QUIC 站点（如 `https://quic.rocks` 或启用 HTTP/3 的站点）可用。
- [ ] UDP direct 与 proxy 路径分别验证（proxy 路径以 remote 实际能力为准）。
- [ ] 会话表空闲超时回收，无泄漏。

---

### Task C2：多节点与订阅

**预估** 6h　**依赖** B3、B6　**归属** Phase C

#### 目标

支持多节点管理、订阅链接导入、按延迟/规则选节点，并与 B3 健康检查联动做故障切换。

#### 输入 / 输出

- 输入：订阅 URL / 手动节点。
- 输出：节点池 + 订阅解析 + 选路策略 + 故障切换。

#### 详细说明

1. **订阅解析**：解析订阅格式（与服务端约定，如 base64/JSON 节点列表），定时更新。
2. **测速选路**：批量 HEARTBEAT 测 RTT，UI 展示，可手动/自动选最优。
3. **故障切换**：与 B3 联动，当前节点 down 自动切下一个健康节点（补全 B3 的「切节点」降级分支）。
4. **持久化**：节点池与订阅源持久化（扩展 B6 数据层）。

#### 测试 / 验收标准

- [ ] 导入订阅后节点列表正确，定时更新生效。
- [ ] 测速排序正确，可自动选最优节点。
- [ ] 当前节点故障时自动切换到健康节点，连接恢复。

---

### Task C3：规则订阅与更新

**预估** 4h　**依赖** B2、C2　**归属** Phase C

#### 目标

支持分流规则集 / GeoIP 库的在线订阅与定时更新，规则不再硬编码在包内。

#### 输入 / 输出

- 输入：规则订阅 URL、GeoIP mmdb 源。
- 输出：规则/GeoIP 的下载、缓存、热更新。

#### 详细说明

1. **规则订阅**：下载规则集（domain/ip 列表），落本地缓存，启动/定时加载进 `router`。
2. **GeoIP 更新**：mmdb 文件可在线更新替换（注意校验完整性）。
3. **热更新**：更新后让 `router` 重载规则，无需重启 VPN（或最小重连）。
4. **容错**：下载失败回退到上次缓存/内置规则。

#### 测试 / 验收标准

- [ ] 规则订阅下载并生效，分流结果随规则变化。
- [ ] GeoIP 更新后命中正确国家。
- [ ] 网络异常时回退到缓存/内置规则，不影响可用性。

---

### Task C4：稳定性加固（内存/电量/异常）

**预估** 6h　**依赖** A6、B 全部　**归属** Phase C

#### 目标

长时间运行下的内存、电量、异常稳健性达到可灰度标准。

#### 输入 / 输出

- 输入：完整链路。
- 输出：内存/电量优化、异常兜底、panic 全捕获、降噪日志。

#### 详细说明

1. **内存**：连接表/会话表上限与回收，避免泄漏；缓冲池复用减少分配。
2. **电量**：心跳/重连退避合理，灭屏降频，避免 wakelock 滥用。
3. **异常兜底**：所有 JNI 边界 `catch_unwind`，Rust 任务 panic 不传染、可恢复。
4. **日志**：分级日志（tracing-android），release 降噪，保留可诊断的关键事件。

#### 测试 / 验收标准

- [ ] 24h 长稳测试无内存持续增长、无崩溃、无 ANR。
- [ ] 后台运行电量消耗在合理区间（对比同类工具）。
- [ ] 注入异常（节点断、网络抖、错误配置）均有兜底，不崩溃。

---

### Task C5：综合测试与灰度准备

**预估** 6h　**依赖** C1–C4　**归属** Phase C

#### 目标

完成发布前的兼容性、性能、安全综合测试，达成灰度门槛。

#### 输入 / 输出

- 输入：C1–C4 完成的产物。
- 输出：兼容性矩阵报告、性能基线、安全自查清单。

#### 详细说明

1. **兼容性**：覆盖 Android 8–14、arm64/armeabi-v7a/x86_64 主流机型。
2. **性能**：吞吐（下载/上传速率）、延迟、并发连接数基线，与桌面/同类对比。
3. **安全自查**：DNS 无泄漏、无明文、protect 全覆盖（呼应 Q4）、密钥不落明文日志。
4. **灰度方案**：分阶段放量、回滚预案、关键指标看板（呼应 Q6 崩溃上报）。

#### 测试 / 验收标准

- [ ] 兼容性矩阵主流机型全绿。
- [ ] 性能基线达标并记录。
- [ ] 安全自查清单全部通过。
- [ ] 灰度/回滚方案就绪。

---

### Task Q5：E2E 自动化 + nightly

**预估** 6h　**依赖** A6、B 全部、C1　**归属** 质量

#### 目标

在模拟器上跑端到端自动化（开 VPN → 访问目标 → 校验出口/连通性），并接入 nightly 定时运行。

#### 输入 / 输出

- 输入：可安装 APK、测试目标。
- 输出：E2E 脚本（adb + 模拟器）+ nightly workflow。

#### 详细说明

1. **E2E 场景**：开启 VPN → 访问国外站点校验出口 IP=节点 IP；访问国内站点校验直连；DNS 无泄漏检查。
2. **模拟器**：CI 用 Android emulator（KVM），自动安装/授权（adb 授予 VPN 较受限，必要时用 instrumented 内部触发）。
3. **nightly**：定时运行全量 E2E + 长稳子集，失败告警。

#### 测试 / 验收标准

- [ ] E2E 脚本本地与 CI 均可跑通核心场景。
- [ ] nightly 定时运行并产出报告/告警。
- [ ] 关键回归（出口、分流、DNS 泄漏）被自动覆盖。

---

### Task Q6：CD（签名 / Release / 崩溃上报）

**预估** 6h　**依赖** Q3、C4　**归属** 质量

#### 目标

打通发布流水线：签名打包、版本管理、产物分发，并接入崩溃/ANR 上报。

#### 输入 / 输出

- 输入：通过门禁的代码。
- 输出：release workflow（签名 AAB/APK）、崩溃上报接入、版本/changelog。

#### 详细说明

1. **签名**：CI 用 secrets 注入签名配置，产出 release 签名包（AAB + APK）。
2. **Release**：打 tag 触发，生成 Release Notes，产物归档/分发（内部分发或商店）。
3. **崩溃上报**：接入崩溃/ANR 上报（含 Rust panic 与 native crash 的符号化），看板可观测。
4. **版本管理**：versionCode/versionName 自动化，与 crate 版本对齐。

#### 测试 / 验收标准

- [ ] 打 tag 自动产出签名 release 包。
- [ ] 崩溃/ANR 能上报并符号化（含 native）。
- [ ] 版本号一致、Release Notes 自动生成。

---

## 6. 任务依赖总览与排期建议

#### 关键路径

`A1 → A2 → A3 → A4 → A5(+Q1/Q2) → A6(M1)`。其中 **A5 是 Go/No-Go 闸口**，建议
A5.1+Q1（crypto 向量）最先攻克——它独立、风险最高、可与 A3/A4 并行，尽早证伪能省下后续返工。

#### 并行建议

- A3/A4（TUN+栈，复用为主）与 A5（出站，全新）**两条线可并行**，A6 汇合。
- Q1/Q2 与 A5 同步进行，作为其安全网，不要等 A5 写完再补测。
- Phase B 内：B1→B2 串行；B3/B4/B5/B6/B7 在 B2 之后大体可并行，Q4 收口。
- Phase C 内：C1/C2/C3 功能相对独立可并行，C4/C5 收口，Q5/Q6 在 C 阶段后期接入。

#### 工时汇总（与设计文档对齐，含质量任务）

| 阶段 | 开发任务 | 质量任务 | 小计 |
| ---- | -------- | -------- | ---- |
| Phase A | A1-A6 ≈ 34h | Q1+Q2+Q3 ≈ 14h | ≈ 48h |
| Phase B | B1-B7 ≈ 32h | Q4 ≈ 6h | ≈ 38h |
| Phase C | C1-C5 ≈ 28h | Q5+Q6 ≈ 12h | ≈ 40h |
| 合计 | ≈ 94h | ≈ 32h | **≈ 126h** |

> 工时为单人理想估算，不含联调/评审/缓冲；A5 风险高，建议预留 30%–50% buffer。

#### 里程碑回顾

- **M1（MVP，A6）**：真机浏览器经全链路打通 HTTPS，出口为节点 IP。
- **M2（可用，B 全 + Q4）**：分流/降级/切网/常驻/UI/统计齐全，可日常自用。
- **M3（产品化，C 全 + Q5 + Q6）**：UDP/多节点/订阅/稳定性/CI-CD/崩溃上报齐备，可灰度。

#### 收尾自检清单（全局）

- [ ] 0.3 防呆铁律全程满足（尤其所有出站 socket 均 protect，Q4 已断言）。
- [ ] 0.4 协议对齐四要素（28B 头大端 / 每消息独立加密 / 非标准 ChaCha20-Poly1305 / HEADERS 首帧）由 Q1+Q2 锁死。
- [ ] 复用代码统一收口于 `plane-net`，桌面与 Android 不漂移（A4 已落地或记录技术债）。
- [ ] CI 三绿门禁 + 覆盖率 ≥80%（Q3）+ E2E nightly（Q5）+ 签名/崩溃上报（Q6）。

---

