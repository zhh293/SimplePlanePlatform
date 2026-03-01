package com.ladder.client.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class LadderMessageDecoder extends ByteToMessageDecoder {
    // 消息头长度：魔数(4) + 版本(1) + 类型(1) + 请求ID(8) + 数据长度(4) = 18字节
    private static final int HEADER_LENGTH = 4 + 1 + 1 + 8 + 4;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 检查是否有足够的数据读取消息头
        if (in.readableBytes() < HEADER_LENGTH) {
            return;
        }

        // 标记当前位置，用于重置
        in.markReaderIndex();

        // 读取魔数
        byte[] magic = new byte[4];
        in.readBytes(magic);

        // 验证魔数
        for (int i = 0; i < 4; i++) {
            if (magic[i] != LadderMessage.MAGIC[i]) {
                ctx.close();
                return;
            }
        }

        // 读取版本
        byte version = in.readByte();
        // 读取消息类型
        byte type = in.readByte();
        // 读取请求ID
        long requestId = in.readLong();
        // 读取数据长度
        int dataLength = in.readInt();

        // 检查是否有足够的数据读取消息体
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }

        // 读取加密数据
        byte[] encryptedData = new byte[dataLength];
        in.readBytes(encryptedData);

        // 创建消息对象
        LadderMessage message = new LadderMessage();
        message.setVersion(version);
        message.setType(type);
        message.setRequestId(requestId);
        message.setDataLength(dataLength);
        message.setEncryptedData(encryptedData);

        out.add(message);
    }
}