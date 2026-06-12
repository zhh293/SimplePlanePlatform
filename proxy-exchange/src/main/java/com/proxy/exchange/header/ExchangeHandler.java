package com.proxy.exchange.header;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.FlowPermit;
import com.proxy.common.transport.MessageHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一交换层消息处理器 —— 客户端与服务端共用
 * <p>
 * 设计灵感来自 Dubbo 的 ExchangeHandler：一个 Handler 同时处理请求和响应，
 * 通过构造时是否传入 {@link Invoker} 隐式区分角色：
 * <ul>
 *   <li>invoker != null → 服务端角色：收到请求 → invoke → 回写响应</li>
 *   <li>invoker == null → 客户端角色：收到响应 → DefaultFuture.received() 唤醒业务线程</li>
 * </ul>
 * </p>
 * <p>
 * 消息分发逻辑（统一入口 channelRead0）：
 * <ul>
 *   <li>requestId > 0 + 服务端：请求消息，构建 Invocation → invoke → 异步回写</li>
 *   <li>requestId > 0 + 客户端：响应消息，DefaultFuture.received() 唤醒业务线程</li>
 *   <li>requestId == 0：数据面消息（服务端透传/客户端按 streamId 路由写回浏览器）</li>
 * </ul>
 * </p>
 * <p>
 * 直接挂在 HTTP/2 Stream Pipeline 末端，统一处理客户端和服务端的所有消息。
 * </p>
 */
@ChannelHandler.Sharable
public class ExchangeHandler extends SimpleChannelInboundHandler<ProxyMessage> implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ExchangeHandler.class);

    /**
     * Channel Attribute Key —— 与 ProxyMessageDecoder 中的字符串必须完全相同。
     * Netty 的 AttributeKey.valueOf() 按名称复用实例，两处无需共享常量。
     */
    private static final AttributeKey<FlowPermit> PERMIT_KEY =
            AttributeKey.valueOf("proxy.flow.permit");

    /**
     * 服务端 Invoker 链引用（客户端为 null）
     */
    private final Invoker invoker;

    /**
     * streamId → 浏览器 ChannelHandlerContext 的路由表（客户端使用，服务端为 null）
     * <p>
     * 由 proxy-local 在启动时通过 setStreamRegistry() 注入，
     * 隧道建立/关闭时的注册/注销由 StreamChannelRegistry 管理。
     * </p>
     */
    private volatile ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry;

    // ==================== 构造器 ====================

    /**
     * 客户端构造 —— 不传 invoker，只处理响应和推送路由
     */
    public ExchangeHandler() {
        this(null);
    }

    /**
     * 通用构造 —— 传入 invoker 表示服务端角色
     *
     * @param invoker 已封装 Filter 链的调用器（服务端传入，客户端传 null）
     */
    public ExchangeHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    // ==================== 核心分发逻辑 ====================

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage message) throws Exception {
        if (message == null || message.getType() == null) {
            log.warn("Received null message or null type, ignoring");
            return;
        }

        // 读取并清除 Channel Attribute 中的 FlowPermit（ProxyMessageDecoder 在 fireChannelRead 前写入）
        // 背压关闭或客户端 pipeline 无 BackpressureHandler 时 attr 为 null → 使用 NOOP
        FlowPermit permit = ctx.channel().attr(PERMIT_KEY).getAndSet(null);
        if (permit == null) {
            permit = FlowPermit.NOOP;
        }

        long requestId = message.getRequestId();
        // 性能关键：数据面（requestId==0，DATA 帧）每帧都会进入此处，高频大流量下
        // 同步写日志会阻塞唯一的 IO EventLoop 线程，导致吞吐骤降。故数据面降级为 debug，
        // 仅控制面（requestId>0，CONNECT/DISCONNECT）保留 info 便于排查。
        if (requestId > 0) {
            log.info("channelRead0: type={}, requestId={}, streamId={}, invoker={}, channel={}",
                    message.getType(), requestId, message.getStreamId(),
                    invoker != null ? "SERVER" : "CLIENT", ctx.channel());
        } else if (log.isDebugEnabled()) {
            log.debug("channelRead0: type={}, requestId={}, streamId={}, invoker={}, channel={}",
                    message.getType(), requestId, message.getStreamId(),
                    invoker != null ? "SERVER" : "CLIENT", ctx.channel());
        }

        if (requestId > 0) {
            if (invoker != null) {
                handleRequest(ctx, message, permit);
            } else {
                handleResponse(message, requestId);
                permit.release();  // 客户端路径：NOOP，无实际操作
            }
        } else {
            if (invoker != null) {
                handleServerData(ctx, message, permit);
            } else {
                handlePush(message);
                permit.release();  // 客户端路径：NOOP，无实际操作
            }
        }
    }

    // ==================== 服务端逻辑 ====================

    /**
     * 服务端处理控制面请求（requestId > 0：CONNECT/DISCONNECT）
     */
    private void handleRequest(ChannelHandlerContext ctx, ProxyMessage message, FlowPermit permit) {
        Invocation invocation = toInvocation(message, ctx);

        CompletableFuture<Response> future;
        try {
            future = invoker.invoke(invocation);
        } catch (Exception e) {
            log.error("Invoker.invoke() threw exception for requestId={}, type={}",
                    message.getRequestId(), message.getType(), e);
            permit.release();  // 同步异常时立即释放
            writeErrorResponse(ctx, message.getRequestId(), e.getMessage());
            return;
        }

        future.whenComplete((response, throwable) -> {
            permit.release();  // 无论成功/失败，invoke 完成即归还信用
            if (throwable != null) {
                log.error("Invoker chain completed exceptionally for requestId={}",
                        message.getRequestId(), throwable);
                writeErrorResponse(ctx, message.getRequestId(), throwable.getMessage());
                return;
            }
            if (response == null) {
                return;
            }
            ProxyMessage reply = ProxyMessage.builder()
                    .requestId(message.getRequestId())
                    .type(ProxyMessage.MessageType.CONNECT_RESPONSE)
                    .status(response.getStatus())
                    .message(response.getErrorMessage())
                    .data(response.getData())
                    .build();
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(reply);
            }
        });
    }

    /**
     * 服务端处理数据面消息（requestId == 0：DATA 透传）
     */
    private void handleServerData(ChannelHandlerContext ctx, ProxyMessage message, FlowPermit permit) {
        Invocation invocation = toInvocation(message, ctx);

        CompletableFuture<Response> future;
        try {
            future = invoker.invoke(invocation);
        } catch (Exception e) {
            log.error("DATA invoke failed: streamId={}", message.getStreamId(), e);
            permit.release();  // 同步异常时立即释放
            return;
        }

        future.whenComplete((response, throwable) -> {
            permit.release();  // DATA 快速路径：future 已立即完成，本行同步执行
            if (throwable != null) {
                log.error("DATA invoke completed exceptionally: streamId={}",
                        message.getStreamId(), throwable);
            }
        });
    }

    // ==================== 客户端逻辑 ====================

    /**
     * 客户端处理控制面响应（requestId > 0）
     */
    private void handleResponse(ProxyMessage message, long requestId) {
        log.info("CLIENT received response: requestId={}, type={}, status={}",
                requestId, message.getType(), message.getStatus());
        Response response;
        if (message.getStatus() == Response.OK || message.getStatus() == 0) {
            response = Response.ok(message.getData());
        } else {
            response = Response.error(
                    message.getMessage() != null ? message.getMessage() : "Remote error");
        }
        DefaultFuture.received(requestId, response);
    }

    /**
     * 客户端处理数据面推送（requestId == 0）
     * <p>
     * 按 streamId 从注册表查找对应的浏览器 ctx，将数据写回。
     * 如果收到 DISCONNECT 类型，关闭浏览器连接并注销。
     * </p>
     */
    private void handlePush(ProxyMessage message) {
        if (streamRegistry == null) {
            log.debug("Received push but no streamRegistry configured: type={}, streamId={}",
                    message.getType(), message.getStreamId());
            return;
        }

        long streamId = message.getStreamId();
        if (streamId == 0) {
            log.warn("Received push with requestId=0 and streamId=0 (invalid), discarding");
            return;
        }

        ChannelHandlerContext browserCtx = streamRegistry.get(streamId);
        if (browserCtx == null) {
            log.debug("No browser channel for streamId={}, discarding ({} bytes)",
                    streamId, message.getData() != null ? message.getData().length : 0);
            return;
        }

        if (!browserCtx.channel().isActive()) {
            log.debug("Browser channel for streamId={} inactive, unregistering", streamId);
            streamRegistry.remove(streamId);
            return;
        }

        // 将目标服务器的响应数据写回浏览器
        byte[] data = message.getData();
        if (data != null && data.length > 0) {
            browserCtx.writeAndFlush(Unpooled.wrappedBuffer(data));
        }

        // 如果是 DISCONNECT 类型，关闭浏览器连接并注销
        if (message.getType() == ProxyMessage.MessageType.DISCONNECT) {
            log.debug("Received DISCONNECT for streamId={}, closing browser channel", streamId);
            browserCtx.close();
            streamRegistry.remove(streamId);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (invoker != null) {
            log.debug("Server stream channel inactive: {}", ctx.channel());
        } else {
            // 单个 HTTP/2 stream 关闭是正常行为（session 结束），
            // 不应该 failAll 影响其他 stream 的 pending 请求。
            // 只有底层 HTTP/2 connection 断开时才需要 failAll（由 onDisconnected() 负责）。
            log.debug("Client stream channel inactive: {}", ctx.channel());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception in stream channel: {}", ctx.channel(), cause);
        if (invoker == null) {
            DefaultFuture.failAll(cause);
        }
        ctx.close();
    }

    // ==================== Setter ====================

    /**
     * 注入 streamId → 浏览器 ctx 的路由表（客户端使用）
     */
    public void setStreamRegistry(ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry) {
        this.streamRegistry = streamRegistry;
        log.info("ExchangeHandler: streamRegistry injected (size={})",
                streamRegistry != null ? streamRegistry.size() : 0);
    }

    // ==================== MessageHandler 接口（兼容 Transporter 签名） ====================

    @Override
    public void onMessage(ProxyMessage message) {
        // channelRead0 已处理，保留空实现满足接口
    }

    @Override
    public void onError(Throwable cause) {
        log.error("Connection error reported", cause);
        if (invoker == null) {
            DefaultFuture.failAll(cause);
        }
    }

    @Override
    public void onDisconnected() {
        log.warn("Connection disconnected reported");
        if (invoker == null) {
            DefaultFuture.failAll(new RuntimeException("Connection disconnected"));
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将 ProxyMessage 转换为 Invocation（服务端使用）
     */
    private Invocation toInvocation(ProxyMessage message, ChannelHandlerContext ctx) {
        Invocation invocation = new Invocation(
                message.getHost(),
                message.getPort(),
                message.getData(),
                message.getType()
        );
        invocation.setAttachment("requestId", message.getRequestId());
        String sessionKey = String.valueOf(message.getStreamId());
        invocation.setAttachment("streamId", sessionKey);
        invocation.setAttachment("rawStreamId", message.getStreamId());
        invocation.setAttachment("inboundCtx", ctx);
        return invocation;
    }

    /**
     * 回写错误响应（服务端使用）
     */
    private void writeErrorResponse(ChannelHandlerContext ctx, long requestId, String errorMessage) {
        if (ctx.channel().isActive()) {
            ProxyMessage reply = ProxyMessage.builder()
                    .requestId(requestId)
                    .type(ProxyMessage.MessageType.CONNECT_RESPONSE)
                    .status(Response.ERROR)
                    .message(errorMessage != null ? errorMessage : "Internal server error")
                    .build();
            ctx.writeAndFlush(reply);
        }
    }
}
