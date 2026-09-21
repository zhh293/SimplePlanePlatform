# HTTP/3 QUIC 跨流公平性与连接级拥塞窗口：待解决问题

> 状态：待解决
>
> 范围：`proxy-transport-http3` 的 QUIC/HTTP/3 多 Stream 传输
>
> 结论：当前实现可以避免 TCP 式的连接级队头阻塞，但不能避免多个 Stream 共享连接级拥塞窗口、流量控制额度和发送缓冲区带来的资源竞争。

## 1. 问题背景

当前客户端把每个逻辑 `streamId` 映射到一个独立的 HTTP/3 双向 Stream：

```text
一个 QUIC 连接
├── HTTP/3 Stream A：逻辑 streamId = A
├── HTTP/3 Stream B：逻辑 streamId = B
└── HTTP/3 Stream C：逻辑 streamId = C
```

这样做可以把丢包影响限制在单个 Stream 的有序交付范围内：

- Stream A 的某个 QUIC 包丢失时，A 内后续字节需要等待重传；
- Stream B、C 仍然可以被 QUIC 调度和交付；
- 因此不会出现 TCP 单连接中“一个丢包阻塞整个连接所有数据交付”的队头阻塞。

但是，Stream 只是交付和排序的隔离单位，不是带宽和拥塞控制的隔离单位。所有 Stream 仍然共享同一个 QUIC 连接的底层资源。

## 2. 需要明确区分的三个窗口

### 2.1 Stream 级流量控制窗口

`Http3Connection` 配置了连接级和 Stream 级的 QUIC 流量控制参数：

```java
.initialMaxData(...)
.initialMaxStreamDataBidirectionalLocal(...)
.initialMaxStreamDataBidirectionalRemote(...)
```

对应代码：

- `proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Connection.java`
- `proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Server.java`

Stream 级窗口限制某一条 Stream 在未获得更多额度前可以发送/接收的数据量。

单纯的 UDP 丢包不会直接把其他 Stream 的 Stream 级窗口改小。QUIC 重传的是丢失的 Stream 数据帧，重传不会被当成新的应用数据重复消耗流量控制额度。

### 2.2 连接级流量控制窗口

连接级 `MAX_DATA` 是所有 Stream 共享的累计数据额度。某一条 Stream 消耗了较多连接级额度后，其他 Stream 可用的连接级额度就会减少。

因此，如果某个 Stream 长时间产生大量数据，或者接收端迟迟不消费已经到达的数据，就可能影响其他 Stream 获取发送额度。这是连接级资源竞争，不是 Stream 交付顺序造成的队头阻塞。

### 2.3 连接级拥塞窗口 `cwnd`

拥塞窗口由 QUIC/quiche 在连接级维护。发生丢包后，拥塞控制算法可能降低 `cwnd`，从而减少整个 QUIC 连接在途数据量：

```text
Stream A 丢包
    ↓
连接级 cwnd 降低
    ↓
A、B、C 都暂时拥有更少的发送机会
```

所以其他 Stream 虽然不会因为 A 的丢包而在协议交付层面被卡住，但它们的吞吐量可能下降。这种影响无法通过 HTTP/3 Stream 多路复用完全消除。

## 3. 当前项目实现情况

### 3.1 已经具备的隔离

客户端按逻辑 `streamId` 管理 Stream 状态，并为每条 Stream 保存自己的待发送队列：

```java
ConcurrentHashMap<Long, Http3StreamState> streams;
```

对应代码：

- `proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3Client.java`
- `proxy-transport-http3/src/main/java/com/proxy/transport/http3/Http3StreamState.java`

每条 Stream 还有独立的：

- `pending` 消息队列；
- `pendingBytes` 字节统计；
- Netty 写缓冲区高低水位；
- Stream 建立、就绪、失败和关闭状态。

因此，一条 Stream 暂时不可写时，不会直接把其他 Stream 的 Java 队列锁住。

### 3.2 当前缺少的能力

当前实现没有应用层的连接级公平调度器：

- 没有 Round-Robin 或 Weighted Round-Robin；
- 没有为每条 Stream 预留连接级发送配额；
- 没有按照 Stream 优先级分配发送字节数；
- 没有暴露或统计 QUIC 连接级 `cwnd`、重传量和各 Stream 的实际发送份额。

待发送消息最终直接写入对应的 QUIC Stream：

```java
channel.writeAndFlush(message)
```

应用层依赖 Netty/QUIC 内部调度，而不是项目自身保证多 Stream 的严格公平性。

### 3.3 当前的队列上限不是公平调度

项目目前设置了：

- 单 Stream pending 上限：默认 4 MB；
- 连接 pending 总上限：默认 64 MB。

这两个限制主要用于控制内存和防止无限排队，并不能保证公平性。

当连接级 pending 总量超过上限时，当前触发检查的 Stream 会被失败：

```java
if (state.pendingBytes + bytes > streamLimit
        || connectionPendingBytes.get() + bytes > connectionLimit) {
    failStream(state, ...);
}
```

这意味着一个流量很大的 Stream 可能占用大部分连接级排队空间，之后其他 Stream 只能依赖上限保护被动失败，而不是获得稳定的发送份额。

## 4. 问题影响

当前系统可能同时出现以下现象：

### 4.1 不会出现的情况

- Stream A 丢包不会强制等待 Stream A 完整恢复后才交付 Stream B；
- Stream A 的应用层队列不会直接阻塞 Stream B 的 Java `synchronized(state)`；
- HTTP/3 DATA Frame 的边界不会被错误地当成 `ProxyMessage` 的消息边界。

### 4.2 仍然可能出现的情况

- Stream A 丢包导致连接级 `cwnd` 降低，B、C 吞吐下降；
- Stream A 消耗大量连接级流量控制额度，B、C 暂时无法继续发送；
- Stream A 产生大量待发送数据，占用连接级 pending 内存；
- 没有优先级调度时，大流量 Stream 可能获得更多发送机会；
- 如果所有业务最终复用同一个逻辑 `streamId`，则仍然存在该 Stream 内的有序交付队头阻塞。

因此需要区分：

```text
跨 Stream 交付队头阻塞：QUIC 已经解决
跨 Stream 资源竞争：当前仍然存在
```

## 5. 待解决方案

### 5.1 P0：增加可观测性

首先补充以下指标和日志：

- 每条 Stream 的 pending 字节数和消息数；
- 连接级 pending 字节数；
- 每条 Stream 的写入成功/失败次数；
- Stream 不可写持续时间；
- QUIC 重传次数、丢包次数和 RTT；
- 连接级拥塞窗口或可用发送额度（如果底层库能够暴露）。

没有这些数据，无法判断实际瓶颈是丢包、拥塞窗口、连接级流控还是应用层排队。

### 5.2 P1：增加连接级公平调度器

不要让业务线程直接把大量消息连续写入单条 Stream，可以增加一个连接级发送调度器：

1. 每条 Stream 维护自己的待发送队列；
2. 调度器使用 Round-Robin 或 Weighted Round-Robin；
3. 每轮每条 Stream 最多发送固定字节数或固定消息数；
4. 当前 Stream 不可写时跳过它，继续调度其他 Stream；
5. 连接级 pending 上限按 Stream 配额拆分，而不是只设置一个总上限。

推荐优先采用按字节数的 Deficit Round-Robin，避免“大消息 Stream”天然占据更多轮次。

### 5.3 P1：完善每条 Stream 的背压策略

需要明确以下策略：

- 单 Stream 达到上限时，只暂停或失败该 Stream；
- 连接级达到上限时，不能让某个 Stream 无限占用额度；
- 新 Stream 至少保留最小发送配额；
- 对长时间不可写的 Stream 设置超时和诊断日志；
- 对不同业务设置优先级，例如控制消息高于大块数据转发。

### 5.4 P2：必要时使用多个 QUIC 连接

如果某些业务需要强隔离，例如控制面、交互式请求和大文件传输，可以拆成不同的 QUIC 连接：

```text
控制面连接：低延迟、高优先级
交互流量连接：普通优先级
大吞吐连接：允许独占较大 cwnd
```

这样可以隔离拥塞窗口，但代价是：

- 连接和 TLS/QUIC 握手数量增加；
- 每条连接都要独立进行拥塞控制；
- 内存、端口和保活开销增加；
- 连接管理和重连逻辑变复杂。

因此不应作为默认方案，只在实测确认单连接竞争无法接受时采用。

## 6. 验收标准

后续解决后至少需要验证：

1. 人为让 Stream A 持续丢包或延迟时，Stream B 仍能继续交付数据；
2. A 的 pending 队列达到上限时，不会无限挤占 B、C 的队列和发送配额；
3. 连接级 pending 达到上限时，失败行为可预测，并且不会误伤无关的高优先级 Stream；
4. 多 Stream 并发时，各 Stream 的发送份额符合配置的权重；
5. 在相同丢包率下，控制消息的延迟不会被大块数据 Stream 长时间拖高；
6. 单 Stream 内仍保持 `ProxyMessage` 有序交付和可靠传输；
7. 正常网络条件下，公平调度不会造成明显吞吐下降。

## 7. 最终结论

当前 HTTP/3/QUIC 改造解决的是“连接级交付队头阻塞”，没有解决“连接级拥塞窗口和发送资源竞争”。

这不是 QUIC 实现错误，而是多路复用协议的边界：

> Stream 隔离了数据顺序和丢包交付影响，但所有 Stream 仍然共享连接级拥塞控制、流量控制和物理带宽。

后续应优先补充指标和连接级公平调度，再根据实际数据决定是否需要多 QUIC 连接隔离。
