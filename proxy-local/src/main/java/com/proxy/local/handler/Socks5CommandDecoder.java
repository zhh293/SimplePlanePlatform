package com.proxy.local.handler;

import com.proxy.common.filter.Invoker;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/** Frames a full command before delegating CONNECT to its existing handler. */
final class Socks5CommandDecoder extends ByteToMessageDecoder {
    private final Invoker invoker;
    private final RouteRule routeRule;
    Socks5CommandDecoder(Invoker invoker, RouteRule routeRule) { this.invoker = invoker; this.routeRule = routeRule; }
    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;
        int start = in.readerIndex(), type = in.getUnsignedByte(start + 3), length;
        if (in.getUnsignedByte(start) != 5 || in.getUnsignedByte(start + 2) != 0) { ctx.close(); return; }
        if (type == 1) length = 10;
        else if (type == 4) length = 22;
        else if (type == 3) {
            if (in.readableBytes() < 5) return;
            length = 7 + in.getUnsignedByte(start + 4);
        } else { Socks5UdpAssociation.reply(ctx, 8, null); ctx.close(); return; }
        if (in.readableBytes() < length) return;
        int command = in.getUnsignedByte(start + 1);
        if (command == 1) {
            ctx.pipeline().addAfter(ctx.name(), "socks5-tcp-connect", new Socks5ConnectHandler(invoker, routeRule));
            ctx.fireChannelRead(in.readRetainedSlice(length));
            ctx.pipeline().remove(this);
        } else if (command == TunUdpRelay.COMMAND && (type == 1 || type == 3)) {
            // The private command selects an already-routed TUN destination.
            java.net.InetSocketAddress source = (java.net.InetSocketAddress) ctx.channel().remoteAddress();
            if (source == null || !"127.0.0.2".equals(source.getAddress().getHostAddress())) {
                ctx.close();
                return;
            }
            ByteBuf address = in.readSlice(length);
            address.skipBytes(3);
            Socks5UdpCodec.Datagram target = Socks5UdpCodec.decodeAddress(address);
            if (target == null) { ctx.close(); return; }
            ctx.pipeline().addAfter(ctx.name(), "udp-length", new io.netty.handler.codec.LengthFieldPrepender(2));
            ctx.pipeline().addAfter("udp-length", "tun-udp", new TunUdpRelay(invoker, target.host, target.port));
            Socks5UdpAssociation.reply(ctx, 0, null);
            ctx.pipeline().remove(this);
        } else if (command == 3 && type == 1) {
            byte[] ip = new byte[4]; in.getBytes(start + 4, ip);
            int port = in.getUnsignedShort(start + 8);
            in.skipBytes(length);
            ctx.pipeline().addAfter(ctx.name(), "socks5-udp-control", new Socks5UdpAssociation(invoker, routeRule, ip, port));
            ctx.pipeline().remove(this);
        } else {
            in.skipBytes(length);
            Socks5UdpAssociation.reply(ctx, command == 3 ? 8 : 7, null);
            ctx.close();
        }
    }
}
