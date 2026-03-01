package com.ladder.client.handler;

import com.ladder.client.crypto.EncryptionUtil;
import com.ladder.client.protocol.LadderMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class ServerHandler extends ChannelInboundHandlerAdapter {
    private final byte[] encryptionKey;

    public ServerHandler(byte[] encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LadderMessage) {
            LadderMessage message = (LadderMessage) msg;
            handleServerMessage(message);
        }
    }

    private void handleServerMessage(LadderMessage message) throws Exception {
        long requestId = message.getRequestId();
        byte[] encryptedData = message.getEncryptedData();
        byte type = message.getType();

        // 处理服务器响应
        HttpProxyHandler.handleServerMessage(requestId, encryptedData, encryptionKey, type);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}