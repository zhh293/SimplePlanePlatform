package com.ladder.server.handler;

import com.ladder.server.crypto.EncryptionUtil;
import com.ladder.server.protocol.LadderMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LadderServerHandler extends ChannelInboundHandlerAdapter {
    private static final byte[] ENCRYPTION_KEY = "1234567890123456".getBytes();
    private final Map<Long, Channel> remoteChannels = new ConcurrentHashMap<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LadderMessage) {
            LadderMessage message = (LadderMessage) msg;
            long requestId = message.getRequestId();
            byte type = message.getType();

            if (type == LadderMessage.TYPE_CONNECT) {
                // 1. 处理连接请求
                handleConnect(ctx, requestId, message.getEncryptedData());
            } else if (type == LadderMessage.TYPE_DATA) {
                // 2. 处理数据传输
                handleData(ctx, requestId, message.getEncryptedData());
            } else if (type == LadderMessage.TYPE_DISCONNECT) {
                // 3. 处理断开连接
                handleDisconnect(requestId);
            }
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, long requestId, byte[] encryptedData) throws Exception {
        // 解密目标地址
        byte[] decryptedData = EncryptionUtil.decrypt(encryptedData, ENCRYPTION_KEY);
        String target = new String(decryptedData);
        String[] parts = target.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("Connecting to " + host + ":" + port + " [ReqID: " + requestId + "]");

        // 连接目标服务器
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, true) // 自动读取
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext remoteCtx) {
                                // 连接成功
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext remoteCtx, Object msg) throws Exception {
                                if (msg instanceof ByteBuf) {
                                    ByteBuf buf = (ByteBuf) msg;
                                    byte[] bytes = new byte[buf.readableBytes()];
                                    buf.readBytes(bytes);

                                    // 加密并转发给客户端
                                    byte[] encrypted = EncryptionUtil.encrypt(bytes, ENCRYPTION_KEY);
                                    LadderMessage dataMsg = new LadderMessage(LadderMessage.TYPE_DATA, requestId,
                                            encrypted);
                                    ctx.writeAndFlush(dataMsg);
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext remoteCtx) {
                                // 目标服务器断开，通知客户端
                                LadderMessage disconnectMsg = new LadderMessage(LadderMessage.TYPE_DISCONNECT,
                                        requestId, null);
                                ctx.writeAndFlush(disconnectMsg);
                                remoteChannels.remove(requestId);
                            }
                        });
                    }
                });

        ChannelFuture f = b.connect(host, port);
        remoteChannels.put(requestId, f.channel());

        f.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                // 连接失败，通知客户端断开
                LadderMessage disconnectMsg = new LadderMessage(LadderMessage.TYPE_DISCONNECT, requestId, null);
                ctx.writeAndFlush(disconnectMsg);
                remoteChannels.remove(requestId);
            }
        });
    }

    private void handleData(ChannelHandlerContext ctx, long requestId, byte[] encryptedData) throws Exception {
        Channel remoteChannel = remoteChannels.get(requestId);
        if (remoteChannel != null && remoteChannel.isActive()) {
            // 解密数据
            byte[] decryptedData = EncryptionUtil.decrypt(encryptedData, ENCRYPTION_KEY);
            // 转发给目标服务器
            remoteChannel.writeAndFlush(Unpooled.wrappedBuffer(decryptedData));
        }
    }

    private void handleDisconnect(long requestId) {
        Channel remoteChannel = remoteChannels.remove(requestId);
        if (remoteChannel != null) {
            remoteChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
        // 关闭所有远程连接
        for (Channel ch : remoteChannels.values()) {
            ch.close();
        }
        remoteChannels.clear();
    }
}
