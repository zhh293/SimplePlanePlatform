package com.proxy.local;

import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.local.config.ProxyConfig;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.transport.http3.Http3Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.proxy.local.Http3ManualSwitchIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual browser CONNECT/SOCKS -> local -> QUIC A/B -> TCP target, including stopped remote recovery. */
class Http3AutoSwitchIntegrationTest {
    @TempDir Path temp;

    @Test void remoteFailureRecoveryAndModeRoundTripUseOriginalTransportPaths() throws Exception {
        TestCertificates certificate = TestCertificates.create();
        NioEventLoopGroup workers = new NioEventLoopGroup(2);
        ExecutorService business = Executors.newFixedThreadPool(4);
        OutboundConnector connector = new OutboundConnector(workers, 3000) {
            @Override public CompletableFuture<Channel> connect(String host, int port,
                    com.proxy.remote.outbound.OutboundSession session) {
                return super.connect("127.0.0.1", port, session);
            }
        };
        DispatchInvoker dispatchA = new DispatchInvoker(business, connector, 3000);
        DispatchInvoker dispatchB = new DispatchInvoker(business, connector, 3000);
        Http3Server a = null, b = null;
        ProxyLocalServer local = null;
        Channel target = null;
        try {
            target = new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.writeAndFlush(message); }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) target.localAddress()).getPort();
            int portA = udpPort(), portB = udpPort(), localPort = tcpPort();
            a = new Http3Server(serverUrl(portA, certificate, "aes-gcm-v2"), new ExchangeHandler(dispatchA));
            b = new Http3Server(serverUrl(portB, certificate, "aes-gcm-v2"), new ExchangeHandler(dispatchB));
            a.start(); b.start();
            Path config = temp.resolve("auto.yml");
            String automatic = yaml(localPort, portA, portB, certificate, "a", "aes-gcm-v2")
                    .replace("mode: manual", "mode: auto\n  preferredNodeId: a\n  probeHost: echo.test\n  probePort: " + targetPort)
                    // Abrupt UDP server shutdown is detected by QUIC idle timeout; shorten A's transport timeout only.
                    .replaceFirst("http3:", "http3:\n      idleTimeoutMs: 3000");
            Files.write(config, automatic.getBytes(StandardCharsets.UTF_8));
            local = new ProxyLocalServer(ProxyConfig.load(config.toString()));
            local.start(); local.watchNodeConfig(config);
            awaitStatus(config, "\"currentNodeId\":\"a\"");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (b.getActiveConnectionCount() != 1 && System.nanoTime() < deadline) Thread.sleep(25);
            assertEquals(1, a.getActiveConnectionCount());
            assertEquals(1, b.getActiveConnectionCount());
            try (Socket browserA = connect(localPort, targetPort)) {
                echo(browserA, "automatic-before-failure");
                a.close(); a = null; dispatchA.getSessionManager().closeAll();
                awaitStatus(config, "\"currentNodeId\":\"b\"");
                assertEquals(-1, browserA.getInputStream().read());
            }
            try (Socket browserB = connectSocks5(localPort, targetPort)) {
                echo(browserB, "automatic-after-failure");
                // closeAll permanently retires the old manager; a remote restart needs fresh state.
                dispatchA = new DispatchInvoker(business, connector, 3000);
                a = new Http3Server(serverUrl(portA, certificate, "aes-gcm-v2"), new ExchangeHandler(dispatchA));
                a.start();
                Path status = config.resolveSibling("auto.yml.status.json");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
                boolean recovered = false;
                while (System.nanoTime() < deadline) {
                    String state = new String(Files.readAllBytes(status), StandardCharsets.UTF_8);
                    if (state.contains("\"nodeId\":\"a\",\"circuit\":\"CLOSED\"")) { recovered = true; break; }
                    Thread.sleep(100);
                }
                assertTrue(recovered, "A must recover through HALF_OPEN checks");
                awaitStatus(config, "\"currentNodeId\":\"b\"");
                echo(browserB, "preferred-recovery-does-not-preempt");
                Files.write(config, yaml(localPort, portA, portB, certificate, "b", "aes-gcm-v2").getBytes(StandardCharsets.UTF_8));
                awaitStatus(config, "\"mode\":\"manual\"");
                awaitStatus(config, "\"nodeStatus\":\"available\"");
                assertEquals(-1, browserB.getInputStream().read());
            }
            try (Socket manual = connect(localPort, targetPort)) {
                echo(manual, "manual-after-auto");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (a.getActiveConnectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals(0, a.getActiveConnectionCount(), "Manual mode closes backup preconnections");
                Files.write(config, automatic.getBytes(StandardCharsets.UTF_8));
                awaitStatus(config, "\"mode\":\"auto\"");
                awaitStatus(config, "\"currentNodeId\":\"a\"");
                assertEquals(-1, manual.getInputStream().read(), "Returning to auto retires the manual stream");
                try (Socket automaticBrowser = connectSocks5(localPort, targetPort)) {
                    echo(automaticBrowser, "automatic-after-manual");
                    verifyPlainHttp(localPort, targetPort);
                }
            }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (dispatchA.getSessionManager().activeCount() != 0
                    || dispatchB.getSessionManager().activeCount() != 0
                    || com.proxy.exchange.header.DefaultFuture.getActiveFutureCount() != 0)) Thread.sleep(25);
            assertEquals(0, dispatchA.getSessionManager().activeCount());
            assertEquals(0, dispatchB.getSessionManager().activeCount());
            assertEquals(0, com.proxy.exchange.header.DefaultFuture.getActiveFutureCount());
        } finally {
            if (local != null) local.shutdown();
            if (a != null) a.close();
            if (b != null) b.close();
            dispatchA.getSessionManager().closeAll(); dispatchB.getSessionManager().closeAll();
            if (target != null) target.close().sync();
            business.shutdownNow();
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            certificate.delete();
        }
    }
}
