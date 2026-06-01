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
 * 双向数据转发 Handler —— 隧道建立后的数据中继
 * <p>
 * 隧道建立成功后（SOCKS5 CONNECT 或 HTTP CONNECT 完成），
 * 浏览器发来的所有数据都通过 ClusterInvoker 以 DATA 类型转发到远程，
 * 远程回来的数据通过 ExchangeClient 的回调写回浏览器 channel。
 * </p>
 * <p>
 * 连接关闭时发送 DISCONNECT 通知远程释放资源。
 * </p>
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    private final Invoker invoker;
    private final String targetHost;
    private final int targetPort;

    public RelayHandler(Invoker invoker, String targetHost, int targetPort) {
        this.invoker = invoker;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
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

            // 构建 DATA 类型的 Invocation，通过 ClusterInvoker 转发到远程
            Invocation invocation = new Invocation(targetHost, targetPort, data, ProxyMessage.MessageType.DATA);

            invoker.invoke(invocation).whenComplete((response, throwable) -> {
                if (throwable != null) {
                    log.error("Relay DATA failed for {}:{}", targetHost, targetPort, throwable);
                    ctx.close();
                    return;
                }

                // 远程返回的数据写回浏览器
                if (response != null && response.isSuccess() && response.getData() != null
                        && response.getData().length > 0) {
                    ByteBuf responseBuf = ctx.alloc().buffer(response.getData().length);
                    responseBuf.writeBytes(response.getData());
                    ctx.writeAndFlush(responseBuf);
                }
            });
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 浏览器断开连接，通知远程释放资源
        log.debug("Client disconnected, sending DISCONNECT for {}:{}", targetHost, targetPort);

        Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.DISCONNECT);
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
