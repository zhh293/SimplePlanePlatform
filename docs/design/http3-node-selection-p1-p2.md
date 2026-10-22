# HTTP/3 多节点手动切换：实现与验证

后续提案：[P3/P4：自动切换设计与实施验收](http3-auto-failover-p3-p4.md)。两阶段尚未实现，不改变本文记录的手动模式行为。

本文对应 [改造方案](node-selection-and-failover-plan.md) 的 P1、P2。范围是 Java local 的 HTTP/3 代理链路、Web Dashboard 与桌面端；HTTP/2 继续沿用原有选择逻辑。自动故障切换、节点熔断与恢复探测属于 P3。

HTTP/3 每个节点固定一条 QUIC 连接，配置要求 `connectionsPerNode: 1`。仅连接当前所选节点，不预建备用节点连接；节点内部不再进行连接池管理或负载均衡。

## 使用

Dashboard 或桌面端可以添加多个 HTTP/3 节点，在节点卡片上勾选“使用此节点”，然后保存。切换会关闭原节点的代理流及客户端连接，应用需要刷新或重连；直连流保持不变。

Java 运行时配置示例（证书路径和密钥必须替换为自己的配置）：

```yaml
localPort: 1080
connectionsPerNode: 1
timeoutMs: 8000
systemProxy:
  enabled: false
remoteServers:
  - id: node-a
    name: 主节点
    host: node-a.example.com
    port: 9090
    transport: http3
    cipher: aes-gcm-v2
    cipherKey: "0123456789abcdef0123456789abcdef"
    http3:
      serverName: node-a.example.com
      caFile: /path/to/trusted-ca.pem
  - id: node-b
    name: 备用节点
    host: node-b.example.com
    port: 9090
    transport: http3
    cipher: aes-gcm-v2
    cipherKey: "0123456789abcdef0123456789abcdef"
    http3:
      serverName: node-b.example.com
      caFile: /path/to/trusted-ca.pem
nodeSelection:
  mode: manual
  selectedNodeId: node-a
```

密钥示例仅用于说明字段格式，不应作为实际部署密钥。加密套件及密钥须与对应 remote 保持一致，本次没有更改帧格式或加解密协议。

命令行必须显式提供外部配置路径：

```sh
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar /path/to/proxy.yml
```

Dashboard、桌面端和仓库启动脚本会传入配置路径。无参数启动仍可读取 classpath 默认资源，但该资源不能热更新。旧的单节点 HTTP/3 配置在加载时迁移为手动模式，缺省逻辑 ID 为 `legacy-node`；迁移不会主动重写原文件。已有多节点配置需显式提供唯一 ID 和手动选择配置。

桌面端保存 `nodes` 和 `selected_node_id`，同时保留所选节点的旧 `remote` 镜像供既有代码读取；生成 `proxy-java.yml` 时输出全部节点及选择信息。导入 YAML 时保留已有节点 ID、名称和 HTTP/3 参数。

## 运行规则

- CONNECT 选择具体 Invoker；DATA、DISCONNECT 仅使用原绑定，不重新负载均衡，也不重放 DATA。
- 绑定保存流对应的 Invoker、断开请求所需的原目标地址及建流是否完成。连接断开时清理所属请求和流；重连后由应用重新建流，不接续旧流。
- 手动模式不会使用备用节点兜底。所选节点暂时不可达仍可应用选择，面板显示等待建连或节点不可用，实际建流失败会返回错误。
- 改变所选节点、当前节点连接参数或建流超时会释放旧资源；仅更改备用节点、名称或不参与 HTTP/3 选择的负载均衡字段，不关闭当前健康流。
- 启动和配置应用线程负责建立所选节点的一条连接。浏览器请求直接进入 ClusterInvoker，不经过额外的 CONNECT 队列、信号量或预算透传。
- HTTP CONNECT 和 SOCKS5 处理恢复到改造前 `ef91d0d`，沿用同步回调；没有 DownstreamGate、额外下行缓存、暂停读取或 SOCKS5 解码器重写。
- 本次没有加入自动重试策略、熔断或主动探测；单次建流失败向应用报错，由应用重连。

## 配置应用与状态

配置以临时文件原子替换；Java 每 500 ms 比较内容摘要，合并重复变更，并且校验后才提交。非法节点 ID、密钥、证书、传输参数或不受支持的模式不会替换现有运行时配置。

状态位于 `<外部配置路径>.status.json`，包含进程实例 ID、心跳、保存/应用摘要、当前节点、连接状态、活跃绑定数和应用错误，不包含密钥。界面区别“已保存”和“已应用”；状态超过 10 秒未更新视为过期。启动新进程前清除旧状态文件，正常停机发布停止状态。

本地监听端口、入口协议开关、系统代理和路由规则变更需要重启代理，不能通过节点热切换生效。节点参数仍可热更新。TUN 启动时生成包含全部候选节点地址的直连豁免配置；TUN 运行时改变节点地址列表须先停止 TUN，重新配置路由后启动。手动启动 TUN 时同样需要保证所有候选节点 IP 已豁免。

## 验证入口

确定性 Java 回归（包含依赖模块和本地真实网络测试，排除依赖外部百度站点的测试）：

```sh
mvn -B -pl proxy-local -am test '-Dtest=*Test,!BaiduProxyTest' -Dsurefire.failIfNoSpecifiedTests=false
node --test dashboard/test/*.test.js
python scripts/test-node-selection-ui.py
```

`test-node-selection-ui.py` 使用本机 Chrome/Chromium（可通过 `CHROME_BIN` 指定）；运行真实页面 JavaScript，模拟 Tauri IPC/HTTP API，检查双节点恢复、选择与保存、TLS/路由参数保留、删除当前节点后的选择回退，并输出页面截图至 `.build/node-selection-ui/`。它不替代 Tauri 原生编译。

关键验收测试：

| 测试 | 验证范围 |
|---|---|
| `StickyClusterInvokerTest` | 固定连接、DATA 不改投、重复/失败 CONNECT 清理、迟到成功不复活 |
| `ConnectionOwnershipTest` | 整连接关闭时只清理所属流；原同步 CONNECT 回调先提交握手响应，再处理后续 DATA |
| `NodeSelectionConfigTest` | 旧配置迁移、无效配置拒绝、备用节点编辑与手动选择应用 |
| `Http3ExchangeIntegrationTest` | 两个独立 QUIC 客户端共享注册表；关闭 A 后 B 正常；三种加密套件隔离 |
| `Http3ManualSwitchIntegrationTest` | 浏览器 TCP → local → 两个真实 HTTP/3 remote → 目标 TCP；A 切 B、备用节点编辑不打断当前流、非法配置保留旧流、128 KiB 分片字节比对、普通 HTTP 首包、远端 session/Future 回收 |
| 同一全链路测试的独立进程用例 | 从 Java main 启动 local，验证状态文件、配置热加载、正常 HTTP CONNECT/SOCKS5 握手与业务数据 |
| Dashboard `node-selection.test.js` | 节点校验、YAML 保留、原子替换、状态过期与候选节点 TUN 豁免 |
| Rust `config::node_tests` | 桌面端旧配置迁移、多节点 YAML/密钥转义、错误选择拒绝、导入 ID 与证书参数保留 |

桌面应用的原生编译与 Rust 配置测试交给 GitHub 的桌面构建流程，本地页面冒烟不代表原生应用已编译成功。验证使用独立回环节点、临时证书和临时配置，测试配置关闭系统代理。

## 最小实现与验证

基线为手动切换之前的 `ef91d0d`。HTTP CONNECT、SOCKS5 和 HTTP/3 Client 的转发与重连逻辑保持该版本实现。

- `ClientInvoker` 仅增加稳定 nodeId 和委托 ExchangeClient 的 close 方法；请求超时仍使用旧配置值。
- `StickyClusterInvoker` 保留 ClusterInvoker 接口及 SPI，CONNECT 选择并绑定，DATA/DISCONNECT 使用原 Invoker。
- `Http3NodeController` 只管理 `nodeId → ClientInvoker`。配置更新时停用旧选择、发送原流的 DISCONNECT、有界等待确认，再关闭其 ExchangeClient 并创建新节点连接。连接建立失败报告 unavailable，不自动回退节点；已建立连接的重连沿用原 Http3Client。
- ExchangeHandler 在旧逻辑上仅补充流归属记录及整连接关闭时的清理通知。HeaderExchangeClient.close、pending Future 和浏览器取消 CONNECT 均复用旧逻辑。
- 删除 DownstreamGate、defer/activate 接口、预算附件、CONNECT 队列、信号量和重复浏览器关闭监听。入口协议不承担节点切换职责。

测试覆盖单连接选择、备用编辑不中断、当前参数修改触发重连、非法配置保留旧快照、三套加密、HTTP CONNECT/普通 HTTP/SOCKS5、128 KiB 分片字节比对、独立 Java 进程、旧节点 session 及 Future 回收。生命周期诊断参数为 `-Dhttp3.lifecycle.probe=true -Dhttp3.lifecycle.limit=4 -Dhttp3.lifecycle.cycles=8`。

本轮日志：`.build/http3-minimal-verify.log`、`.build/http3-minimal-dashboard.log`；打包冒烟日志为 `.build/node-selection-jar-smoke.log`。桌面原生编译及 Rust 配置测试仍交给 GitHub，页面冒烟只验证真实页面 JavaScript 和配置交互。

本地验收：Java 完整 `verify` 共 192 个用例，189 个通过、3 个原有条件跳过，无失败；10 类生命周期场景各循环 8 次并回到零资源基线。Dashboard 29 个测试全部通过，两个真实页面和新 JAR 冒烟通过。HTTP CONNECT、SOCKS5 Handler、HTTP/3 Client 已逐文件核对，与 `ef91d0d` 内容一致。
