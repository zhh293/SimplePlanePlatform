package com.proxy.local.handler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestParserTest {

    @Test
    void parsesAbsoluteUrlAndRewritesToOriginForm() {
        HttpRequestParser.ParseResult result = parse(
                "GET http://example.com:8080/a/b?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n");

        assertEquals("example.com", result.getHost());
        assertEquals(8080, result.getPort());
        assertEquals("GET /a/b?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n", text(result));
    }

    @Test
    void parsesAbsoluteUrlWithQueryAndNoPath() {
        HttpRequestParser.ParseResult result = parse(
                "GET http://example.com?x=1 HTTP/1.1\r\nHost: ignored.test\r\n\r\n");

        assertEquals("example.com", result.getHost());
        assertEquals(80, result.getPort());
        assertTrue(text(result).startsWith("GET /?x=1 HTTP/1.1\r\n"));
    }

    @Test
    void usesHostHeaderForOriginFormAndSupportsExplicitPort() {
        HttpRequestParser.ParseResult result = parse(
                "POST /submit HTTP/1.1\r\nHost: api.example.com:8081\r\nContent-Length: 4\r\n\r\ndata");

        assertEquals("api.example.com", result.getHost());
        assertEquals(8081, result.getPort());
        assertEquals("POST /submit HTTP/1.1\r\nHost: api.example.com:8081\r\n"
                + "Content-Length: 4\r\n\r\ndata", text(result));
    }

    @Test
    void removesProxyConnectionHeaderCaseInsensitively() {
        HttpRequestParser.ParseResult result = parse(
                "GET http://example.com/ HTTP/1.1\r\nproxy-connection: keep-alive\r\n"
                        + "X-Test: kept\r\n\r\n");

        assertFalse(text(result).toLowerCase().contains("proxy-connection:"));
        assertTrue(text(result).contains("X-Test: kept\r\n"));
    }

    @Test
    void preservesNonAsciiBodyBytes() {
        byte[] body = new byte[]{0, (byte) 0xff, 0x41};
        byte[] header = "POST http://example.com/upload HTTP/1.1\r\nHost: example.com\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] request = new byte[header.length + body.length];
        System.arraycopy(header, 0, request, 0, header.length);
        System.arraycopy(body, 0, request, header.length, body.length);

        byte[] rewritten = HttpRequestParser.parse(request).getRewritten();
        assertArrayEquals(body, java.util.Arrays.copyOfRange(rewritten, rewritten.length - body.length, rewritten.length));
    }

    @Test
    void rejectsMalformedRequestsAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> parse("GET / HTTP/1.1\r\n\r\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET http://example.com:70000/ HTTP/1.1\r\n\r\n"));
    }

    @Test
    void parsesBracketedIpv6Authorities() {
        HttpRequestParser.ParseResult result = parse(
                "GET http://[2001:db8::1]:8080/a HTTP/1.1\r\nHost: [2001:db8::1]\r\n\r\n");

        assertEquals("2001:db8::1", result.getHost());
        assertEquals(8080, result.getPort());
        assertEquals("GET /a HTTP/1.1\r\nHost: [2001:db8::1]\r\n\r\n", text(result));
    }

    @Test
    void rejectsAmbiguousOrMalformedHostAuthorities() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET / HTTP/1.1\r\nHost: first.example\r\nHost: second.example\r\n\r\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET / HTTP/1.1\r\nHost: example.com:abc\r\n\r\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET / HTTP/1.1\r\nHost: example.com:\r\n\r\n"));
    }

    @Test
    void rejectsHttpsAbsoluteFormAndFragmentsInsteadOfForwardingAmbiguousTargets() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET https://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "GET http://example.com/path#fragment HTTP/1.1\r\nHost: example.com\r\n\r\n"));
    }

    @Test
    void resultDoesNotExposeMutableInternalRequestBytes() {
        HttpRequestParser.ParseResult result = parse(
                "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n");
        byte[] exposed = result.getRewritten();
        exposed[0] = 'X';

        assertTrue(text(result).startsWith("GET / HTTP/1.1"));
    }

    private static HttpRequestParser.ParseResult parse(String request) {
        return HttpRequestParser.parse(request.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String text(HttpRequestParser.ParseResult result) {
        return new String(result.getRewritten(), StandardCharsets.ISO_8859_1);
    }
}
