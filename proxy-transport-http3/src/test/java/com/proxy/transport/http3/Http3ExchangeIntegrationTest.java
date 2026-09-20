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
    @Test
    void exchangeHandlerAcceptsDataAfterConnectOnTheSameHttp3Stream() throws Exception {
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
                    .addParameter("cipher", "none")
                    .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
            server = new Http3Server(serverUrl, new ExchangeHandler(invoker));
            server.start();

            URL clientUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", "none")
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
