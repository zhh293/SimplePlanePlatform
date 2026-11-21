package com.proxy.local.handler;
import com.proxy.common.filter.Invoker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/** Accumulates negotiation; remaining bytes are forwarded to the command decoder. */
public final class Socks5InitHandler extends ByteToMessageDecoder {
    private final Invoker invoker;
    private final RouteRule routeRule;
    public Socks5InitHandler(Invoker invoker, RouteRule routeRule) {
        this.invoker = invoker; this.routeRule = routeRule;
    }
    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) return;
        int start = in.readerIndex();
        if (in.getUnsignedByte(start) != 5) { ctx.close(); return; }
        int count = in.getUnsignedByte(start + 1);
        if (in.readableBytes() < 2 + count) return;
        boolean accepted = false;
        for (int i = 0; i < count; i++) accepted |= in.getUnsignedByte(start + 2 + i) == 0;
        in.skipBytes(2 + count);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{5, (byte) (accepted ? 0 : 255)}));
        if (!accepted) { in.skipBytes(in.readableBytes()); ctx.close(); return; }
        ctx.pipeline().addAfter(ctx.name(), "socks5-connect", new Socks5CommandDecoder(invoker, routeRule));
        ctx.pipeline().remove(this);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
