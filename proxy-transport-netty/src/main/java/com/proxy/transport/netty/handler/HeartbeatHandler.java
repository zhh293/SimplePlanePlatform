package com.proxy.transport.netty.handler;

import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳处理器（已废弃，不再挂载到任何 Pipeline）
 *
 * <p><b>废弃原因：</b>
 * 该 Handler 原本挂在每个 HTTP/2 Stream 子 Channel 上，负责写空闲时发 HEARTBEAT_REQUEST、
 * 读空闲超时时关闭 Stream。但这导致了严重的 Stream 泄漏：
 * <ul>
 *   <li>浏览器侧连接关闭后，服务端如果未收到 DISCONNECT，对应的 Stream 上的心跳
 *       会让 {@code channel.isActive()} 永远返回 true，使 {@code SessionManager} 的
 *       inactive 清理完全失效。</li>
 *   <li>HTTP/2 连接级探活（{@code Http2Connection} 中的 PING 帧调度）已覆盖
 *       "判断 TCP 连接是否存活"的需求，Stream 级心跳属于多余的重复探活。</li>
 * </ul>
 *
 * <p><b>替代方案：</b>
 * <ul>
 *   <li>连接级探活：{@code Http2Connection.startPingScheduler()} 定期发送 HTTP/2 PING 帧。</li>
 *   <li>Stream 超龄兜底：{@code NettyClient.cleanupStaleStreams()} 和
 *       {@code SessionManager.cleanupSessions()} 定期强制关闭存活时间超过阈值的 Stream/Session。</li>
 * </ul>
 *
 * @deprecated 已从 stream pipeline 移除，保留此类仅作历史参考。
 */
@Deprecated
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
