package com.proxy.remote.outbound;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 目标网站响应中继器
 * <p>
 * 挂在 Outbound Channel（到目标服务器的 TCP 连接）的 Pipeline 上，
 * 收到目标返回的字节后，通过 OutboundSession 回推给客户端。
 * </p>
 * <p>
 * 这是 Outbound Channel 上唯一的业务 Handler，Pipeline 极简：
 * HEAD → OutboundHandler → TAIL
 * </p>
 */
public class OutboundHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(OutboundHandler.class);

    private final OutboundSession session;

    public OutboundHandler(OutboundSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        // 读取 ByteBuf 为字节数组
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);

        // 通过 session 回写给客户端
        session.writeBack(data);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Outbound channel to {}:{} inactive, closing session: sessionKey={}",
                session.getTargetHost(), session.getTargetPort(), session.getSessionKey());
        session.close();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception on outbound channel to {}:{}, sessionKey={}",
                session.getTargetHost(), session.getTargetPort(), session.getSessionKey(), cause);
        session.close();
        ctx.close();
    }
}
