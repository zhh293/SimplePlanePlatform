package com.proxy.local;

import com.proxy.common.model.URL;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.local.config.ProxyConfig;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.transport.http3.Http3Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Browser TCP -> local CONNECT -> real QUIC remote -> target TCP, then config-driven switch. */
class Http3ManualSwitchIntegrationTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"none,false", "chacha20-poly1305-v2,false", "aes-gcm-v2,false", "aes-gcm-v2,true"})
    void atomicConfigSwitchDisconnectsOldFlowAndNewBrowserUsesNewNode(String suite, boolean forkProcess) throws Exception {
        TestCertificates certificate = TestCertificates.create();
        NioEventLoopGroup workers = new NioEventLoopGroup(2);
        java.util.concurrent.ExecutorService business = java.util.concurrent.Executors.newFixedThreadPool(4);
        // Deterministic test DNS: browser uses a proxied hostname; target TCP stays on loopback.
        OutboundConnector connector = new OutboundConnector(workers, 3000) {
            @Override public java.util.concurrent.CompletableFuture<Channel> connect(String host, int port,
                    com.proxy.remote.outbound.OutboundSession session) {
                assertEquals("echo.test", host);
                return super.connect("127.0.0.1", port, session);
            }
        };
        DispatchInvoker dispatchA = new DispatchInvoker(business, connector, 3000);
        DispatchInvoker dispatchB = new DispatchInvoker(business, connector, 3000);
        Http3Server a = null, b = null;
        Channel target = null;
        ProxyLocalServer local = null;
        Process localProcess = null;
        Socket browserA = null, browserB = null;
        java.util.concurrent.atomic.AtomicInteger connectsA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger connectsB = new java.util.concurrent.atomic.AtomicInteger();
        try {
            target = new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    ctx.writeAndFlush(message);
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) target.localAddress()).getPort();
            int portA = udpPort(), portB = udpPort(), localPort = tcpPort();
            a = new Http3Server(serverUrl(portA, certificate, suite), new ExchangeHandler(invocation -> {
                if (invocation.getType() == com.proxy.common.model.ProxyMessage.MessageType.CONNECT) connectsA.incrementAndGet();
                return dispatchA.invoke(invocation);
            }));
            b = new Http3Server(serverUrl(portB, certificate, suite), new ExchangeHandler(invocation -> {
                if (invocation.getType() == com.proxy.common.model.ProxyMessage.MessageType.CONNECT) connectsB.incrementAndGet();
                return dispatchB.invoke(invocation);
            }));
            a.start();
            b.start();
            Path config = temp.resolve("proxy.yml");
            String initial = yaml(localPort, portA, portB, certificate, "a", suite);
            Files.write(config, initial.getBytes(StandardCharsets.UTF_8));
            if (forkProcess) {
                localProcess = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        "com.proxy.local.ProxyLocalServer", config.toString())
                        .redirectErrorStream(true).redirectOutput(temp.resolve("local-process.log").toFile()).start();
                awaitStatus(config, "\"currentNodeId\":\"a\"");
            } else {
                local = new ProxyLocalServer(ProxyConfig.load(config.toString()));
                local.start();
                local.watchNodeConfig(config);
            }
            browserA = connect(localPort, targetPort);
            echo(browserA, "before-switch");
            assertEquals(1, connectsA.get());
            assertEquals(0, connectsB.get());
            assertEquals(1, a.getActiveConnectionCount(), "Only one client connection is needed per node");
            assertEquals(0, b.getActiveConnectionCount(), "Backup nodes must not be preconnected");
            String backupOnlyChange = initial.replace("id: b\n", "id: b\n    name: renamed-backup\n");
            Files.write(config, backupOnlyChange.getBytes(StandardCharsets.UTF_8));
            awaitStatus(config, "\"appliedRevision\":\"" + digest(backupOnlyChange) + "\"");
            echo(browserA, "backup-only-change-keeps-a-alive");
            Path next = temp.resolve("next.yml");
            Files.write(next, yaml(localPort, portA, portB, certificate, "b", suite).getBytes(StandardCharsets.UTF_8));
            Files.move(next, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            awaitStatus(config, "\"currentNodeId\":\"b\"");
            assertEquals(-1, browserA.getInputStream().read(), "Old browser tunnel must be closed");
            awaitSessionsReleased(dispatchA);
            browserB = forkProcess ? connectSocks5(localPort, targetPort) : connect(localPort, targetPort);
            echo(browserB, "after-switch");
            assertEquals(1, connectsA.get());
            assertEquals(1, connectsB.get());
            assertEquals(1, b.getActiveConnectionCount());
            byte[] fragmented = new byte[131072];
            new java.util.Random(7).nextBytes(fragmented);
            for (int offset = 0; offset < fragmented.length; offset += 1023) {
                browserB.getOutputStream().write(fragmented, offset, Math.min(1023, fragmented.length - offset));
            }
            assertArrayEquals(fragmented, readBytes(browserB.getInputStream(), fragmented.length));
            verifyPlainHttp(localPort, targetPort);
            // Invalid snapshots must leave the last valid node and its healthy streams alone.
            Files.write(config, yaml(localPort, portA, portB, certificate, "missing", suite).getBytes(StandardCharsets.UTF_8));
            awaitStatus(config, "Configuration not applied");
            echo(browserB, "still-on-b");
            String changedCurrent = yaml(localPort, portA, portB, certificate, "b", suite)
                    .replace("timeoutMs: 5000", "timeoutMs: 6000");
            Files.write(config, changedCurrent.getBytes(StandardCharsets.UTF_8));
            awaitStatus(config, "\"appliedRevision\":\"" + digest(changedCurrent) + "\"");
            assertEquals(-1, browserB.getInputStream().read(), "Changed current-node parameters retire its flows");
            awaitSessionsReleased(dispatchB);
            browserB = connect(localPort, targetPort);
            echo(browserB, "reconnected-after-current-node-edit");
            browserB.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (dispatchA.getSessionManager().activeCount() != 0
                    || dispatchB.getSessionManager().activeCount() != 0
                    || com.proxy.exchange.header.DefaultFuture.getActiveFutureCount() != 0)) Thread.sleep(25);
            assertEquals(0, dispatchA.getSessionManager().activeCount());
            assertEquals(0, dispatchB.getSessionManager().activeCount());
            assertEquals(0, com.proxy.exchange.header.DefaultFuture.getActiveFutureCount());
        } finally {
            if (browserA != null) browserA.close();
            if (browserB != null) browserB.close();
            if (local != null) local.shutdown();
            if (localProcess != null) {
                localProcess.destroy();
                if (!localProcess.waitFor(10, TimeUnit.SECONDS)) {
                    localProcess.destroyForcibly();
                    localProcess.waitFor(10, TimeUnit.SECONDS);
                }
            }
            if (a != null) a.close();
            if (b != null) b.close();
            dispatchA.getSessionManager().closeAll();
            dispatchB.getSessionManager().closeAll();
            if (target != null) target.close().sync();
            business.shutdownNow();
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            certificate.delete();
        }
    }

    static URL serverUrl(int port, TestCertificates certificate, String suite) {
        return new URL("proxy", "localhost", port).addParameter("transport", "http3")
                .addParameter("cipher", suite).addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
    }

    static String yaml(int local, int a, int b, TestCertificates certificate, String selected, String suite) {
        String ca = certificate.ca().getAbsolutePath().replace("'", "''");
        return "localPort: " + local + "\nsystemProxy:\n  enabled: false\nconnectionsPerNode: 1\ntimeoutMs: 5000\nremoteServers:\n"
                + "  - id: a\n    host: localhost\n    port: " + a + "\n    transport: http3\n    cipher: " + suite + "\n    cipherKey: '0123456789abcdef0123456789abcdef'\n    http3:\n      caFile: '" + ca + "'\n"
                + "  - id: b\n    host: localhost\n    port: " + b + "\n    transport: http3\n    cipher: " + suite + "\n    cipherKey: '0123456789abcdef0123456789abcdef'\n    http3:\n      caFile: '" + ca + "'\n"
                + "nodeSelection:\n  mode: manual\n  selectedNodeId: " + selected + "\nroute:\n  defaultRoute: proxy\n";
    }

    static Socket connect(int localPort, int targetPort) throws Exception {
        Socket browser = new Socket("127.0.0.1", localPort);
        browser.setSoTimeout(10000);
        browser.getOutputStream().write(("CONNECT echo.test:" + targetPort + " HTTP/1.1\r\nHost: echo.test\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        StringBuilder response = new StringBuilder();
        while (!response.toString().endsWith("\r\n\r\n")) {
            int c = browser.getInputStream().read();
            assertTrue(c >= 0, "Connection closed before handshake: " + response);
            response.append((char) c);
        }
        assertTrue(response.toString().startsWith("HTTP/1.1 200"), response.toString());
        return browser;
    }

    static void echo(Socket browser, String value) throws Exception {
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        browser.getOutputStream().write(payload);
        assertArrayEquals(payload, readBytes(browser.getInputStream(), payload.length));
    }

    static byte[] readBytes(InputStream input, int length) throws Exception {
        byte[] received = new byte[length];
        int offset = 0;
        while (offset < received.length) {
            int count = input.read(received, offset, received.length - offset);
            assertTrue(count > 0);
            offset += count;
        }
        return received;
    }

    static Socket connectSocks5(int localPort, int targetPort) throws Exception {
        Socket browser = new Socket("127.0.0.1", localPort);
        browser.setSoTimeout(10000);
        browser.getOutputStream().write(new byte[]{5, 1, 0});
        assertArrayEquals(new byte[]{5, 0}, readBytes(browser.getInputStream(), 2));
        java.io.ByteArrayOutputStream request = new java.io.ByteArrayOutputStream();
        byte[] host = "echo.test".getBytes(StandardCharsets.US_ASCII);
        request.write(new byte[]{5, 1, 0, 3, (byte) host.length});
        request.write(host);
        request.write((targetPort >> 8) & 255);
        request.write(targetPort & 255);
        browser.getOutputStream().write(request.toByteArray());
        byte[] reply = readBytes(browser.getInputStream(), 10);
        assertEquals(5, reply[0]);
        assertEquals(0, reply[1]);
        echo(browser, "after-socks-handshake");
        return browser;
    }

    static void verifyPlainHttp(int localPort, int targetPort) throws Exception {
        try (Socket browser = new Socket("127.0.0.1", localPort)) {
            browser.setSoTimeout(10000);
            String headers = " HTTP/1.1\r\nHost: echo.test:" + targetPort + "\r\n\r\nbody";
            browser.getOutputStream().write(("GET http://echo.test:" + targetPort + "/plain" + headers).getBytes(StandardCharsets.US_ASCII));
            byte[] expected = ("GET /plain" + headers).getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(expected, readBytes(browser.getInputStream(), expected.length));
        }
    }

    static void awaitSessionsReleased(DispatchInvoker dispatch) throws Exception {
        // Local close is synchronous, but the peer processes QUIC stream/connection closure
        // asynchronously. No per-stream DISCONNECT acknowledgement gates switch publication.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dispatch.getSessionManager().activeCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(0, dispatch.getSessionManager().activeCount(),
                "Retired node sessions must be released within 10 seconds of local tunnel closure");
    }

    static void awaitStatus(Path config, String text) throws Exception {
        Path status = config.resolveSibling(config.getFileName() + ".status.json");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (Files.exists(status)) {
                String value = new String(Files.readAllBytes(status), StandardCharsets.UTF_8);
                boolean needsConnection = text.startsWith("\"currentNodeId\"") || text.startsWith("\"appliedRevision\"");
                if (value.contains(text) && (!needsConnection || value.contains("\"nodeStatus\":\"available\""))) return;
            }
            Thread.sleep(50);
        }
        Path processLog = config.resolveSibling("local-process.log");
        String details = Files.exists(processLog) ? new String(Files.readAllBytes(processLog), StandardCharsets.UTF_8) : "";
        fail("Runtime status did not contain " + text + "\n" + details);
    }

    static int udpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) { return socket.getLocalPort(); }
    }
    static String digest(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    static int tcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
