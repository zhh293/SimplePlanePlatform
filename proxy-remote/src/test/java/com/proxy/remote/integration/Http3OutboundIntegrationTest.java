package com.proxy.remote.integration;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.exchange.header.HeaderExchanger;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.transport.http3.Http3Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the real HTTP/3 remote outbound path, not just a mock ExchangeHandler. */
class Http3OutboundIntegrationTest {
    @Test
    void forwardsDataToOutboundTcpTargetAndPushesResponseBackAfterConnect() throws Exception {
        TestCertificates certificates = TestCertificates.create();
        EventLoopGroup targetBoss = new NioEventLoopGroup(1);
        EventLoopGroup targetWorkers = new NioEventLoopGroup(1);
        EventLoopGroup outboundWorkers = new NioEventLoopGroup(1);
        Channel targetServer = null;
        Http3Server proxyServer = null;
        ExchangeClient proxyClient = null;
        DispatchInvoker dispatch = null;
        try {
            ServerBootstrap targetBootstrap = new ServerBootstrap()
                    .group(targetBoss, targetWorkers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                                    // Force the response through OutboundSession.writeBack
                                    // and the real HTTP/3 client push path.
                                    ctx.writeAndFlush(message.retain());
                                }
                            });
                        }
                    });
            targetServer = targetBootstrap.bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) targetServer.localAddress()).getPort();

            OutboundConnector connector = new OutboundConnector(outboundWorkers, 3000);
            dispatch = new DispatchInvoker(
                    java.util.concurrent.Executors.newFixedThreadPool(2), connector, 3000);

            int http3Port;
            try (DatagramSocket socket = new DatagramSocket(0)) {
                http3Port = socket.getLocalPort();
            }
            URL serverUrl = new URL("proxy", "localhost", http3Port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.certificateFile", certificates.certificate.getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificates.privateKey.getAbsolutePath());
            proxyServer = new Http3Server(serverUrl, new ExchangeHandler(dispatch));
            proxyServer.start();

            ExtensionLoader.resetAll();
            URL clientUrl = new URL("proxy", "localhost", http3Port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
                    .addParameter("http3.caFile", certificates.ca.getAbsolutePath());
            proxyClient = new HeaderExchanger().connect(clientUrl);
            java.util.concurrent.ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry =
                    new java.util.concurrent.ConcurrentHashMap<>();
            proxyClient.setStreamRegistry(streamRegistry);

            long streamId = 901;
            Response connect = proxyClient.request(ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host("127.0.0.1")
                    .port(targetPort)
                    .streamId(streamId)
                    .build(), 5000).get(10, TimeUnit.SECONDS);
            assertTrue(connect.isSuccess(), "CONNECT should establish outbound TCP session");

            byte[] payload = "http3-outbound-payload".getBytes(StandardCharsets.UTF_8);
            BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
            EmbeddedChannel pushCapture = createPushCapture(streamId, pushQueue, streamRegistry);
            proxyClient.send(ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .host("127.0.0.1")
                    .port(targetPort)
                    .streamId(streamId)
                    .data(payload)
                    .build());

            byte[] pushed = pushQueue.poll(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, pushed,
                    "outbound TCP response should be pushed back over HTTP/3");
            pushCapture.finishAndReleaseAll();
        } finally {
            if (proxyClient != null) proxyClient.close();
            if (proxyServer != null) proxyServer.close();
            if (dispatch != null) dispatch.shutdown();
            if (targetServer != null) targetServer.close();
            outboundWorkers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            targetBoss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            targetWorkers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            certificates.delete();
        }
    }

    private EmbeddedChannel createPushCapture(long streamId, BlockingQueue<byte[]> pushQueue,
                                              java.util.concurrent.ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) msg;
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    buf.release();
                    pushQueue.offer(data);
                    promise.setSuccess();
                } else {
                    ctx.write(msg, promise);
                }
            }
        }, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
            }
        });
        streamRegistry.put(streamId, channel.pipeline().lastContext());
        return channel;
    }

    private static final class TestCertificates {
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
            Path directory = Files.createTempDirectory("http3-remote-test-tls");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair caKey = generator.generateKeyPair();
            KeyPair serverKey = generator.generateKeyPair();
            Date notBefore = new Date(System.currentTimeMillis() - 60_000);
            Date notAfter = new Date(System.currentTimeMillis() + 86_400_000);
            X500Name caName = new X500Name("CN=SimplePlane Test CA");
            X500Name serverName = new X500Name("CN=localhost");
            X509Certificate caCert = certificate(caName, caName, caKey, caKey,
                    notBefore, notAfter, true);
            X509Certificate serverCert = certificate(caName, serverName, caKey, serverKey,
                    notBefore, notAfter, false);
            java.io.File certificate = directory.resolve("server-chain.pem").toFile();
            java.io.File privateKey = directory.resolve("server-key.pem").toFile();
            java.io.File ca = directory.resolve("ca.pem").toFile();
            writePem(certificate, serverCert, caCert);
            writePem(privateKey, serverKey.getPrivate());
            writePem(ca, caCert);
            return new TestCertificates(directory, certificate, privateKey, ca);
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

        private static void writePem(java.io.File file, Object... values) throws IOException {
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.US_ASCII);
                 JcaPEMWriter pem = new JcaPEMWriter(writer)) {
                for (Object value : values) pem.writeObject(value);
            }
        }

        private void delete() throws IOException {
            Files.deleteIfExists(certificate.toPath());
            Files.deleteIfExists(privateKey.toPath());
            Files.deleteIfExists(ca.toPath());
            Files.deleteIfExists(directory);
        }
    }
}
