package com.proxy.transport.http3;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.MessageHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native QUIC/TLS loopback smoke test. */
class Http3LoopbackIntegrationTest {
    @Test
    void clientAndServerExchangeProxyMessageOverUdp() throws Exception {
        TestCertificates certificate = TestCertificates.create();
        Http3Server server = null;
        Http3Client client = null;
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            CapturingHandler clientHandler = new CapturingHandler();
            EchoHandler serverHandler = new EchoHandler();

            URL serverUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
            server = new Http3Server(serverUrl, serverHandler);
            server.start();

            URL clientUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.caFile", certificate.ca().getAbsolutePath());
            client = new Http3Client(clientUrl, clientHandler);
            client.send(ProxyMessage.builder()
                    .streamId(42)
                    .type(ProxyMessage.MessageType.DATA)
                    .data("quic-loopback".getBytes(StandardCharsets.UTF_8))
                    .build());

            assertTrue(clientHandler.messages.await(10, TimeUnit.SECONDS), "HTTP/3 response not received");
            assertArrayEquals("quic-loopback".getBytes(StandardCharsets.UTF_8),
                    clientHandler.message.get().getData());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            certificate.delete();
        }
    }

    /**
     * Regression test for cross-stream head-of-line blocking.
     *
     * <p>The slow stream is deliberately held for three seconds after the
     * server has received it. Fifty independent streams are then sent and
     * must complete before the slow stream is released. This is deterministic
     * and exercises the important HTTP/3 property without depending on an
     * operating-system-specific packet-loss tool.</p>
     */
    @Test
    void delayedStreamDoesNotBlockIndependentStreams() throws Exception {
        TestCertificates certificate = TestCertificates.create();
        DelayedStreamHandler serverHandler = new DelayedStreamHandler(1000L, 3, TimeUnit.SECONDS);
        StreamCapturingHandler clientHandler = new StreamCapturingHandler(1000L, 50);
        Http3Server server = null;
        Http3Client client = null;
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            URL serverUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
            server = new Http3Server(serverUrl, serverHandler);
            server.start();

            URL clientUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.caFile", certificate.ca().getAbsolutePath());
            client = new Http3Client(clientUrl, clientHandler);

            client.send(ProxyMessage.builder()
                    .streamId(1000L)
                    .type(ProxyMessage.MessageType.DATA)
                    .data("slow-stream".getBytes(StandardCharsets.UTF_8))
                    .build());
            assertTrue(serverHandler.slowStreamObserved.await(5, TimeUnit.SECONDS),
                    "Slow stream was not received by the server");

            long fastStart = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                client.send(ProxyMessage.builder()
                        .streamId(2000L + i)
                        .type(ProxyMessage.MessageType.DATA)
                        .data(("fast-stream-" + i).getBytes(StandardCharsets.UTF_8))
                        .build());
            }

            assertTrue(clientHandler.fastResponses.await(2, TimeUnit.SECONDS),
                    "Independent HTTP/3 streams were blocked by the delayed stream");
            long fastElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fastStart);
            assertTrue(fastElapsedMs < 2000,
                    "Independent streams took too long: " + fastElapsedMs + " ms");

            assertTrue(clientHandler.slowResponse.await(5, TimeUnit.SECONDS),
                    "Delayed stream did not eventually receive its response");
            assertTrue(clientHandler.messages.containsKey(1000L));
            assertTrue(clientHandler.messages.size() >= 51,
                    "Expected all stream responses, got " + clientHandler.messages.size());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            serverHandler.close();
            certificate.delete();
        }
    }

    private static final class EchoHandler extends ChannelInboundHandlerAdapter implements MessageHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ProxyMessage) {
                ctx.writeAndFlush(message);
            } else {
                ctx.fireChannelRead(message);
            }
        }

        @Override public void onMessage(ProxyMessage message) { }
        @Override public void onError(Throwable cause) { }
        @Override public void onDisconnected() { }
    }

    private static final class CapturingHandler extends ChannelInboundHandlerAdapter implements MessageHandler {
        private final CountDownLatch messages = new CountDownLatch(1);
        private final AtomicReference<ProxyMessage> message = new AtomicReference<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object value) {
            if (value instanceof ProxyMessage) {
                message.set((ProxyMessage) value);
                messages.countDown();
            } else {
                ctx.fireChannelRead(value);
            }
        }

        @Override public void onMessage(ProxyMessage message) { }
        @Override public void onError(Throwable cause) { }
        @Override public void onDisconnected() { }
    }

    @io.netty.channel.ChannelHandler.Sharable
    private static final class DelayedStreamHandler extends ChannelInboundHandlerAdapter implements MessageHandler {
        private final long slowStreamId;
        private final long delay;
        private final TimeUnit unit;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "http3-hol-test-delay");
            thread.setDaemon(true);
            return thread;
        });
        private final CountDownLatch slowStreamObserved = new CountDownLatch(1);

        private DelayedStreamHandler(long slowStreamId, long delay, TimeUnit unit) {
            this.slowStreamId = slowStreamId;
            this.delay = delay;
            this.unit = unit;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof ProxyMessage)) {
                ctx.fireChannelRead(message);
                return;
            }
            ProxyMessage proxyMessage = (ProxyMessage) message;
            if (proxyMessage.getStreamId() == slowStreamId) {
                slowStreamObserved.countDown();
                scheduler.schedule(() -> ctx.writeAndFlush(proxyMessage), delay, unit);
            } else {
                ctx.writeAndFlush(proxyMessage);
            }
        }

        private void close() {
            scheduler.shutdownNow();
        }

        @Override public void onMessage(ProxyMessage message) { }
        @Override public void onError(Throwable cause) { }
        @Override public void onDisconnected() { }
    }

    @io.netty.channel.ChannelHandler.Sharable
    private static final class StreamCapturingHandler extends ChannelInboundHandlerAdapter implements MessageHandler {
        private final long slowStreamId;
        private final CountDownLatch fastResponses;
        private final CountDownLatch slowResponse = new CountDownLatch(1);
        private final Map<Long, ProxyMessage> messages = new ConcurrentHashMap<>();

        private StreamCapturingHandler(long slowStreamId, int fastStreamCount) {
            this.slowStreamId = slowStreamId;
            this.fastResponses = new CountDownLatch(fastStreamCount);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof ProxyMessage)) {
                ctx.fireChannelRead(message);
                return;
            }
            ProxyMessage proxyMessage = (ProxyMessage) message;
            messages.put(proxyMessage.getStreamId(), proxyMessage);
            if (proxyMessage.getStreamId() == slowStreamId) {
                slowResponse.countDown();
            } else {
                fastResponses.countDown();
            }
        }

        @Override public void onMessage(ProxyMessage message) { }
        @Override public void onError(Throwable cause) { }
        @Override public void onDisconnected() { }
    }

    static final class TestCertificates {
        private final Path directory;
        private final java.io.File certificate;
        private final java.io.File privateKey;
        private final java.io.File ca;

        private TestCertificates(Path directory, java.io.File certificate,
                                 java.io.File privateKey, java.io.File ca) {
            this.directory = directory;
            this.certificate = certificate;
            this.privateKey = privateKey;
            this.ca = ca;
        }

        static TestCertificates create() throws Exception {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            Path directory = Files.createTempDirectory("http3-test-tls");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair caKey = generator.generateKeyPair();
            KeyPair serverKey = generator.generateKeyPair();
            Date notBefore = new Date(System.currentTimeMillis() - 60_000);
            Date notAfter = new Date(System.currentTimeMillis() + 86_400_000);
            X500Name caName = new X500Name("CN=SimplePlane Test CA");
            X500Name serverName = new X500Name("CN=localhost");
            X509Certificate caCert = certificate(caName, caName, caKey, caKey, notBefore, notAfter, true);
            X509Certificate serverCert = certificate(caName, serverName, caKey, serverKey,
                    notBefore, notAfter, false);

            java.io.File certFile = directory.resolve("server-chain.pem").toFile();
            java.io.File keyFile = directory.resolve("server-key.pem").toFile();
            java.io.File caFile = directory.resolve("ca.pem").toFile();
            writePem(certFile, serverCert, caCert);
            writePem(keyFile, serverKey.getPrivate());
            writePem(caFile, caCert);
            return new TestCertificates(directory, certFile, keyFile, caFile);
        }

        private static X509Certificate certificate(X500Name issuer, X500Name subject,
                                                   KeyPair issuerKey, KeyPair subjectKey,
                                                   Date notBefore, Date notAfter, boolean ca) throws Exception {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter,
                    subject, subjectKey.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(Extension.keyUsage, false,
                    new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                            : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            if (!ca) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
            }
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(issuerKey.getPrivate());
            return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
        }

        private static void writePem(java.io.File file, Object... objects) throws IOException {
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.US_ASCII);
                 JcaPEMWriter pem = new JcaPEMWriter(writer)) {
                for (Object object : objects) {
                    pem.writeObject(object);
                }
            }
        }

        java.io.File certificate() { return certificate; }
        java.io.File privateKey() { return privateKey; }
        java.io.File ca() { return ca; }

        void delete() throws IOException {
            Files.deleteIfExists(certificate.toPath());
            Files.deleteIfExists(privateKey.toPath());
            Files.deleteIfExists(ca.toPath());
            Files.deleteIfExists(directory);
        }
    }
}
