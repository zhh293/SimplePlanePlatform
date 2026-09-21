package com.proxy.transport.http3;

import com.proxy.common.model.URL;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One QUIC connection. HTTP/3 request streams are created on demand. */
public final class Http3Connection {
    private static final Logger log = LoggerFactory.getLogger(Http3Connection.class);

    private final URL url;
    private final EventLoopGroup eventLoop;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final QuicSslContext sslContext;
    private volatile Channel datagramChannel;
    private volatile QuicChannel quicChannel;

    public Http3Connection(URL url) {
        this.url = url;
        this.eventLoop = new NioEventLoopGroup(url.getParameter("ioThreads", 0));
        try {
            this.sslContext = createClientSslContext(url);
        } catch (Exception e) {
            eventLoop.shutdownGracefully();
            throw new IllegalArgumentException("Failed to initialize HTTP/3 TLS context", e);
        }
    }

    public synchronized void connect() throws Exception {
        if (isActive()) {
            return;
        }
        // Netty's QUIC builder calls native setters immediately after creating
        // its config. On affected Windows quiche builds, config allocation
        // returns -1 and the next setter crashes the JVM in native code.
        // Fail safely before entering that builder path.
        io.netty.handler.codec.quic.QuicheNativePreflight.ensureConfigCanBeCreated();
        Bootstrap bootstrap = new Bootstrap();
        String serverName = url.getParameter("http3.serverName", url.getHost());
        bootstrap.group(eventLoop).channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(Http3.newQuicClientCodecBuilder()
                // Use the configured DNS name for both SNI and hostname verification.
                // InetSocketAddress may resolve localhost to an IP before Netty creates
                // the QUIC TLS engine, which would otherwise make a valid certificate
                // for the configured server name fail verification.
                .sslEngineProvider(channel ->
                        sslContext.newEngine(channel.alloc(), serverName, url.getPort()))
                .maxIdleTimeout(url.getParameter("http3.idleTimeoutMs", 60000), TimeUnit.MILLISECONDS)
                .initialMaxData(url.getParameter("http3.initialConnectionWindow", 16777216L))
                .initialMaxStreamDataBidirectionalLocal(
                        url.getParameter("http3.initialStreamWindow", 1048576L))
                .initialMaxStreamDataBidirectionalRemote(
                        url.getParameter("http3.initialStreamWindow", 1048576L))
                .initialMaxStreamsBidirectional(url.getParameter("http3.maxStreams", 1000))
                .build());
        datagramChannel = bootstrap.bind(0).sync().channel();
        QuicChannelBootstrapHelper helper = new QuicChannelBootstrapHelper(datagramChannel, url, sslContext);
        quicChannel = helper.connect();
        quicChannel.closeFuture().addListener(f -> {
            log.warn("HTTP/3 QUIC connection closed: {}", url.getAddress());
            if (datagramChannel != null) {
                datagramChannel.close();
            }
        });
        log.info("HTTP/3 QUIC handshake established to {}:{}", url.getHost(), url.getPort());
    }

    private QuicSslContext createClientSslContext(URL url) throws Exception {
        QuicSslContextBuilder builder = QuicSslContextBuilder.forClient()
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .endpointIdentificationAlgorithm("HTTPS");
        String caFile = url.getParameter("http3.caFile", "");
        if (!caFile.isEmpty()) {
            try (InputStream ignored = openCaStream(caFile)) {
                // The CA may be an external file or a classpath resource.  The
                // latter is important for the IDE and shaded local client.
            }
        }
        String certificatePin = url.getParameter("http3.certificatePin", "");
        if (!caFile.isEmpty() || !certificatePin.isEmpty()) {
            builder.trustManager(new PinningTrustManager(loadTrustManager(caFile), certificatePin));
        }
        return builder.build();
    }

    private static X509TrustManager loadTrustManager(String caFile) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        if (caFile == null || caFile.isEmpty()) {
            factory.init((KeyStore) null);
        } else {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            try (InputStream input = openCaStream(caFile)) {
                Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(input);
                int index = 0;
                for (Certificate certificate : certificates) {
                    keyStore.setCertificateEntry("http3-ca-" + index++, certificate);
                }
                if (index == 0) {
                    throw new IllegalArgumentException("HTTP/3 caFile contains no certificates: " + caFile);
                }
            }
            factory.init(keyStore);
        }
        for (TrustManager trustManager : factory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        throw new IllegalStateException("No X509TrustManager available for HTTP/3 TLS");
    }

    private static InputStream openCaStream(String caFile) throws Exception {
        File file = new File(caFile);
        if (file.isFile()) {
            return new java.io.FileInputStream(file);
        }
        InputStream resource = Http3Connection.class.getClassLoader().getResourceAsStream(caFile);
        if (resource != null) {
            return resource;
        }
        throw new IllegalArgumentException("HTTP/3 caFile is not readable as file or classpath resource: " + caFile);
    }

    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;
        private final byte[] expectedSpkiSha256;

        private PinningTrustManager(X509TrustManager delegate, String pin) {
            this.delegate = delegate;
            this.expectedSpkiSha256 = decodePin(pin);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws java.security.cert.CertificateException {
            delegateClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws java.security.cert.CertificateException {
            delegateClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyPin(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws java.security.cert.CertificateException {
            delegateServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws java.security.cert.CertificateException {
            delegateServerTrusted(chain, authType, engine);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private void delegateClientTrusted(X509Certificate[] chain, String authType, Object transport)
                throws java.security.cert.CertificateException {
            if (delegate instanceof X509ExtendedTrustManager) {
                X509ExtendedTrustManager extended = (X509ExtendedTrustManager) delegate;
                if (transport instanceof SSLEngine) {
                    extended.checkClientTrusted(chain, authType, (SSLEngine) transport);
                } else {
                    extended.checkClientTrusted(chain, authType, (Socket) transport);
                }
            } else {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        private void delegateServerTrusted(X509Certificate[] chain, String authType, Object transport)
                throws java.security.cert.CertificateException {
            if (delegate instanceof X509ExtendedTrustManager) {
                X509ExtendedTrustManager extended = (X509ExtendedTrustManager) delegate;
                if (transport instanceof SSLEngine) {
                    extended.checkServerTrusted(chain, authType, (SSLEngine) transport);
                } else {
                    extended.checkServerTrusted(chain, authType, (Socket) transport);
                }
            } else {
                delegate.checkServerTrusted(chain, authType);
            }
            verifyPin(chain);
        }

        private void verifyPin(X509Certificate[] chain)
                throws java.security.cert.CertificateException {
            if (expectedSpkiSha256 == null) {
                return;
            }
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("HTTP/3 server certificate chain is empty");
            }
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256")
                        .digest(chain[0].getPublicKey().getEncoded());
                if (!MessageDigest.isEqual(expectedSpkiSha256, actual)) {
                    throw new java.security.cert.CertificateException("HTTP/3 certificate SPKI pin mismatch");
                }
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new java.security.cert.CertificateException("SHA-256 is unavailable", impossible);
            }
        }

        private static byte[] decodePin(String configured) {
            if (configured == null || configured.trim().isEmpty()) {
                return null;
            }
            String value = configured.trim();
            if (value.startsWith("sha256/")) {
                value = value.substring("sha256/".length());
            }
            try {
                if (value.matches("[0-9a-fA-F]{64}")) {
                    byte[] result = new byte[32];
                    for (int i = 0; i < result.length; i++) {
                        result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
                    }
                    return result;
                }
                byte[] result = Base64.getDecoder().decode(value);
                if (result.length != 32) {
                    throw new IllegalArgumentException("HTTP/3 certificatePin must be a SHA-256 SPKI hash");
                }
                return result;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "HTTP/3 certificatePin must be sha256/<base64> or 64-char hex", e);
            }
        }
    }

    public Future<QuicStreamChannel> openStream(ChannelInitializer<QuicStreamChannel> initializer) {
        QuicChannel parent = quicChannel;
        if (closed.get() || parent == null || !parent.isActive()) {
            io.netty.util.concurrent.Promise<QuicStreamChannel> promise = eventLoop.next().newPromise();
            promise.setFailure(new IllegalStateException("HTTP/3 QUIC connection is not available"));
            return promise;
        }
        return Http3.newRequestStreamBootstrap(parent, initializer).type(QuicStreamType.BIDIRECTIONAL).create();
    }

    public boolean isActive() {
        QuicChannel current = quicChannel;
        return !closed.get() && current != null && current.isActive();
    }

    public QuicChannel channel() {
        return quicChannel;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            QuicChannel current = quicChannel;
            if (current != null) {
                current.close();
            }
            if (datagramChannel != null) {
                datagramChannel.close();
            }
            eventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static final class QuicChannelBootstrapHelper {
        private final Channel datagramChannel;
        private final URL url;
        private final QuicSslContext sslContext;

        private QuicChannelBootstrapHelper(Channel datagramChannel, URL url, QuicSslContext sslContext) {
            this.datagramChannel = datagramChannel;
            this.url = url;
            this.sslContext = sslContext;
        }

        private QuicChannel connect() throws Exception {
            io.netty.handler.codec.quic.QuicChannelBootstrap bootstrap = QuicChannel.newBootstrap(datagramChannel)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel channel) {
                            channel.pipeline().addLast("http3", new Http3ClientConnectionHandler());
                        }
                    })
                    .remoteAddress(new InetSocketAddress(url.getHost(), url.getPort()));
            Future<QuicChannel> future = bootstrap.connect();
            long timeoutMs = url.getParameter("http3.handshakeTimeoutMs", 10000L);
            if (!future.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                future.cancel(false);
                throw new TimeoutException("HTTP/3 QUIC handshake timed out after " + timeoutMs + " ms");
            }
            if (!future.isSuccess()) {
                throw new IllegalStateException("HTTP/3 QUIC handshake failed", future.cause());
            }
            return future.getNow();
        }
    }
}
