package com.proxy.transport.http3;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.exchange.header.DefaultFuture;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.exchange.header.HeaderExchanger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.DatagramSocket;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual QUIC cancellation with a delayed remote response and a healthy independent client. */
class Http3PendingConnectLifecycleTest {
    @ParameterizedTest
    @CsvSource({"none,client", "none,browser", "none,timeout",
            "chacha20-poly1305-v2,client", "chacha20-poly1305-v2,browser", "chacha20-poly1305-v2,timeout",
            "aes-gcm-v2,client", "aes-gcm-v2,browser", "aes-gcm-v2,timeout"})
    void pendingConnectCancellationDoesNotReviveOrAffectAnotherClient(String cipher, String cancellation) throws Exception {
        Http3LoopbackIntegrationTest.TestCertificates certificate = Http3LoopbackIntegrationTest.TestCertificates.create();
        CompletableFuture<Response> lateResponse = new CompletableFuture<>();
        CountDownLatch arrived = new CountDownLatch(1), healthyData = new CountDownLatch(1);
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        URL serverUrl = new URL("proxy", "localhost", port).addParameter("transport", "http3")
                .addParameter("cipher", cipher).addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                .addParameter("http3.certificateFile", certificate.certificate().getAbsolutePath())
                .addParameter("http3.privateKeyFile", certificate.privateKey().getAbsolutePath());
        Http3Server server = new Http3Server(serverUrl, new ExchangeHandler(invocation -> {
            if ("801".equals(String.valueOf(invocation.getAttachment("streamId")))
                    && invocation.getType() == ProxyMessage.MessageType.CONNECT) {
                arrived.countDown();
                return lateResponse;
            }
            if (invocation.getType() == ProxyMessage.MessageType.DATA) healthyData.countDown();
            return CompletableFuture.completedFuture(Response.ok());
        }));
        EmbeddedChannel browserA = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel browserB = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ExchangeClient a = null, b = null;
        try {
            server.start();
            URL clientUrl = new URL("proxy", "localhost", port).addParameter("transport", "http3")
                    .addParameter("cipher", cipher).addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile", certificate.ca().getAbsolutePath());
            a = new HeaderExchanger().connect(clientUrl);
            b = new HeaderExchanger().connect(clientUrl);
            ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
            registry.put(801L, browserA.pipeline().firstContext());
            registry.put(802L, browserB.pipeline().firstContext());
            a.setStreamRegistry(registry);
            b.setStreamRegistry(registry);
            assertTrue(b.request(connect(802), 5000).get(10, TimeUnit.SECONDS).isSuccess());
            CompletableFuture<Response> pending = a.request(connect(801), cancellation.equals("timeout") ? 3000 : 10000);
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "Remote must be awaiting its reply before cancellation");
            if (!cancellation.equals("timeout")) assertFalse(pending.isDone());
            if (cancellation.equals("client")) a.close();
            else if (cancellation.equals("browser")) browserA.close();
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((browserA.isActive() || registry.containsKey(801L) || a.getClient().getActiveStreamCount() != 0)
                    && System.nanoTime() < deadline) {
                browserA.runPendingTasks();
                Thread.sleep(10);
            }
            assertFalse(browserA.isActive());
            assertFalse(registry.containsKey(801L));
            lateResponse.complete(Response.ok());
            assertTrue(pending.isCompletedExceptionally(), "Late success must not revive CONNECT");
            assertTrue(browserB.isActive());
            assertTrue(registry.containsKey(802L));
            assertEquals(0, a.getClient().getActiveStreamCount());
            assertEquals(1, b.getClient().getActiveStreamCount());
            b.send(ProxyMessage.builder().streamId(802).type(ProxyMessage.MessageType.DATA).data(new byte[]{1, 2, 3}).build());
            assertTrue(healthyData.await(5, TimeUnit.SECONDS), "Independent client must still carry DATA");
            assertEquals(0, DefaultFuture.getActiveFutureCount());
        } finally {
            lateResponse.complete(Response.error("test cleanup"));
            if (a != null) a.close();
            if (b != null) b.close();
            server.close();
            browserA.finishAndReleaseAll();
            browserB.finishAndReleaseAll();
            certificate.delete();
        }
    }

    private static ProxyMessage connect(long id) {
        return ProxyMessage.builder().streamId(id).type(ProxyMessage.MessageType.CONNECT).host("target").port(443).build();
    }
}
