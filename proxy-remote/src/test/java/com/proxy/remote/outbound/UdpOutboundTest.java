package com.proxy.remote.outbound;

import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class UdpOutboundTest {
    @Test
    void writeTasksAreBoundedInBothDirectionsAndRecoverWhenConsumed() {
        class HeldWrites extends io.netty.channel.ChannelOutboundHandlerAdapter {
            final java.util.List<Object> messages = new java.util.ArrayList<>();
            final java.util.List<io.netty.channel.ChannelPromise> promises = new java.util.ArrayList<>();
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message, io.netty.channel.ChannelPromise promise) {
                messages.add(message); promises.add(promise);
            }
            void complete(int index) {
                if (!promises.get(index).isDone()) {
                    io.netty.util.ReferenceCountUtil.release(messages.get(index));
                    promises.get(index).setSuccess();
                }
            }
            void completeAll() { for (int i=0; i<promises.size(); i++) complete(i); }
        }
        HeldWrites outgoing = new HeldWrites(), replies = new HeldWrites();
        EmbeddedChannel inbound = new EmbeddedChannel(replies, new ChannelInboundHandlerAdapter());
        EmbeddedChannel target = new EmbeddedChannel(outgoing);
        OutboundSession session = new OutboundSession(inbound.pipeline().lastContext(), "echo.test", 443, "bounded", 7, true);
        try {
            assertTrue(session.setOutboundChannel(target));
            for (int i=0; i<1000; i++) { session.forward(new byte[]{1}); session.writeBack(new byte[]{2}); }
            assertEquals(64, outgoing.messages.size()); assertEquals(64, replies.messages.size());
            outgoing.complete(0); replies.complete(0);
            session.forward(new byte[]{3}); session.writeBack(new byte[]{4});
            assertEquals(65, outgoing.messages.size()); assertEquals(65, replies.messages.size());
        } finally {
            outgoing.completeAll(); replies.completeAll(); session.close();
            inbound.finishAndReleaseAll(); target.finishAndReleaseAll();
        }
    }
    @Test
    void emptyDatagramMaximumBoundaryPeerFilteringAndIdleCleanup() throws Exception {
        NioEventLoopGroup workers = new NioEventLoopGroup(1);
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel inbound = new EmbeddedChannel(handler);
        SessionManager sessions = new SessionManager();
        try (DatagramSocket target = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket stranger = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(2000);
            OutboundSession session = new OutboundSession(inbound.pipeline().context(handler), "127.0.0.1", target.getLocalPort(), "udp-test", 7, true);
            sessions.register("udp-test", session);
            assertTrue(session.setOutboundChannel(new OutboundConnector(workers, 1000)
                    .connectUdp("127.0.0.1", target.getLocalPort(), session).get(2, TimeUnit.SECONDS)));
            int[] lengths = new int[103];
            lengths[0] = 1;
            lengths[102] = 1472;
            SocketAddress associationAddress = null;
            for (int length : lengths) {
                byte[] payload = new byte[length];
                java.util.Arrays.fill(payload, (byte) length);
                // Covers the null representation used by ProxyCodec for zero dataLength.
                session.forward(length == 0 ? null : payload);
                DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);
                target.receive(packet);
                if (associationAddress == null) associationAddress = packet.getSocketAddress();
                assertEquals(associationAddress, packet.getSocketAddress(), "empty datagrams must keep the association's source port");
                assertArrayEquals(payload, java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
                target.send(new DatagramPacket(payload, payload.length, packet.getSocketAddress()));
                ProxyMessage reply = awaitReply(inbound);
                assertArrayEquals(payload, reply.getData());
                assertEquals(7, reply.getStreamId());
                stranger.send(new DatagramPacket(new byte[] {99}, 1, packet.getSocketAddress()));
            }
            Thread.sleep(50);
            assertNull(inbound.readOutbound(), "a different peer cannot inject a response");
            session.forward(new byte[1473]);
            target.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, () -> target.receive(new DatagramPacket(new byte[2000], 2000)));
            session.getOutboundChannel().eventLoop().submit(() -> session.getOutboundChannel().pipeline()
                    .fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT)).sync();
            assertEquals(OutboundSession.SessionState.CLOSED, session.getState());
            assertEquals(0, sessions.activeCount());
            session.getOutboundChannel().closeFuture().sync();
        } finally {
            sessions.closeAll(); inbound.finishAndReleaseAll(); workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private ProxyMessage awaitReply(EmbeddedChannel channel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        ProxyMessage message;
        while ((message = channel.readOutbound()) == null) {
            if (System.nanoTime() > deadline) fail("UDP reply not received");
            Thread.sleep(5);
        }
        return message;
    }
}
