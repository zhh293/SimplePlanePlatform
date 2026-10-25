package com.proxy.exchange.header;

import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionOwnershipTest {
    @Test void clientCloseCancelsPendingConnectBeforeTransportCloseAndIgnoresLateSuccess() {
        ExchangeHandler handler = new ExchangeHandler();
        EmbeddedChannel transport = new EmbeddedChannel(handler);
        EmbeddedChannel browser = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
        registry.put(105L, browser.pipeline().firstContext());
        handler.setStreamRegistry(registry);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong requestId = new java.util.concurrent.atomic.AtomicLong();
        com.proxy.common.transport.Client raw = new com.proxy.common.transport.Client() {
            public void send(ProxyMessage message) { requestId.set(message.getRequestId()); }
            public boolean isAvailable() { return !closed.get(); }
            public int getActiveStreamCount() { return 0; }
            public void close() {
                assertFalse(browser.isActive(), "Owned browser is closed before transport retirement");
                assertTrue(registry.isEmpty());
                closed.set(true);
            }
        };
        HeaderExchangeClient client = new HeaderExchangeClient(raw,
                new com.proxy.common.model.URL("proxy", "localhost", 9090), handler);
        try {
            java.util.concurrent.CompletableFuture<Response> pending = client.request(ProxyMessage.builder()
                    .streamId(105).type(ProxyMessage.MessageType.CONNECT).host("target").port(443).build(), 5000);
            assertFalse(pending.isDone());
            client.close();
            browser.runPendingTasks();
            assertTrue(closed.get());
            assertTrue(pending.isCompletedExceptionally());
            transport.writeInbound(ProxyMessage.builder().requestId(requestId.get()).streamId(105)
                    .type(ProxyMessage.MessageType.CONNECT_RESPONSE).status(Response.OK).build());
            assertTrue(pending.isCompletedExceptionally(), "Late success must not revive a closed CONNECT");
            assertFalse(browser.isActive());
            assertTrue(registry.isEmpty());
            assertEquals(0, DefaultFuture.getActiveFutureCount());
        } finally {
            client.close();
            transport.finishAndReleaseAll();
            browser.finishAndReleaseAll();
        }
    }

    @Test void synchronousConnectCallbackWritesHandshakeBeforeFollowingData() {
        ExchangeHandler handler = new ExchangeHandler();
        EmbeddedChannel transport = new EmbeddedChannel(handler);
        EmbeddedChannel browser = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
        registry.put(104L, browser.pipeline().firstContext());
        handler.setStreamRegistry(registry);
        DefaultFuture connect = DefaultFuture.newFuture(90004, 10000);
        handler.trackRequest(90004, 104, connect, true);
        connect.whenComplete((response, error) -> browser.writeOutbound(
                io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{5, 0})));
        transport.writeInbound(ProxyMessage.builder().requestId(90004).streamId(104)
                .type(ProxyMessage.MessageType.CONNECT_RESPONSE).status(Response.OK).build());
        transport.writeInbound(ProxyMessage.builder().streamId(104).type(ProxyMessage.MessageType.DATA)
                .data(new byte[]{42}).build());
        io.netty.buffer.ByteBuf handshake = browser.readOutbound();
        io.netty.buffer.ByteBuf payload = browser.readOutbound();
        assertEquals(5, handshake.readByte());
        assertEquals(42, payload.readByte());
        handshake.release();
        payload.release();
        handler.onStreamClosed(104, null);
        transport.finishAndReleaseAll();
        browser.finishAndReleaseAll();
    }

    @Test void disconnectClosesOnlyOwnedEstablishedStreamsAndRequests() {
        ExchangeHandler a = new ExchangeHandler(), b = new ExchangeHandler();
        EmbeddedChannel browserA = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel browserB = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
        registry.put(101L, browserA.pipeline().firstContext());
        registry.put(102L, browserB.pipeline().firstContext());
        a.setStreamRegistry(registry);
        b.setStreamRegistry(registry);
        DefaultFuture established = DefaultFuture.newFuture(90001, 10000);
        DefaultFuture pendingB = DefaultFuture.newFuture(90002, 10000);
        a.trackRequest(90001, 101, established, true);
        b.trackRequest(90002, 102, pendingB, true);
        established.complete(Response.ok());
        a.onDisconnected();
        browserA.runPendingTasks();
        assertFalse(browserA.isActive());
        assertTrue(browserB.isActive());
        assertFalse(pendingB.isDone());
        assertEquals(1, registry.size());
        b.onDisconnected();
        assertTrue(pendingB.isCompletedExceptionally());
        browserA.finishAndReleaseAll();
        browserB.finishAndReleaseAll();
    }

}
