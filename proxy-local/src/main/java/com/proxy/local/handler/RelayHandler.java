package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 出站数据转发 Handler —— 隧道建立后的上行中继
 * <p>
 * 隧道建立成功后（SOCKS5 CONNECT 或 HTTP CONNECT 完成），
 * 浏览器发来的所有数据都通过 ClusterInvoker 以 DATA 类型转发到远程。
 * </p>
 * <p>
 * <strong>关键设计（数据面 = 推送模型）</strong>：远程目标站点返回的数据
 * 不是以“请求-响应”的形式回到本 Handler，而是由远程服务器主动
 * <em>推送</em>（requestId=0 + streamId），在本地由
 * {@link ServerPushDispatchHandler} 按 streamId 路由写回浏览器。
 * 因此本 Handler 只负责<strong>上行（浏览器 → 远程）</strong>，
 * DATA 调用采用“发后即忘”，不再消费响应数据，避免与推送路径
 * 产生双重写回。控制面（CONNECT 握手 / DISCONNECT 通知）仍使用
 * 请求-响应语义以确保可靠性与错误传播。
 * </p>
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    private final Invoker invoker;
    private final String targetHost;
    private final int targetPort;
    private final long streamId;
    private final StreamChannelRegistry streamRegistry = StreamChannelRegistry.getInstance();

    public RelayHandler(Invoker invoker, String targetHost, int targetPort, long streamId) {
        this.invoker = invoker;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.streamId = streamId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() == 0) {
                return;
            }

            // 提取浏览器发来的原始字节
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            // 构建 DATA 类型的 Invocation，通过 ClusterInvoker 转发到远程。
            // 数据面采用推送模型：上行发后即忘，下行由 ServerPushDispatchHandler
            // 按 streamId 路由写回浏览器，因此这里不消费 response.getData()。
            Invocation invocation = new Invocation(targetHost, targetPort, data, ProxyMessage.MessageType.DATA);
            invocation.setAttachment("streamId", streamId);

            invoker.invoke(invocation).whenComplete((response, throwable) -> {
                if (throwable != null) {
                    log.error("Relay DATA failed for {}:{}, streamId={}", targetHost, targetPort, streamId, throwable);
                    ctx.close();
                }
                // 成功时无需处理：目标站点的回包由远程推送 + ServerPushDispatchHandler 写回
            });
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 浏览器断开连接，注销 streamId 并通知远程释放资源
        log.debug("Client disconnected, sending DISCONNECT for {}:{}, streamId={}", targetHost, targetPort, streamId);
        streamRegistry.unregister(streamId);

        Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.DISCONNECT);
        invocation.setAttachment("streamId", streamId);
        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.debug("DISCONNECT notification failed for {}:{}", targetHost, targetPort);
            }
        });

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Relay error for {}:{}", targetHost, targetPort, cause);
        ctx.close();
    }
}
