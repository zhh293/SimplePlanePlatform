package com.proxy.transport.http3;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.MessageHandler;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.TransportException;
import com.proxy.transport.http3.handler.Http3CipherDecodeHandler;
import com.proxy.transport.http3.handler.Http3CipherEncodeHandler;
import com.proxy.transport.http3.handler.Http3ProxyMessageDecoder;
import com.proxy.transport.http3.handler.Http3ProxyMessageEncoder;
import com.proxy.transport.http3.handler.Http3StreamHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.ClientAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import io.netty.util.AttributeKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** QUIC/HTTP3 UDP server that installs the existing ExchangeHandler on every request stream. */
public final class Http3Server implements Server {
    private static final Logger log = LoggerFactory.getLogger(Http3Server.class);

    private final URL url;
    private final ChannelHandler exchangeHandler;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicInteger connections = new AtomicInteger();
    private EventLoopGroup eventLoop;
    private Channel datagramChannel;
    private static final AttributeKey<Boolean> DEVICE_AUTHENTICATED =
            AttributeKey.valueOf(Http3Server.class, "deviceAuthenticated");
    private String subscriptionKey;

    public Http3Server(URL url, ChannelHandler exchangeHandler) {
        this.url = url;
        this.exchangeHandler = exchangeHandler;
    }

    @Override
    public void start() throws TransportException {
        if (active.get()) {
            return;
        }
        try {
            String keyFile = url.getParameters().get("http3.subscriptionFrameKeyFile");
            if (keyFile != null) {
                subscriptionKey = Files.readString(Path.of(keyFile)).trim();
                if (!subscriptionKey.matches("[0-9a-fA-F]{32}") ||
                        subscriptionKey.equals(url.getParameter("cipherKey", "")) ||
                        !url.getParameters().containsKey("http3.deviceCaFile") ||
                        !"chacha20".equals(url.getParameter("cipher", ""))) {
                    throw new IllegalArgumentException("Invalid shared listener key configuration");
                }
            }
            QuicSslContext sslContext = createServerSslContext();
            eventLoop = new NioEventLoopGroup(url.getParameter("workerThreads", 0));
            ChannelInitializer<QuicChannel> connectionInitializer = new ChannelInitializer<QuicChannel>() {
                @Override
                protected void initChannel(QuicChannel channel) {
                    int maxConnections = url.getParameter("http3.maxConnections", 10000);
                    if (connections.incrementAndGet() > maxConnections) {
                        connections.decrementAndGet();
                        channel.close();
                        return;
                    }
                    channel.closeFuture().addListener(f -> connections.decrementAndGet());
                    if (url.getParameters().containsKey("http3.deviceCaFile")) {
                        channel.pipeline().addLast("device-expiry", new DeviceCertificateExpiry(subscriptionKey != null));
                    }
                    channel.pipeline().addLast("http3-connection", new Http3ServerConnectionHandler(
                            new ChannelInitializer<io.netty.handler.codec.quic.QuicStreamChannel>() {
                                @Override
                                protected void initChannel(io.netty.handler.codec.quic.QuicStreamChannel stream) {
                                    installStreamPipeline(stream);
                                }
                            }));
                }
            };
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(eventLoop).channel(NioDatagramChannel.class)
                    .handler(Http3.newQuicServerCodecBuilder()
                            .sslContext(sslContext)
                            .maxIdleTimeout(url.getParameter("http3.idleTimeoutMs", 60000), TimeUnit.MILLISECONDS)
                            .initialMaxData(url.getParameter("http3.initialConnectionWindow", 16777216L))
                            .initialMaxStreamDataBidirectionalLocal(
                                    url.getParameter("http3.initialStreamWindow", 1048576L))
                            .initialMaxStreamDataBidirectionalRemote(
                                    url.getParameter("http3.initialStreamWindow", 1048576L))
                            .initialMaxStreamsBidirectional(
                                    url.getParameter("http3.maxStreamsPerConnection", 1000))
                            .handler(connectionInitializer)
                            .build());
            datagramChannel = bootstrap.bind(url.getHost(), url.getPort()).sync().channel();
            active.set(true);
            log.info("Http3Server started on UDP {}:{} (activeStreams={}, cipher={})", url.getHost(),
                    url.getPort(), url.getParameter("http3.maxStreamsPerConnection", 1000),
                    url.getParameter("cipher", "none"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new TransportException("Interrupted while binding HTTP/3 UDP " + url.getAddress(), e);
        } catch (Exception e) {
            close();
            throw new TransportException("Failed to bind HTTP/3 UDP " + url.getAddress(), e);
        }
    }

    private void installStreamPipeline(io.netty.handler.codec.quic.QuicStreamChannel stream) {
        boolean deviceCipher = false;
        if (subscriptionKey != null) {
            Boolean authenticated = stream.parent().attr(DEVICE_AUTHENTICATED).get();
            if (authenticated == null) {
                stream.close();
                return;
            }
            deviceCipher = authenticated;
        }
        Cipher cipher = createCipher(deviceCipher);
        int maxMessage = url.getParameter("http3.maxProxyMessageBytes", 8388608);
        stream.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(
                        url.getParameter("http3.writeBufferLowWaterMark", 262144),
                        url.getParameter("http3.writeBufferHighWaterMark", 1048576)));
        stream.pipeline().addLast("h3-stream", new Http3StreamHandler(true, url, null, null, null));
        stream.pipeline().addLast("h3-cipher-decode", new Http3CipherDecodeHandler(cipher, maxMessage + 65536));
        stream.pipeline().addLast("h3-decoder", new Http3ProxyMessageDecoder(
                ExtensionLoader.getLoader(com.proxy.common.codec.Codec.class).getDefaultExtension(), maxMessage));
        stream.pipeline().addLast("h3-cipher-encode", new Http3CipherEncodeHandler(cipher, maxMessage + 65536));
        stream.pipeline().addLast("h3-encoder", new Http3ProxyMessageEncoder(
                ExtensionLoader.getLoader(com.proxy.common.codec.Codec.class).getDefaultExtension(), maxMessage));
        stream.pipeline().addLast("exchange-handler", exchangeHandler);
    }

    private QuicSslContext createServerSslContext() throws Exception {
        String certificate = url.getParameter("http3.certificateFile", "");
        String privateKey = url.getParameter("http3.privateKeyFile", "");
        if (certificate.isEmpty() || privateKey.isEmpty()) {
            throw new IllegalArgumentException("HTTP/3 requires http3.certificateFile and http3.privateKeyFile");
        }
        File certificateFile = new File(certificate);
        File privateKeyFile = new File(privateKey);
        if (!certificateFile.isFile() || !privateKeyFile.isFile()) {
            throw new IllegalArgumentException("HTTP/3 certificate/private key is not readable");
        }
        // Netty's File overload is (privateKeyFile, password, certificateChainFile).
        QuicSslContextBuilder builder = QuicSslContextBuilder.forServer(privateKeyFile, null, certificateFile)
                .applicationProtocols(Http3.supportedApplicationProtocols());
        String deviceCa = url.getParameters().get("http3.deviceCaFile");
        if (deviceCa != null) {
            builder.trustManager(new File(deviceCa))
                    .clientAuth(subscriptionKey == null ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL).earlyData(false);
        }
        return builder.build();
    }

    private static final class DeviceCertificateExpiry extends ChannelInboundHandlerAdapter {
        private final boolean allowLegacy;

        DeviceCertificateExpiry(boolean allowLegacy) {
            this.allowLegacy = allowLegacy;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            QuicChannel channel = (QuicChannel) ctx.channel();
            try {
                X509Certificate certificate = (X509Certificate)
                        channel.sslEngine().getSession().getPeerCertificates()[0];
                certificate.checkValidity();
                DeviceIdentity.record(channel, certificate);
                channel.attr(DEVICE_AUTHENTICATED).set(true);
                long remaining = certificate.getNotAfter().getTime() - System.currentTimeMillis();
                io.netty.util.concurrent.ScheduledFuture<?> expiry = ctx.executor().schedule(
                        (Runnable) channel::close, Math.max(0, remaining), TimeUnit.MILLISECONDS);
                channel.closeFuture().addListener(future -> expiry.cancel(false));
            } catch (javax.net.ssl.SSLPeerUnverifiedException noPeerCertificate) {
                if (!allowLegacy) {
                    channel.close();
                    return;
                }
                channel.attr(DEVICE_AUTHENTICATED).set(false);
            } catch (java.security.cert.CertificateException invalid) {
                channel.close();
                return;
            }
            ctx.fireChannelActive();
        }
    }

    private Cipher createCipher(boolean deviceCipher) {
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class)
                .newExtension(deviceCipher ? "aes-gcm-v2" : url.getParameter("cipher", "none"));
        String key = deviceCipher ? subscriptionKey : url.getParameter("cipherKey", "");
        CipherConfig config = new CipherConfig(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        config.setServerSide(true);
        cipher.init(config);
        return cipher;
    }

    @Override
    public void close() {
        boolean wasActive = active.compareAndSet(true, false);
        if (datagramChannel != null) {
            datagramChannel.close().syncUninterruptibly();
        }
        if (eventLoop != null) {
            eventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (wasActive) {
            log.info("Http3Server stopped on UDP {}:{}", url.getHost(), url.getPort());
        }
    }

    @Override
    public boolean isActive() {
        return active.get() && datagramChannel != null && datagramChannel.isActive();
    }

    @Override
    public int getActiveConnectionCount() {
        return connections.get();
    }

    @Override
    public String getBindAddress() {
        return url.getHost();
    }

    @Override
    public int getBindPort() {
        return url.getPort();
    }
}
