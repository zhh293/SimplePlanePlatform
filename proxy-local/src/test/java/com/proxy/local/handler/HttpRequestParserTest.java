package com.proxy.local.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpRequestParser 单元测试。
 * <p>
 * 验证 HTTP 代理请求的解析与 URL 重写功能，覆盖正常场景、Fallback 场景和异常场景。
 * </p>
 */
class HttpRequestParserTest {

    // ==================== 正常场景 ====================

    /**
     * TC-1: 标准 GET 请求，绝对 URL，默认端口
     */
    @Test
    void testStandardGet() {
        byte[] input = "GET http://example.com/page HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(80, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET /page HTTP/1.1\r\n"));
        // 确认仍然包含 Host 头
        assertTrue(rewritten.contains("Host: example.com"));
        // 确认绝对 URL 已被移除
        assertFalse(rewritten.contains("http://example.com"));
    }

    /**
     * TC-2: 带自定义端口
     */
    @Test
    void testCustomPort() {
        byte[] input = "GET http://example.com:8080/api HTTP/1.1\r\nHost: example.com:8080\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(8080, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET /api HTTP/1.1\r\n"));
    }

    /**
     * TC-3: 无路径，自动补 "/"
     */
    @Test
    void testNoPath() {
        byte[] input = "GET http://example.com HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(80, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET / HTTP/1.1\r\n"));
    }

    /**
     * TC-4: 带 query string
     */
    @Test
    void testWithQueryString() {
        byte[] input = "GET http://example.com/search?q=hello&lang=zh HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET /search?q=hello&lang=zh HTTP/1.1\r\n"));
    }

    /**
     * TC-5: POST 方法
     */
    @Test
    void testPostMethod() {
        byte[] input = "POST http://example.com/api/data HTTP/1.1\r\nHost: example.com\r\nContent-Length: 0\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("POST /api/data HTTP/1.1\r\n"));
        // 确认 Content-Length 头保留
        assertTrue(rewritten.contains("Content-Length: 0"));
    }

    /**
     * TC-6: Proxy-Connection 头被移除
     */
    @Test
    void testProxyConnectionRemoved() {
        byte[] input = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        String rewritten = new String(result.getRewritten());
        assertFalse(rewritten.toLowerCase().contains("proxy-connection"));
        assertTrue(rewritten.contains("Accept: */*"));
        assertTrue(rewritten.contains("Host: example.com"));
    }

    // ==================== Fallback 场景 ====================

    /**
     * TC-7: 相对 URL + Host 头 fallback
     */
    @Test
    void testRelativeUrlWithHostHeader() {
        byte[] input = "GET /page HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(80, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET /page HTTP/1.1\r\n"));
    }

    /**
     * TC-8: 相对 URL + Host 头带端口
     */
    @Test
    void testRelativeUrlWithHostPort() {
        byte[] input = "GET /page HTTP/1.1\r\nHost: example.com:9090\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(9090, result.getPort());
    }

    // ==================== 异常场景 ====================

    /**
     * TC-9: null 输入
     */
    @Test
    void testNullInput() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(null));
    }

    /**
     * TC-10: 空数组
     */
    @Test
    void testEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(new byte[0]));
    }

    /**
     * TC-11: 相对 URL 且无 Host 头
     */
    @Test
    void testRelativeUrlNoHost() {
        byte[] input = "GET /page HTTP/1.1\r\nAccept: */*\r\n\r\n".getBytes();
        assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(input));
    }

    /**
     * TC-12: 请求行格式错误（不足 3 段）
     */
    @Test
    void testMalformedRequestLine() {
        byte[] input = "INVALID\r\n\r\n".getBytes();
        assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(input));
    }

    // ==================== 补充场景 ====================

    /**
     * TC-13: 带尾部斜杠的 URL
     */
    @Test
    void testTrailingSlash() {
        byte[] input = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(80, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("GET / HTTP/1.1\r\n"));
    }

    /**
     * TC-14: PUT 方法
     */
    @Test
    void testPutMethod() {
        byte[] input = "PUT http://example.com:3000/resource HTTP/1.1\r\nHost: example.com:3000\r\nContent-Length: 5\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        assertEquals("example.com", result.getHost());
        assertEquals(3000, result.getPort());

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("PUT /resource HTTP/1.1\r\n"));
    }

    /**
     * TC-15: DELETE 方法
     */
    @Test
    void testDeleteMethod() {
        byte[] input = "DELETE http://example.com/resource/123 HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.startsWith("DELETE /resource/123 HTTP/1.1\r\n"));
    }

    /**
     * TC-16: Proxy-Connection 大小写不敏感
     */
    @Test
    void testProxyConnectionCaseInsensitive() {
        byte[] input = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nproxy-connection: keep-alive\r\n\r\n".getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        String rewritten = new String(result.getRewritten());
        assertFalse(rewritten.toLowerCase().contains("proxy-connection"));
    }

    /**
     * TC-17: 多个 header 时只移除 Proxy-Connection，保留其余
     */
    @Test
    void testMultipleHeadersPreserved() {
        byte[] input = ("GET http://example.com/path HTTP/1.1\r\n"
                + "Host: example.com\r\n"
                + "Accept: text/html\r\n"
                + "Proxy-Connection: keep-alive\r\n"
                + "User-Agent: TestAgent\r\n"
                + "Accept-Encoding: gzip\r\n"
                + "\r\n").getBytes();
        HttpRequestParser.ParseResult result = HttpRequestParser.parse(input);

        String rewritten = new String(result.getRewritten());
        assertTrue(rewritten.contains("Host: example.com"));
        assertTrue(rewritten.contains("Accept: text/html"));
        assertTrue(rewritten.contains("User-Agent: TestAgent"));
        assertTrue(rewritten.contains("Accept-Encoding: gzip"));
        assertFalse(rewritten.toLowerCase().contains("proxy-connection"));
        // 确认以 \r\n\r\n 结尾
        assertTrue(rewritten.endsWith("\r\n\r\n"));
    }
}
