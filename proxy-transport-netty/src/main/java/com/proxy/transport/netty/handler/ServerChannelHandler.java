package com.proxy.transport.netty.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 服务端 Pipeline 尾端入站处理器
 * <p>
 * 接收解码后的 {@link ProxyMessage}，构建 {@link Invocation}，
 * 通过 {@link Invoker} 链处理请求，并将响应通过 ctx 异步回写给客户端。
 * </p>
 * <p>
 * 采用"内聚一层"方案：直接持有 Invoker 引用，在 channelRead0 中完成
 * invoke + 回写的全部逻辑，无需中间层传递 ctx。
 * </p>
 */
public class ServerChannelHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private static final Logger log = LoggerFactory.getLogger(ServerChannelHandler.class);

    private final Invoker invoker;

    public ServerChannelHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage message) throws Exception {
        // 构建 Invocation
        Invocation invocation = toInvocation(message, ctx);

        // 异步调用 Invoker 链
        CompletableFuture<Response> future;
        try {
            future = invoker.invoke(invocation);
        } catch (Exception e) {
            log.error("Invoker.invoke() threw exception for requestId={}, type={}",
                    message.getRequestId(), message.getType(), e);
            writeErrorResponse(ctx, message.getRequestId(), e.getMessage());
            return;
        }

        // 异步回写响应
        future.whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.error("Invoker chain completed exceptionally for requestId={}",
                        message.getRequestId(), throwable);
                writeErrorResponse(ctx, message.getRequestId(), throwable.getMessage());
                return;
            }

            if (response == null) {
                return; // 某些消息类型可能不需要回写（如 DATA 透传场景）
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

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Server channel inactive: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception in server channel: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    /**
     * 将 ProxyMessage 转换为 Invocation
     *
     * @param message 原始消息
     * @param ctx     Channel 上下文（供后续 Outbound 使用）
     * @return 调用上下文
     */
    private Invocation toInvocation(ProxyMessage message, ChannelHandlerContext ctx) {
        Invocation invocation = new Invocation(
                message.getHost(),
                message.getPort(),
                message.getData(),
                message.getType()
        );
        invocation.setAttachment("requestId", message.getRequestId());
        // streamId 已由客户端 StreamIdFactory 保证全局唯一（高16位=clientId，低48位=序列号），
        // 直接作为 sessionKey 使用，多 local 实例连同一 remote 也不会碰撞。
        String sessionKey = String.valueOf(message.getStreamId());
        invocation.setAttachment("streamId", sessionKey);
        invocation.setAttachment("rawStreamId", message.getStreamId());
        invocation.setAttachment("inboundCtx", ctx);
        return invocation;
    }

    /**
     * 回写错误响应
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
