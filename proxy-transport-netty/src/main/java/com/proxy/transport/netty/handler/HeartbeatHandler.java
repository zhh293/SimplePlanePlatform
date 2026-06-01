package com.proxy.transport.netty.handler;

import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳处理器
 * <p>
 * 在 HTTP/2 Stream 子 Channel 上处理心跳逻辑：
 * - 写空闲时发送 HEARTBEAT_REQUEST
 * - 收到 HEARTBEAT_REQUEST 时回复 HEARTBEAT_RESPONSE
 * - 读空闲超时时关闭连接
 * </p>
 */
public class HeartbeatHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private int readIdleCount = 0;
    private static final int MAX_READ_IDLE_COUNT = 3;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.WRITER_IDLE) {
                // 写空闲，发送心跳
                ProxyMessage heartbeat = ProxyMessage.builder()
                        .type(ProxyMessage.MessageType.HEARTBEAT_REQUEST)
                        .requestId(System.nanoTime())
                        .build();
                ctx.writeAndFlush(heartbeat);
                log.debug("Sent heartbeat request on channel: {}", ctx.channel());
            } else if (event.state() == IdleState.READER_IDLE) {
                readIdleCount++;
                if (readIdleCount >= MAX_READ_IDLE_COUNT) {
                    log.warn("Read idle timeout exceeded {} times, closing channel: {}",
                            MAX_READ_IDLE_COUNT, ctx.channel());
                    ctx.close();
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 收到任何消息，重置读空闲计数
        readIdleCount = 0;

        if (msg instanceof ProxyMessage) {
            ProxyMessage proxyMsg = (ProxyMessage) msg;
            if (proxyMsg.getType() == ProxyMessage.MessageType.HEARTBEAT_REQUEST) {
                // 回复心跳
                ProxyMessage response = ProxyMessage.builder()
                        .type(ProxyMessage.MessageType.HEARTBEAT_RESPONSE)
                        .requestId(proxyMsg.getRequestId())
                        .build();
                ctx.writeAndFlush(response);
                log.debug("Replied heartbeat response for requestId: {}", proxyMsg.getRequestId());
                return; // 心跳消息不再向下传递
            } else if (proxyMsg.getType() == ProxyMessage.MessageType.HEARTBEAT_RESPONSE) {
                log.debug("Received heartbeat response for requestId: {}", proxyMsg.getRequestId());
                return; // 心跳响应不再向下传递
            }
        }

        // 非心跳消息，继续传递
        ctx.fireChannelRead(msg);
    }
}
