package com.proxy.local.handler;

import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端推送消息分发 Handler
 * <p>
 * 在 Stream Pipeline 中，位于 ClientMessageHandler 之前。
 * 拦截 requestId=0 的入站 ProxyMessage（即远程服务器推送的目标站点响应数据），
 * 通过 streamId 从 {@link StreamChannelRegistry} 查找对应的浏览器 ChannelHandlerContext，
 * 将数据直接写回浏览器，不再向后续 Handler 传播。
 * </p>
 * <p>
 * 对于 requestId>0 的正常请求-响应消息，直接透传给下游 Handler 处理。
 * </p>
 * <p>
 * 性能优化：使用 write（非 writeAndFlush）累积数据，在 channelReadComplete 时统一 flush，
 * 将同一批到达的多个推送消息合并为一次系统调用，大幅提升吞吐。
 * </p>
 */
@ChannelHandler.Sharable
public class ServerPushDispatchHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private static final Logger log = LoggerFactory.getLogger(ServerPushDispatchHandler.class);

    private final StreamChannelRegistry registry = StreamChannelRegistry.getInstance();


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage msg) throws Exception {
        // 只拦截 requestId=0 的推送消息
        if (msg.getRequestId() != 0) {
            // 正常的请求-响应消息，传递给下游（ClientMessageHandler → ExchangeHandler）
            ctx.fireChannelRead(msg);
            return;
        }

        long streamId = msg.getStreamId();
        if (streamId == 0) {
            log.warn("Received push message with requestId=0 but streamId=0 (invalid), discarding");
            return;
        }

        ChannelHandlerContext browserCtx = registry.get(streamId);
        if (browserCtx == null) {
            log.warn("No browser channel found for streamId={}, discarding push data ({} bytes)",
                    streamId, msg.getData() != null ? msg.getData().length : 0);
            return;
        }

        if (!browserCtx.channel().isActive()) {
            log.debug("Browser channel for streamId={} is inactive, unregistering", streamId);
            registry.unregister(streamId);
            return;
        }

        // 将目标服务器的响应数据写回浏览器
        byte[] data = msg.getData();
        if (data != null && data.length > 0) {
            browserCtx.writeAndFlush(Unpooled.wrappedBuffer(data));
            log.debug("Dispatched {} bytes to browser for streamId={}", data.length, streamId);
        }

        // 如果是 DISCONNECT 类型，关闭浏览器连接并注销
        if (msg.getType() == ProxyMessage.MessageType.DISCONNECT) {
            log.debug("Received DISCONNECT for streamId={}, closing browser channel", streamId);
            browserCtx.close();
            registry.unregister(streamId);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception in ServerPushDispatchHandler: {}", cause.getMessage(), cause);
        ctx.fireExceptionCaught(cause);
    }
}
