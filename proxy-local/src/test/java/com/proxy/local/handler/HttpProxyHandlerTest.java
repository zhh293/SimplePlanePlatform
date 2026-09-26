package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.local.config.ProxyConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class HttpProxyHandlerTest {

    @Test
    void forwardsPlainHttpOverRemoteTunnelAndPreservesBodyArrivingWithHeaders() {
        List<Invocation> invocations = new ArrayList<>();
        Invoker invoker = invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpConnectHandler(invoker, route("proxy")));

        String request = "POST http://example.com:8080/upload?q=1 HTTP/1.1\r\n"
                + "Host: example.com:8080\r\nContent-Length: 4\r\nProxy-Connection: keep-alive\r\n\r\nDATA";
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.ISO_8859_1));

        assertTrue(channel.pipeline().get("http-proxy-relay") instanceof HttpProxyRelayHandler);
        assertEquals(ProxyMessage.MessageType.CONNECT, invocations.get(0).getType());
        assertEquals("example.com", invocations.get(0).getTargetHost());
        assertEquals(8080, invocations.get(0).getTargetPort());

        List<byte[]> payloads = new ArrayList<>();
        for (Invocation invocation : invocations) {
            if (invocation.getType() == ProxyMessage.MessageType.DATA) payloads.add(invocation.getData());
        }
        assertEquals(2, payloads.size(), "rewritten request headers and buffered body should both be sent");
        String initialRequest = new String(payloads.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(initialRequest.startsWith("POST /upload?q=1 HTTP/1.1\r\n"));
        assertFalse(initialRequest.toLowerCase().contains("proxy-connection:"));
        assertEquals("DATA", new String(payloads.get(1), StandardCharsets.ISO_8859_1));

        channel.finishAndReleaseAll();
    }

    @Test
    void waitsForCompleteRequestHeaderBeforeRouting() {
        List<Invocation> invocations = new ArrayList<>();
        Invoker invoker = invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpConnectHandler(invoker, route("proxy")));

        channel.writeInbound(Unpooled.copiedBuffer("GET http://example.com/ HTTP/1.1\r\nHost:",
                StandardCharsets.US_ASCII));
        assertTrue(invocations.isEmpty());

        channel.writeInbound(Unpooled.copiedBuffer(" example.com\r\n\r", StandardCharsets.US_ASCII));
        assertTrue(invocations.isEmpty(), "a delimiter split across reads must not route early");
        channel.writeInbound(Unpooled.copiedBuffer("\n", StandardCharsets.US_ASCII));
        assertEquals(ProxyMessage.MessageType.CONNECT, invocations.get(0).getType());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectRouteReturns502WithoutOpeningRemoteTunnel() {
        List<Invocation> invocations = new ArrayList<>();
        Invoker invoker = invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpConnectHandler(invoker, route("reject")));

        channel.writeInbound(Unpooled.copiedBuffer(
                "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n", StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502"));
        response.release();
        assertTrue(invocations.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void malformedPlainHttpRequestReturns400() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpConnectHandler(invocation -> CompletableFuture.completedFuture(Response.ok()), route("proxy")));

        channel.writeInbound(Unpooled.copiedBuffer("GET /missing-host HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 400"));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void protocolDetectorRecognizesOrdinaryHttpMethods() {
        List<Invocation> invocations = new ArrayList<>();
        Invoker invoker = invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new ProtocolDetector(invoker, true, route("proxy")));

        channel.writeInbound(Unpooled.copiedBuffer(
                "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n", StandardCharsets.US_ASCII));

        assertTrue(invocations.stream().anyMatch(i -> i.getType() == ProxyMessage.MessageType.CONNECT));
        assertTrue(invocations.stream().anyMatch(i -> i.getType() == ProxyMessage.MessageType.DATA));
        channel.finishAndReleaseAll();
    }

    @Test
    void largeRequestBodyIsNotMistakenForAnOversizedHeader() {
        List<Invocation> invocations = new ArrayList<>();
        Invoker invoker = invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpConnectHandler(invoker, route("proxy")));
        byte[] body = new byte[16 * 1024];
        Arrays.fill(body, (byte) 'z');
        byte[] header = "POST http://example.com/upload HTTP/1.1\r\nHost: example.com\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] request = new byte[header.length + body.length + 2];
        System.arraycopy(header, 0, request, 0, header.length);
        System.arraycopy("\r\n".getBytes(StandardCharsets.US_ASCII), 0,
                request, header.length, 2);
        System.arraycopy(body, 0, request, header.length + 2, body.length);

        channel.writeInbound(Unpooled.wrappedBuffer(request));

        assertEquals(ProxyMessage.MessageType.CONNECT, invocations.get(0).getType());
        int forwardedBodyBytes = invocations.stream()
                .filter(i -> i.getType() == ProxyMessage.MessageType.DATA)
                .mapToInt(i -> i.getData().length)
                .sum();
        byte[] requestHeader = Arrays.copyOf(request, header.length + 2);
        int rewrittenHeaderLength = HttpRequestParser.parse(requestHeader).getRewritten().length;
        assertEquals(rewrittenHeaderLength + body.length, forwardedBodyBytes);
        assertNull(channel.readOutbound(), "a valid large body must not receive a 400 response");
        channel.finishAndReleaseAll();
    }

    @Test
    void oversizedIncompleteHeaderIsRejectedBeforeRouting() {
        List<Invocation> invocations = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpConnectHandler(invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        }, route("proxy")));
        String prefix = "GET http://example.com/ HTTP/1.1\r\nX-Long: ";
        String oversized = prefix + repeat('a', 8200);

        channel.writeInbound(Unpooled.copiedBuffer(oversized, StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 400"));
        response.release();
        assertTrue(invocations.isEmpty());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void oversizedCompleteHeaderIsRejectedBeforeRouting() {
        List<Invocation> invocations = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpConnectHandler(invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        }, route("proxy")));
        String request = "GET http://example.com/ HTTP/1.1\r\nX-Long: " + repeat('b', 8200) + "\r\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 400"));
        response.release();
        assertTrue(invocations.isEmpty());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void connectStillEstablishesTunnelAndForwardsBytesThatShareThePacket() {
        List<Invocation> invocations = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpConnectHandler(invocation -> {
            invocations.add(invocation);
            return CompletableFuture.completedFuture(Response.ok());
        }, route("proxy")));
        byte[] tlsRecordPrefix = new byte[]{0x16, 0x03, 0x01, 0x00, 0x02};
        ByteBuf request = Unpooled.buffer();
        request.writeBytes("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        request.writeBytes(tlsRecordPrefix);

        channel.writeInbound(request);

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200 Connection Established"));
        response.release();
        assertEquals(ProxyMessage.MessageType.CONNECT, invocations.get(0).getType());
        Invocation data = invocations.stream()
                .filter(i -> i.getType() == ProxyMessage.MessageType.DATA)
                .findFirst().orElseThrow(AssertionError::new);
        assertArrayEquals(tlsRecordPrefix, data.getData());
        channel.finishAndReleaseAll();
    }

    private static RouteRule route(String action) {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute(action);
        if ("reject".equals(action)) {
            ProxyConfig.RouteConfig.RouteEntry all = new ProxyConfig.RouteConfig.RouteEntry();
            all.setId("reject-all");
            all.setType("match");
            all.setValue("");
            all.setAction("reject");
            config.setRules(Arrays.asList(all));
        }
        return new RouteRule(config);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
