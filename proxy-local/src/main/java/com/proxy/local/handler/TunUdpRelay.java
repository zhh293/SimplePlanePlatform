package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;
import java.util.List;

/** One private SOCKS command per local TCP flow; every uint16-length frame is one datagram. */
final class TunUdpRelay extends ByteToMessageDecoder {
    static final int COMMAND = 0xf0;
    private final Invoker invoker;
    private final String host;
    private final int port;
    private long streamId;

    TunUdpRelay(Invoker invoker, String host, int port) {
        this.invoker = invoker;
        this.host = host;
        this.port = port;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) return;
        int length = in.getUnsignedShort(in.readerIndex());
        if (length > Socks5UdpCodec.MAX_PAYLOAD) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < 2 + length) return;
        in.skipBytes(2);
        byte[] data = new byte[length];
        in.readBytes(data);
        boolean first = streamId == 0;
        if (first) {
            StreamChannelRegistry registry = StreamChannelRegistry.getInstance();
            streamId = registry.nextStreamId();
            ctx.channel().attr(AttributeKey.<Boolean>valueOf("proxy.local.framedUdp")).set(true);
            registry.register(streamId, ctx);
        }
        Invocation message = new Invocation(host, port, data, first ? MessageType.UDP_OPEN : MessageType.DATA);
        message.setAttachment("streamId", streamId);
        message.setAttachment("route", "proxy");
        invoker.invoke(message).whenComplete((response, error) -> {
            if (error != null || (response != null && !response.isSuccess())) ctx.close();
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
