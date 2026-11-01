package com.proxy.remote.outbound;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.net.InetSocketAddress;
import io.netty.util.AttributeKey;

/** Netty and JDK connected writes skip empty datagrams; unconnected send emits them. */
final class UdpOutboundChannel {
    private static final AttributeKey<DatagramChannel> SOCKET = AttributeKey.valueOf("udp.outbound.socket");
    private static final AttributeKey<InetSocketAddress> PEER = AttributeKey.valueOf("udp.outbound.peer");
    private UdpOutboundChannel() { }

    /** Bootstrap resolves/connects first; disconnect before acknowledging or sending any data.
     * Reconnecting after sending an empty packet can drain its already-arrived response in JDK 17.
     * Keep the socket unconnected and enforce the immutable peer on both directions instead. */
    static void prepare(Channel channel) throws java.io.IOException {
        DatagramChannel socket = channel.attr(SOCKET).get();
        InetSocketAddress peer = (InetSocketAddress) socket.getRemoteAddress();
        socket.disconnect();
        channel.attr(PEER).set(peer);
    }

    static InetSocketAddress peer(Channel channel) { return channel.attr(PEER).get(); }

    static NioDatagramChannel create() {
        try {
            DatagramChannel socket = DatagramChannel.open();
            socket.bind(new InetSocketAddress(0));
            NioDatagramChannel channel = new NioDatagramChannel(socket);
            channel.attr(SOCKET).set(socket);
            channel.pipeline().addLast("empty-datagrams", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    if (message instanceof ByteBuf && !((ByteBuf) message).isReadable()) {
                        try {
                            socket.send(ByteBuffer.allocate(0), peer(ctx.channel()));
                            promise.setSuccess();
                        } catch (Exception error) {
                            promise.setFailure(error);
                        } finally {
                            io.netty.util.ReferenceCountUtil.release(message);
                        }
                    } else {
                        if (message instanceof ByteBuf) {
                            ctx.write(new io.netty.channel.socket.DatagramPacket((ByteBuf) message, peer(ctx.channel())), promise);
                        } else {
                            ctx.write(message, promise);
                        }
                    }
                }
            });
            return channel;
        } catch (java.io.IOException error) {
            throw new ChannelException(error);
        }
    }
}
