package com.proxy.transport.http3;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.exchange.header.HeaderExchanger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real exchange layer across a control request followed by DATA. */
class Http3ExchangeIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"none", "chacha20-poly1305-v2", "aes-gcm-v2"})
    void independentClientsShareBrowserRegistryWithoutCrossConnectionCleanup(String suite) throws Exception {
        Http3LoopbackIntegrationTest.TestCertificates certificate =
                Http3LoopbackIntegrationTest.TestCertificates.create();
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        URL serverUrl = new URL("proxy", "localhost", port).addParameter("transport", "http3")
                .addParameter("cipher", suite).addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
        Http3Server server = new Http3Server(serverUrl,
                new ExchangeHandler(invocation -> CompletableFuture.completedFuture(Response.ok())));
        ExchangeClient a = null, b = null;
        io.netty.channel.embedded.EmbeddedChannel browserA = new io.netty.channel.embedded.EmbeddedChannel(
                new ChannelInboundHandlerAdapter());
        io.netty.channel.embedded.EmbeddedChannel browserB = new io.netty.channel.embedded.EmbeddedChannel(
                new ChannelInboundHandlerAdapter());
        try {
            server.start();
            URL clientUrl = new URL("proxy", "localhost", port).addParameter("transport", "http3")
                    .addParameter("cipher", suite).addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile", certificate.ca().getAbsolutePath());
            a = new HeaderExchanger().connect(clientUrl);
            b = new HeaderExchanger().connect(clientUrl);
            java.util.concurrent.ConcurrentHashMap<Long, ChannelHandlerContext> registry =
                    new java.util.concurrent.ConcurrentHashMap<>();
            registry.put(701L, browserA.pipeline().firstContext());
            registry.put(702L, browserB.pipeline().firstContext());
            a.setStreamRegistry(registry);
            b.setStreamRegistry(registry);
            CompletableFuture<Response> connectA = a.request(ProxyMessage.builder().streamId(701)
                    .type(ProxyMessage.MessageType.CONNECT).host("example.com").port(443).build(), 5000);
            CompletableFuture<Response> connectB = b.request(ProxyMessage.builder().streamId(702)
                    .type(ProxyMessage.MessageType.CONNECT).host("example.com").port(443).build(), 5000);
            assertTrue(connectA.get(10, TimeUnit.SECONDS).isSuccess());
            assertTrue(connectB.get(10, TimeUnit.SECONDS).isSuccess());
            a.close();
            browserA.runPendingTasks();
            org.junit.jupiter.api.Assertions.assertFalse(browserA.isActive());
            assertTrue(browserB.isActive());
            assertTrue(b.isAvailable());
            ExchangeClient currentB = b;
            assertEquals(1, b.getClient().getActiveStreamCount());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> currentB.send(
                    ProxyMessage.builder().streamId(701).type(ProxyMessage.MessageType.DATA)
                            .data(new byte[]{42}).build()));
            org.junit.jupiter.api.Assertions.assertFalse(b.request(ProxyMessage.builder().streamId(701)
                    .type(ProxyMessage.MessageType.DISCONNECT).build(), 5000)
                    .get(10, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, b.getClient().getActiveStreamCount(), "Old events must not create a new stream");
            assertTrue(browserB.isActive(), "Rejecting an old stream must not close B's browser");
            assertTrue(registry.containsKey(702L));
            assertEquals(0, com.proxy.exchange.header.DefaultFuture.getActiveFutureCount());
            assertTrue(b.request(ProxyMessage.builder().streamId(702)
                    .type(ProxyMessage.MessageType.DISCONNECT).build(), 5000)
                    .get(10, TimeUnit.SECONDS).isSuccess());
        } finally {
            if (a != null) a.close();
            if (b != null) b.close();
            server.close();
            browserA.finishAndReleaseAll();
            browserB.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "chacha20-poly1305-v2", "aes-gcm-v2"})
    void exchangeHandlerAcceptsDataAfterConnectOnTheSameHttp3Stream(String suite) throws Exception {
        Http3LoopbackIntegrationTest.TestCertificates certificate =
                Http3LoopbackIntegrationTest.TestCertificates.create();
        Http3Server server = null;
        ExchangeClient client = null;
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }

        CountDownLatch dataReceived = new CountDownLatch(1);
        AtomicReference<ProxyMessage.MessageType> receivedType = new AtomicReference<>();
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        Invoker invoker = invocation -> {
            receivedType.set(invocation.getType());
            receivedData.set(invocation.getData());
            if (invocation.getType() == ProxyMessage.MessageType.DATA) {
                dataReceived.countDown();
            }
            return CompletableFuture.completedFuture(Response.ok());
        };

        try {
            URL serverUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
            server = new Http3Server(serverUrl, new ExchangeHandler(invoker));
            server.start();

            URL clientUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile", certificate.ca().getAbsolutePath());
            client = new HeaderExchanger().connect(clientUrl);

            ProxyMessage connect = ProxyMessage.builder()
                    .streamId(77)
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host("example.com")
                    .port(443)
                    .build();
            Response response = client.request(connect, 5000).get(10, TimeUnit.SECONDS);
            assertTrue(response.isSuccess(), "CONNECT response should succeed");

            byte[] payload = "data-after-connect".getBytes(StandardCharsets.UTF_8);
            client.send(ProxyMessage.builder()
                    .streamId(77)
                    .type(ProxyMessage.MessageType.DATA)
                    .host("example.com")
                    .port(443)
                    .data(payload)
                    .build());

            assertTrue(dataReceived.await(5, TimeUnit.SECONDS),
                    "server ExchangeHandler did not receive DATA after CONNECT");
            assertEquals(ProxyMessage.MessageType.DATA, receivedType.get());
            assertNotNull(receivedData.get());
            assertEquals(new String(payload, StandardCharsets.UTF_8),
                    new String(receivedData.get(), StandardCharsets.UTF_8));
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
}
