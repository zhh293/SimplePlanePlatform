package com.ladder.server.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class LadderMessageEncoder extends MessageToByteEncoder<LadderMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, LadderMessage msg, ByteBuf out) throws Exception {
        // 写入魔数
        out.writeBytes(LadderMessage.MAGIC);
        // 写入版本
        out.writeByte(msg.getVersion());
        // 写入消息类型
        out.writeByte(msg.getType());
        // 写入请求ID
        out.writeLong(msg.getRequestId());
        // 写入数据长度
        out.writeInt(msg.getDataLength());
        // 写入加密数据
        if (msg.getDataLength() > 0) {
            out.writeBytes(msg.getEncryptedData());
        }
    }
}
