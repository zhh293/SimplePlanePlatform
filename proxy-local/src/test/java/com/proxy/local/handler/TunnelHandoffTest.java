package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class TunnelHandoffTest {
    @Test void socksSuccessIsNotPublishedBeforeRelayCanAcceptTls() throws Exception { verify(true); }
    @Test void httpSuccessIsNotPublishedBeforeRelayCanAcceptTls() throws Exception { verify(false); }

    private void verify(boolean socks) throws Exception {
        CompletableFuture<Response> connected = new CompletableFuture<>();
        List<Invocation> calls = new CopyOnWriteArrayList<>();
        Invoker invoker = invocation -> {
            calls.add(invocation);
            return invocation.getType() == MessageType.CONNECT ? connected : CompletableFuture.completedFuture(Response.ok());
        };
        ChannelOutboundHandlerAdapter beforeReply = new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                assertNotNull(ctx.pipeline().get(RelayHandler.class), "Success reply must follow Relay installation");
                assertNull(ctx.pipeline().get(Socks5ConnectHandler.class));
                assertNull(ctx.pipeline().get(HttpConnectHandler.class));
                ctx.write(msg, promise);
            }
        };
        EmbeddedChannel browser = new EmbeddedChannel(beforeReply, socks
                ? new Socks5ConnectHandler(invoker, null) : new HttpConnectHandler(invoker, null));
        try {
            byte[] request = socks ? new byte[]{5, 1, 0, 3, 6, 't', 'a', 'r', 'g', 'e', 't', 1, (byte) 187}
                    : "CONNECT target:443 HTTP/1.1\r\nHost: target\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            browser.writeInbound(Unpooled.wrappedBuffer(request));
            Thread remote = new Thread(() -> connected.complete(Response.ok()));
            remote.start(); remote.join();
            browser.runPendingTasks();
            browser.checkException();
            ByteBuf reply = browser.readOutbound();
            assertNotNull(reply);
            reply.release();
            byte[] firstTls = new byte[]{0x16, 3, 3, 0, 3, 1, 2, 3};
            browser.writeInbound(Unpooled.wrappedBuffer(firstTls));
            assertEquals(MessageType.DATA, calls.get(1).getType());
            assertArrayEquals(firstTls, calls.get(1).getData());
        } finally { browser.finishAndReleaseAll(); }
    }
}
