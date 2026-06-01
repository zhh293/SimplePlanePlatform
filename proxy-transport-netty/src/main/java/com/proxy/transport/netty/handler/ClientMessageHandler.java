package com.proxy.transport.netty.handler;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.MessageHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端消息处理器
 * <p>
 * 工作在 HTTP/2 Stream 子 Channel 的 Pipeline 末端，
 * 将解码后的 ProxyMessage 分发给上层 MessageHandler。
 * </p>
 */
public class ClientMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageHandler.class);

    private final MessageHandler messageHandler;

    public ClientMessageHandler(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage msg) throws Exception {
        if (messageHandler != null) {
            messageHandler.onMessage(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("HTTP/2 stream channel inactive: {}", ctx.channel());
        if (messageHandler != null) {
            messageHandler.onDisconnected();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception in HTTP/2 stream channel: {}", ctx.channel(), cause);
        if (messageHandler != null) {
            messageHandler.onError(cause);
        }
        ctx.close();
    }
}
