package com.proxy.remote.integration;

import com.proxy.common.model.ProxyMessage;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UdpAssociationIsolationTest {
    @Test
    void sameLogicalIdInDifferentClientStreamsCannotReplaceOrReceiveEachOthersDatagrams() throws Exception {
        NioEventLoopGroup workers = new NioEventLoopGroup(1);
        ExecutorService business = Executors.newFixedThreadPool(2);
        DispatchInvoker dispatch = new DispatchInvoker(business, new OutboundConnector(workers, 1000), 1500);
        EmbeddedChannel first = new EmbeddedChannel(DefaultChannelId.newInstance(), new ExchangeHandler(dispatch));
        EmbeddedChannel second = new EmbeddedChannel(DefaultChannelId.newInstance(), new ExchangeHandler(dispatch));
        try (DatagramSocket target = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(2000);
            for (EmbeddedChannel channel : new EmbeddedChannel[] {first, second}) {
                channel.writeInbound(ProxyMessage.builder().type(ProxyMessage.MessageType.UDP_CONNECT)
                        .requestId(1).streamId(7).host("127.0.0.1").port(target.getLocalPort())
                        .data("udp-v1".getBytes(StandardCharsets.US_ASCII)).build());
                ProxyMessage ack = awaitMessage(channel);
                assertEquals(200, ack.getStatus());
                assertArrayEquals("udp-v1".getBytes(StandardCharsets.US_ASCII), ack.getData());
            }
            assertEquals(2, dispatch.getSessionManager().activeCount());
            int index = 0;
            for (EmbeddedChannel channel : new EmbeddedChannel[] {first, second}) {
                byte[] bytes = new byte[] {(byte) ++index};
                channel.writeInbound(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(7).data(bytes).build());
                DatagramPacket packet = new DatagramPacket(new byte[32], 32);
                target.receive(packet);
                assertEquals(index, packet.getData()[0]);
                target.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                assertArrayEquals(bytes, awaitMessage(channel).getData());
            }
            first.close(); first.runPendingTasks();
            assertEquals(1, dispatch.getSessionManager().activeCount());
            second.close(); second.runPendingTasks();
            assertEquals(0, dispatch.getSessionManager().activeCount());
        } finally {
            first.finishAndReleaseAll(); second.finishAndReleaseAll(); dispatch.shutdown(); business.shutdownNow();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private ProxyMessage awaitMessage(EmbeddedChannel channel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ProxyMessage message;
        while ((message = channel.readOutbound()) == null) {
            channel.runPendingTasks();
            if (System.nanoTime() > deadline) fail("proxy response timed out");
            Thread.sleep(5);
        }
        return message;
    }
}
