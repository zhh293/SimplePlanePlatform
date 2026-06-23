# Task-2：HttpProxyRelayHandler —— HTTP 普通代理中继处理器

> **所属 SDD**：[http-plain-proxy-design.md](./http-plain-proxy-design.md)
> **任务编号**：Task-2 / 3
> **预计产出**：`HttpProxyRelayHandler.java` + 编译通过
> **前置依赖**：Task-1（HttpRequestParser，需已编译通过）
> **后续依赖**：Task-3（HttpConnectHandler 改造）依赖本类

---

## 1. 任务目标

实现 `HttpProxyRelayHandler`，作为 HTTP 普通代理模式下的数据中继处理器。它与现有 CONNECT 隧道的 `RelayHandler` 角色类似，但需要处理一个关键差异：**建连成功后必须立即发送首条 HTTP 请求数据**，而不是回复 `200 Connection Established`。

## 2. 文件位置

```
proxy-local/src/main/java/com/proxy/local/handler/HttpProxyRelayHandler.java
```

## 3. 与 RelayHandler 的对比

在开始之前先明确本处理器与现有 `RelayHandler` 的关系：

| 对比维度 | RelayHandler（现有） | HttpProxyRelayHandler（新增） |
|---------|---------------------|-------------------------------|
| 适用模式 | CONNECT 隧道 | HTTP 普通代理 |
| 何时加入 pipeline | 隧道建立成功、回复 200 之后 | 远端建连成功之后（不回 200） |
| 首条数据 | 无（隧道建好后浏览器才发 TLS ClientHello） | 有，必须立即发送重写后的首条 HTTP 请求 |
| 后续 channelRead | 透传字节 | 透传字节（与 RelayHandler 完全一致） |
| channelInactive | 发 DISCONNECT + 注销 streamId | 发 DISCONNECT + 注销 streamId（完全一致） |

**核心区别只有一个**：构造时接收 `initialRequest` 字节，加入 pipeline 后立即发送。

## 4. 实现思路

### 4.1 生命周期流程

```
构造函数
  │ 接收 invoker, targetHost, targetPort, streamId, initialRequest
  │
  ▼
handlerAdded(ctx)
  │ 通过 invoker 将 initialRequest 作为第一个 DATA 帧发送到远程
  │ 发送完毕后将 initialRequest 置 null（释放引用，帮助 GC）
  │
  ▼
channelRead(ctx, msg)  ── 循环 ──
  │ 后续浏览器发来的数据，提取 byte[]，构建 DATA Invocation
  │ 通过 invoker 发送（发后即忘，与 RelayHandler 一致）
  │
  ▼
channelInactive(ctx)
  │ 注销 streamId
  │ 发送 DISCONNECT 通知远程释放资源
  │
  ▼
exceptionCaught(ctx, cause)
  │ 日志记录 + 关闭连接
```

### 4.2 核心代码结构

```java
package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * HTTP 普通代理中继处理器。
 * <p>
 * 与 CONNECT 隧道的 {@link RelayHandler} 的唯一区别：
 * 加入 pipeline 时立即发送首条 HTTP 请求数据（已由 HttpRequestParser 完成 URL 重写）。
 * 后续的 channelRead、channelInactive、exceptionCaught 逻辑与 RelayHandler 完全一致。
 * </p>
 *
 * <h3>为什么不复用 RelayHandler</h3>
 * <p>
 * RelayHandler 加入 pipeline 时不发送任何数据，它假定隧道已建好、浏览器会主动发数据。
 * 但 HTTP 普通代理模式下，触发建连的那条 HTTP 请求已经被 HttpConnectHandler 读取并消费，
 * 如果不在此处主动发送，这条请求就会丢失，目标服务器不会返回任何响应。
 * 单独新建一个 Handler 比给 RelayHandler 加可选参数更清晰，职责更明确。
 * </p>
 */
public class HttpProxyRelayHandler extends ChannelInboundHandlerAdapter {

    private final Invoker invoker;
    private final String targetHost;
    private final int targetPort;
    private final long streamId;
    private final StreamChannelRegistry streamRegistry = StreamChannelRegistry.getInstance();

    /** 重写后的首条 HTTP 请求，发送后置 null */
    private byte[] initialRequest;

    public HttpProxyRelayHandler(Invoker invoker, String targetHost, int targetPort,
                                  long streamId, byte[] initialRequest) {
        this.invoker = invoker;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.streamId = streamId;
        this.initialRequest = initialRequest;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // 立即发送首条请求
        if (initialRequest != null && initialRequest.length > 0) {
            sendData(initialRequest);
            initialRequest = null; // 释放引用
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 与 RelayHandler.channelRead 完全一致
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() == 0) return;
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            sendData(data);
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 与 RelayHandler.channelInactive 完全一致
        streamRegistry.unregister(streamId);
        Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.DISCONNECT);
        invocation.setAttachment("streamId", streamId);
        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                // log DISCONNECT 失败
            }
        });
        super.channelInactive(ctx);
    }

    /**
     * 通过 invoker 发送 DATA 帧（发后即忘）
     */
    private void sendData(byte[] data) {
        Invocation invocation = new Invocation(targetHost, targetPort, data, ProxyMessage.MessageType.DATA);
        invocation.setAttachment("streamId", streamId);
        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                // log 发送失败
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // log + close
        ctx.close();
    }
}
```

### 4.3 关键设计决策

#### 为什么在 handlerAdded 而不是 channelActive 中发送首条请求？

`channelActive` 在 channel 变为 active 时触发，但此时本 handler 可能还没被加入 pipeline。而 `handlerAdded` 在 handler 被添加到 pipeline 的那一刻立即触发，且此时 channel 已经是 active 的（因为浏览器的 TCP 连接早已建立）。所以用 `handlerAdded` 能确保首条数据在 handler 就位后立即发出。

#### 为什么 initialRequest 发送后置 null？

`initialRequest` 是一个 `byte[]`，可能有几 KB。发送完毕后这段数据不再需要，置 null 可以帮助 GC 及时回收。虽然不是必须的，但对于长连接场景（keep-alive 可能持续数分钟），这是个好习惯。

#### 为什么不直接复用 RelayHandler + 添加可选参数？

方案对比：

- **方案 A**：给 RelayHandler 构造函数加一个 `byte[] initialRequest` 可选参数，为 null 时行为不变，非 null 时在 handlerAdded 中发送。
- **方案 B**：新建 HttpProxyRelayHandler，职责独立。

选择方案 B 的理由：RelayHandler 已在 CONNECT 和 SOCKS5 两条路径中稳定运行，不动它可以避免引入回归风险。新建一个 handler 代码量很小（核心逻辑都是复制 RelayHandler 的），但职责更清晰、改动更安全。

## 5. 测试方案

### 5.1 编译验证

```bash
cd /Users/zhanghonghao/Desktop/SimplePlanePlatform
mvn compile -pl proxy-local
```

确认无编译错误、无警告。

### 5.2 单元级验证（手工 main 方法）

由于 `HttpProxyRelayHandler` 依赖 Netty 和 Invoker，完整的单元测试需要 mock 框架。如果项目未配置 Mockito，可以用以下方法验证核心逻辑：

```java
// 验证 1: 构造函数参数正确存储
HttpProxyRelayHandler handler = new HttpProxyRelayHandler(
    mockInvoker, "example.com", 80, 12345L, "GET / HTTP/1.1\r\n\r\n".getBytes());
// 通过反射检查 initialRequest 不为 null

// 验证 2: handlerAdded 后 initialRequest 被置 null
handler.handlerAdded(mockCtx);
// 通过反射检查 initialRequest 为 null
// 通过 mockInvoker 验证收到了一次 DATA invoke
```

### 5.3 集成测试（在 Task-3 完成后做）

本 handler 的完整功能验证需要配合 `HttpConnectHandler` 的改造，在 Task-3 的全链路测试中一并验证。此处只确保：

- 类编译通过
- 与 `RelayHandler` 的 API 签名保持一致（channelRead、channelInactive、exceptionCaught）
- `handlerAdded` 中正确调用了 `sendData`

### 5.4 代码对比检查

将 `HttpProxyRelayHandler` 和 `RelayHandler` 做 diff，确认：

- `channelRead` 逻辑完全一致
- `channelInactive` 逻辑完全一致
- `exceptionCaught` 逻辑完全一致
- 唯一新增的是 `handlerAdded` 和 `initialRequest` 相关逻辑

## 6. 完成标准

- [ ] `HttpProxyRelayHandler.java` 编写完成，放在 `proxy-local/.../handler/` 包下
- [ ] `mvn compile -pl proxy-local` 编译通过
- [ ] 代码包含完整的 Javadoc 注释，说明与 RelayHandler 的区别和设计理由
- [ ] `channelRead`、`channelInactive`、`exceptionCaught` 与 RelayHandler 逻辑一致
- [ ] `handlerAdded` 中正确发送 initialRequest 并置 null

## 7. 不做什么

- 不修改现有的 `RelayHandler`
- 不修改 `HttpConnectHandler`（Task-3 负责）
- 不处理 pipeline 的切换逻辑（由 Task-3 在 HttpConnectHandler 中负责）
- 不做全链路测试（Task-3 负责）
