package com.proxy.local;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage.MessageType;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.local.config.Http3NodeController;
import com.proxy.local.config.ProxyConfig;
import com.proxy.local.handler.StreamChannelRegistry;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.transport.http3.Http3Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.proxy.local.Http3ManualSwitchIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests stale events through the actual cluster, exchange client, QUIC and remote target path. */
class ClusterStreamLifecycleIntegrationTest {
    @TempDir Path temp;

    @Test void staleOldEventsAfterSwitchCannotCreateRemoteSessionsOrBreakNewData() throws Exception {
        TestCertificates certificate = TestCertificates.create();
        NioEventLoopGroup workers = new NioEventLoopGroup(2);
        ExecutorService business = Executors.newFixedThreadPool(4);
        OutboundConnector connector = new OutboundConnector(workers, 3000) {
            public CompletableFuture<Channel> connect(String host, int port, com.proxy.remote.outbound.OutboundSession session) {
                return super.connect("127.0.0.1", port, session);
            }
        };
        DispatchInvoker dispatchA = new DispatchInvoker(business, connector, 3000);
        DispatchInvoker dispatchB = new DispatchInvoker(business, connector, 3000);
        Http3Server a = null, b = null;
        Channel target = null;
        Http3NodeController controller = null;
        EmbeddedChannel oldBrowser = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel newBrowser = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        StreamChannelRegistry registry = StreamChannelRegistry.getInstance();
        long oldId = registry.nextStreamId(), newId = registry.nextStreamId();
        try {
            target = new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                public void channelRead(ChannelHandlerContext ctx, Object data) { ctx.writeAndFlush(data); }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) target.localAddress()).getPort();
            int portA = udpPort(), portB = udpPort(), localPort = tcpPort();
            a = new Http3Server(serverUrl(portA, certificate, "aes-gcm-v2"), new ExchangeHandler(dispatchA));
            b = new Http3Server(serverUrl(portB, certificate, "aes-gcm-v2"), new ExchangeHandler(dispatchB));
            a.start(); b.start();
            Path config = temp.resolve("client.yml");
            Files.write(config, yaml(localPort, portA, portB, certificate, "a", "aes-gcm-v2").getBytes(StandardCharsets.UTF_8));
            controller = new Http3NodeController(ProxyConfig.load(config.toString()));
            registry.register(oldId, oldBrowser.pipeline().firstContext());
            assertTrue(controller.getClusterInvoker().invoke(message(oldId, targetPort, MessageType.CONNECT)).get(10, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, controller.getActiveStreamCount());
            Files.write(config, yaml(localPort, portA, portB, certificate, "b", "aes-gcm-v2").getBytes(StandardCharsets.UTF_8));
            controller.apply(ProxyConfig.load(config.toString()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!controller.getClusterInvoker().isAvailable() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(controller.getClusterInvoker().isAvailable());
            oldBrowser.runPendingTasks();
            assertFalse(oldBrowser.isActive());
            registry.register(newId, newBrowser.pipeline().firstContext());
            assertTrue(controller.getClusterInvoker().invoke(message(newId, targetPort, MessageType.CONNECT)).get(10, TimeUnit.SECONDS).isSuccess());
            com.proxy.common.cluster.ClusterInvoker current = controller.getClusterInvoker();
            for (int attempt = 0; attempt < 3; attempt++) {
                Invocation stale = message(oldId, targetPort, MessageType.DATA);
                assertThrows(CompletionException.class, () -> current.invoke(stale).join());
                assertFalse(current.invoke(message(oldId, targetPort, MessageType.DISCONNECT))
                        .get(10, TimeUnit.SECONDS).isSuccess());
            }
            assertEquals(1, dispatchB.getSessionManager().activeCount(), "Old events cannot create target sessions");
            assertTrue(newBrowser.isActive());
            assertEquals(1, controller.getActiveStreamCount());
            byte[] payload = "healthy-after-stale-events".getBytes(StandardCharsets.US_ASCII);
            Invocation data = message(newId, targetPort, MessageType.DATA);
            data.setData(payload);
            assertTrue(current.invoke(data).get(10, TimeUnit.SECONDS).isSuccess());
            java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (received.size() < payload.length && System.nanoTime() < deadline) {
                newBrowser.runPendingTasks();
                ByteBuf block = newBrowser.readOutbound();
                if (block == null) { Thread.sleep(10); continue; }
                try {
                    byte[] bytes = new byte[block.readableBytes()];
                    block.readBytes(bytes);
                    received.write(bytes);
                } finally { block.release(); }
            }
            assertArrayEquals(payload, received.toByteArray());
            controller.close();
            controller = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((dispatchA.getSessionManager().activeCount() != 0 || dispatchB.getSessionManager().activeCount() != 0)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, dispatchA.getSessionManager().activeCount());
            assertEquals(0, dispatchB.getSessionManager().activeCount());
            assertEquals(0, registry.size());
            assertFalse(newBrowser.isActive());
            assertEquals(0, com.proxy.exchange.header.DefaultFuture.getActiveFutureCount());
        } finally {
            if (controller != null) controller.close();
            if (a != null) a.close();
            if (b != null) b.close();
            dispatchA.getSessionManager().closeAll();
            dispatchB.getSessionManager().closeAll();
            registry.unregister(oldId); registry.unregister(newId);
            oldBrowser.finishAndReleaseAll(); newBrowser.finishAndReleaseAll();
            if (target != null) target.close().sync();
            business.shutdownNow();
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            certificate.delete();
        }
    }

    private static Invocation message(long id, int port, MessageType type) {
        Invocation invocation = new Invocation("echo.test", port, new byte[]{42}, type);
        invocation.setAttachment("streamId", id);
        return invocation;
    }
}
