# 节点选择、故障切换与连接生命周期

本文记录截至 `e94a2c2` 的手动节点选择、自动故障切换与连接生命周期，保留当时 cluster 绑定及逐流退役的说明。本轮已移除这两项管理，当前行为以[作者设计文档第 3.5 节](node-switching-author-prompt-and-source-mapping.md#remove-cluster-bindings)为准；下文源码链接固定到历史提交，不将旧实现表述为当前代码。

## 1. 入口与对象关系

配置启用 `nodeSelection` 时，`ProxyLocalServer.buildInvokerChain()` 创建 `Http3NodeController`，业务请求使用它持有的 `StickyClusterInvoker`。这一分支直接构造集群实例，不通过 `config.cluster` 选择集群实现。

每个节点的对象关系如下：

```text
Http3NodeController
  ├─ nodes：nodeId → Node
  │    ├─ config：节点配置
  │    ├─ circuit：节点熔断状态
  │    ├─ wrapped：Filter 链 → observe() → raw
  │    └─ raw：ClientInvoker
  │                └─ HeaderExchangeClient
  │                     ├─ ExchangeHandler
  │                     └─ 底层 Http3Client
  └─ StickyClusterInvoker
       ├─ invokers：节点调用器列表
       ├─ currentInvoker：当前业务节点的调用器
       └─ bindings：streamId → Binding（原始选定的 Invoker）
```

控制器同时保存 `raw` 和 `wrapped`：业务请求经过 `wrapped`，关闭节点时直接调用 `raw.close()`，无需拆开 Filter 链。`HeaderExchanger.connect()` 为每条节点连接创建独立的 `ExchangeHandler`，并将其与底层 Client 一起交给 `HeaderExchangeClient` 持有。

相关代码：

- [ProxyLocalServer.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/ProxyLocalServer.java)：`buildInvokerChain()`。
- [Http3NodeController.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/Http3NodeController.java)：`Node`、`beginConnect()`。
- [HeaderExchanger.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-exchange/src/main/java/com/proxy/exchange/header/HeaderExchanger.java)：`connect()`。

## 2. 手动模式

初始化时只连接 `selectedNodeId` 对应的节点。连接成功后，将其 `wrapped` 加入集群，并调用 `cluster.setCurrentInvoker(node.wrapped)`。因此，成功初始化后，集群中只有选中节点的一个 Invoker，业务使用固定的 `currentInvoker`。

手动模式不自动寻找其他节点兜底，但仍检查连接可用性。`StickyClusterInvoker.invoke()` 在处理每个 CONNECT 时直接取得 `currentInvoker`：

- 当前 Invoker 为空、不属于集群成员或连接不可用，返回 `Current node unavailable`。
- 当前 Invoker 可用，使用它创建 stream 绑定并发起 CONNECT。

业务入口不遍历备用节点检查其可用性，也不调用负载均衡。初始化尚未完成、切换清理中或没有健康候选时，current 可以为空；此时 `isAvailable()` 同样返回 false，等待控制器安装 current。调整理由及测试记录见[作者原始设计与源码对应，第 3.4 节](node-switching-author-prompt-and-source-mapping.md#current-invoker-only)。

因此，“手动模式不需要管其他可用节点”可以理解为不自动切换到其他节点，不能理解为完全不检查当前连接是否可用。

手动更换节点通过 `Http3NodeController.apply()` 生效。模式和选中节点的连接配置未变时，不重建连接；需要切换时，先退役当前节点、关闭旧节点，再按新配置初始化。自动模式切换到手动模式时，也会关闭旧的预连接节点并重新建立手动目标节点连接。

配置文件由 `NodeConfigWatcher` 每隔 500ms 检查摘要变化，再调用 `controller.apply(next)`。自动模式下修改自动节点配置目前要求重启，不能将所有配置变更都视为热切换。

相关代码：

- [Http3NodeController.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/Http3NodeController.java)：`initialize()`、`apply()`、`selectionKey()`。
- [StickyClusterInvoker.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-cluster/src/main/java/com/proxy/cluster/StickyClusterInvoker.java)：`invoke()`、`setCurrentInvoker()`。
- [NodeConfigWatcher.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/NodeConfigWatcher.java)：`poll()`。

## 3. 自动模式的选点与重选

自动模式预连接所有配置节点，但业务流量固定使用当前节点。控制器选择节点时执行两层过滤：

1. `NodeCircuit.eligible()`：节点未被配置阻断，且熔断状态为 `CLOSED`。
2. `StickyClusterInvoker.selectCurrent()`：候选属于集群，且 `candidate.isAvailable()`。

健康的首选节点优先；否则使用控制器配置的 `random` 负载均衡策略选择节点，再调用 `setCurrentInvoker()` 固定选择。初次选点时，若首选节点仍在连接，会先等待其连接结果。

每次 `initialize()` 都会重置 `lastSelectionAt`，所以从手动模式重新进入自动模式也会执行上述首选等待规则，不沿用上一次自动运行期间的选点间隔或初始化状态。

### 3.1 可用性检查不等于当次重选

自动模式同样会在每个业务 CONNECT 时检查 current 的成员归属与连接可用性。发现 current 不可用时，当次请求返回错误，不会在该次调用中立即负载均衡重选，也不生成全体可用成员列表。

手动和自动模式都直接使用控制器安装的 current；`fixedSelection` 及业务入口中的负载均衡兜底分支已移除。自动模式的重选仍由控制器调用 `selectCurrent()` 负责，该方法保留健康候选过滤和负载均衡，不在 `StickyClusterInvoker.invoke()` 内重试。

当前选点没有“在一次调用中不断选择，直到拿到健康节点”的循环。本次没有候选时保持不可用，后续由控制器再次尝试。已经存在 `currentNodeId` 时，`chooseCurrent()` 直接返回，因此恢复后的首选节点不会抢占健康的当前节点。

### 3.2 自动故障切换流程

控制器通过单线程定时任务每隔 250ms 执行一次 `tick()`（固定延迟调度）：

```text
tick()
  → 判断连接故障、健康检查与恢复条件
  → fail(故障节点)
       → 熔断该节点
       → 若为当前节点：retireCurrent()
       → 若为备用节点：closeNode(node)
       → 刷新集群成员
  → chooseCurrent()
       → 从符合熔断与连接可用性条件的节点中选择
       → 安装新的 currentInvoker
```

连接连续不可用达到 5 秒后，控制器才通过连接故障路径调用 `fail()`。在此期间，新 CONNECT 可能因为当前连接不可用而失败；底层 HTTP/3 Client 也有每隔 500ms 尝试重连的机制，连接恢复后会重置控制器的连续断开计时。

业务 CONNECT 没有收到可确认的远端响应时，会请求健康检查。健康检查通过探测目标发起 CONNECT，再发送 DISCONNECT 清理探测流。判断依据是是否收到远端响应，远端返回目标连接失败也能证明节点仍可响应，并非必须成功连通探测目标才算响应有效。

熔断与恢复的主要条件：

| 条件 | 当前实现 |
| --- | --- |
| 连接持续不可用 | 达到 5 秒后判故障 |
| 健康检查失败 | 激活后前 15 秒不累计失败；累计至少 3 次失败且激活至少 30 秒后判故障 |
| 检查间隔 | 失败检查及半开恢复检查按 5 秒间隔控制 |
| 熔断冷却 | 从 30 秒开始递增，基础值上限 300 秒，另加最多 20% 抖动 |
| 半开恢复 | 两次符合间隔要求的远端响应后恢复为 `CLOSED` |
| 首选节点恢复 | 留作备用，不抢占健康的当前节点 |

相关代码：

- [Http3NodeController.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/Http3NodeController.java)：`chooseCurrent()`、`tick()`、`observe()`、`probe()`、`fail()`。
- [NodeCircuit.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/NodeCircuit.java)：可选条件、故障计时、熔断与恢复状态机。
- [Http3Client.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Client.java)：`attachConnectionListener()`、`scheduleReconnect()`。

## 4. 切换时的 stream 退役与两侧连接关闭

当前节点的完整退役顺序如下：

```text
Http3NodeController.retireCurrent()
  ├─ cluster.setCurrentInvoker(null)
  │    └─ 暂停新 CONNECT 准入
  ├─ cluster.retireBindings()
  │    ├─ 移除各 stream 的绑定并取消 active 状态
  │    └─ 通过各自原来的 Invoker 发送 DISCONNECT
  ├─ 等待最多 min(2000ms, timeoutMs)
  ├─ nodes.get(currentNodeId)
  ├─ closeNode(old)
  │    └─ old.raw.close()
  │         └─ HeaderExchangeClient.close()
  │              ├─ ExchangeHandler.onDisconnected()
  │              │    └─ failPending() → onStreamClosed()
  │              └─ client.close()
  └─ 清空 currentNodeId
```

### 4.1 浏览器侧由 ExchangeHandler 清理

`ExchangeHandler` 保存两类信息：

- `pendingRequests`：本连接尚未完成的控制请求。
- `ownedStreams`：本连接所属的 streamId；CONNECT 请求完成后，仍保留已建立 stream 的归属。

`onDisconnected()` 会终结待完成请求，并遍历本连接的 `ownedStreams`。`onStreamClosed()` 按 streamId 从共享的浏览器注册表中移除对应上下文、关闭浏览器连接，并在所属 stream 被移除时回调监听器。控制器注册的监听器是 `cluster::unbind`，用于清除集群中的对应绑定。

因此，即使浏览器注册表是共享的，父连接断开时仍按该 `ExchangeHandler` 的所属 stream 清理，不会直接关闭注册表中的所有浏览器连接。

### 4.2 节点侧由底层 Client 清理

`ClientInvoker.close()` 调用 `ExchangeClient.close()`。`HeaderExchangeClient.close()` 先通知 `ExchangeHandler.onDisconnected()`，再调用底层 `client.close()`。

HTTP/3 实现会停止重连任务，终结并清空所属 stream，然后调用 `Http3Connection.close()` 关闭 QUIC Channel、Datagram Channel 和 EventLoop。

### 4.3 nodeId 的作用与清理范围

关闭节点时，是控制器通过 `currentNodeId` 从 `nodes` 找到 `Node`，再关闭该节点持有的 `ClientInvoker`。`ExchangeHandler` 自身按 streamId 和所属 stream 集合清理，不再按 nodeId 搜索。

此外，`retireBindings()` 当前遍历的是集群全部绑定，没有 nodeId 过滤。因为业务使用单个 currentInvoker，这些通常就是旧当前节点的业务流。自动故障切换时，健康备用节点的预连接会保留；配置切换的 `apply()` 路径则会关闭旧节点集合并重新初始化。

相关代码：

- [Http3NodeController.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-local/src/main/java/com/proxy/local/config/Http3NodeController.java)：`retireCurrent()`、`closeNode()`。
- [StickyClusterInvoker.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-cluster/src/main/java/com/proxy/cluster/StickyClusterInvoker.java)：`retireBindings()`、`unbind()`。
- [ClientInvoker.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-cluster/src/main/java/com/proxy/cluster/ClientInvoker.java)：`close()`。
- [HeaderExchangeClient.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-exchange/src/main/java/com/proxy/exchange/header/HeaderExchangeClient.java)：`close()`。
- [ExchangeHandler.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-exchange/src/main/java/com/proxy/exchange/header/ExchangeHandler.java)：`trackRequest()`、`failPending()`、`onStreamClosed()`。
- [Http3Client.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Client.java)：`close()`。
- [Http3Connection.java](https://github.com/zhh293/SimplePlanePlatform/blob/e94a2c20a7a66da1e198b382f6fefa1027dfd008/proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Connection.java)：`close()`。

## 5. 已建立 stream 不随选点迁移

CONNECT 时，`StickyClusterInvoker` 创建 `streamId → Binding`，其中保存当时选定的 Invoker。后续 DATA 和 DISCONNECT 直接使用这个绑定，不重新负载均衡。

节点切换会退役旧绑定并关闭旧流；已有流的 DATA 不会转发到新节点。新节点承接的是后续新建的 CONNECT。绑定提前移除后，即使旧 CONNECT 延迟返回成功，也不能重新激活该绑定。

## 6. 与讨论中描述的对应关系

| 讨论中的描述 | 当前代码行为 |
| --- | --- |
| 手动模式只有一个 Invoker，由 currentInvoker 固定使用 | 是，连接成功后只安装所选节点的 Invoker |
| 手动模式不管其他可用节点 | 不自动选其他节点，但仍检查当前连接可用性 |
| 自动模式也固定 currentInvoker | 是，健康时持续复用 |
| 每次使用会检查可用性 | 新 CONNECT 会检查；DATA、DISCONNECT 使用已有 stream 绑定 |
| 当前节点没有或不可用时，当次一直负载均衡直到选到健康节点 | 当前没有该循环；控制器检测故障、退役旧节点后负责重选 |
| 选点排除熔断与不可用节点 | 是，分别由控制器和集群选点方法过滤 |
| 根据 nodeId 关闭旧 Invoker 两侧连接 | 控制器按 nodeId 找 Node，再通过 raw.close() 触发两侧清理；此前还会退役 stream 并等待 DISCONNECT |
| ExchangeHandler 负责浏览器连接，底层 client.close() 负责节点连接 | 是，HeaderExchangeClient.close() 串起这两部分 |
