package com.proxy.local.handler;

import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求解析与重写工具。
 * <p>
 * 从浏览器发送的 HTTP 代理请求中解析目标 host:port，
 * 并将绝对 URL 重写为目标服务器可接受的相对路径格式。
 * </p>
 * <p>
 * 本类是纯工具类，不依赖 Netty、不涉及网络操作。职责单一：解析和重写。
 * </p>
 *
 * <h3>输入输出示例</h3>
 * <pre>
 * 输入：GET http://example.com/page?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n
 * 输出：GET /page?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n
 *       host=example.com, port=80
 * </pre>
 */
public class HttpRequestParser {

    private static final String HTTP_SCHEME_PREFIX = "http://";
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final String CRLF = "\r\n";
    private static final String HEADER_END = "\r\n\r\n";
    private static final String PROXY_CONNECTION_PREFIX = "proxy-connection:";

    /**
     * 解析结果：不可变对象，包含目标主机、端口和重写后的请求字节。
     */
    public static class ParseResult {
        private final String host;
        private final int port;
        private final byte[] rewritten;

        /**
         * @param host      目标主机名
         * @param port      目标端口（默认 80）
         * @param rewritten 重写后的完整 HTTP 请求字节
         */
        public ParseResult(String host, int port, byte[] rewritten) {
            this.host = host;
            this.port = port;
            this.rewritten = rewritten;
        }

        /** 目标主机名 */
        public String getHost() {
            return host;
        }

        /** 目标端口 */
        public int getPort() {
            return port;
        }

        /** 重写后的完整 HTTP 请求字节 */
        public byte[] getRewritten() {
            return rewritten;
        }
    }

    /**
     * 解析并重写 HTTP 代理请求。
     * <p>
     * 从原始请求字节中提取目标 host:port，将请求行中的绝对 URL 重写为相对路径，
     * 并移除 Proxy-Connection 头（如果存在）。
     * </p>
     *
     * <h3>解析逻辑</h3>
     * <ol>
     *   <li>从请求行中提取 URL 部分（{@code http://example.com:8080/path?q=1}）</li>
     *   <li>解析出 host、port（无端口则默认 80）、pathAndQuery（{@code /path?q=1}）</li>
     *   <li>将请求行中的绝对 URL 替换为相对路径（{@code GET /path?q=1 HTTP/1.1}）</li>
     *   <li>移除 Proxy-Connection 头</li>
     *   <li>保留所有其他原始 header 不变</li>
     *   <li>返回包含 host、port、重写后字节的 ParseResult</li>
     * </ol>
     *
     * @param rawRequest 原始 HTTP 请求头的完整字节（以 \r\n\r\n 结尾）
     * @return 解析结果，包含 host、port 和重写后的字节
     * @throws IllegalArgumentException 请求格式无法解析时抛出
     */
    public static ParseResult parse(byte[] rawRequest) {
        if (rawRequest == null || rawRequest.length == 0) {
            throw new IllegalArgumentException("Raw request is null or empty");
        }

        String rawStr = new String(rawRequest, StandardCharsets.UTF_8);

        // 提取请求行（第一行）
        int firstCrLf = rawStr.indexOf(CRLF);
        if (firstCrLf < 0) {
            throw new IllegalArgumentException("No CRLF found in request, malformed HTTP request");
        }
        String requestLine = rawStr.substring(0, firstCrLf);
        String rest = rawStr.substring(firstCrLf); // 包含 \r\n 开头，后面是 headers + \r\n\r\n

        // 拆分请求行为 method、url、httpVersion
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Malformed request line: " + requestLine);
        }
        String method = parts[0];
        String url = parts[1];
        String httpVersion = parts[2];

        String host;
        int port;
        String pathAndQuery;

        if (url.toLowerCase().startsWith(HTTP_SCHEME_PREFIX)) {
            // 绝对 URL：解析 host:port 和 pathAndQuery
            String afterScheme = url.substring(HTTP_SCHEME_PREFIX.length());
            String[] hostPortAndPath = splitHostPortAndPath(afterScheme);
            host = hostPortAndPath[0];
            port = Integer.parseInt(hostPortAndPath[1]);
            pathAndQuery = hostPortAndPath[2];
        } else {
            // 非绝对 URL：fallback 到 Host 头
            String[] hostAndPort = parseHostHeader(rest);
            if (hostAndPort == null) {
                throw new IllegalArgumentException("Cannot determine target: URL is not absolute and no Host header found");
            }
            host = hostAndPort[0];
            port = Integer.parseInt(hostAndPort[1]);
            // 相对 URL 时，请求行中的 url 就是 pathAndQuery，不需要重写
            pathAndQuery = url;
        }

        // 重写请求行
        String rewrittenRequestLine = method + " " + pathAndQuery + " " + httpVersion;

        // 过滤 Proxy-Connection 头
        String filteredRest = removeProxyConnectionHeader(rest);

        // 拼接重写后的请求
        String rewritten = rewrittenRequestLine + filteredRest;
        byte[] rewrittenBytes = rewritten.getBytes(StandardCharsets.UTF_8);

        return new ParseResult(host, port, rewrittenBytes);
    }

    /**
     * 从绝对 URL 的 scheme 之后部分中分离 host、port 和 pathAndQuery。
     * <p>
     * 输入示例：{@code "example.com:8080/page?q=1"}
     * 输出：{@code ["example.com", "8080", "/page?q=1"]}
     * </p>
     *
     * @param afterScheme http:// 后面的部分
     * @return 包含 [host, port字符串, pathAndQuery] 的数组
     */
    private static String[] splitHostPortAndPath(String afterScheme) {
        String hostPort;
        String pathAndQuery;

        int slashIndex = afterScheme.indexOf('/');
        if (slashIndex >= 0) {
            hostPort = afterScheme.substring(0, slashIndex);
            pathAndQuery = afterScheme.substring(slashIndex); // 保留 /
        } else {
            hostPort = afterScheme;
            pathAndQuery = "/"; // 无路径则默认 "/"
        }

        String host;
        int port;
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex > 0) {
            host = hostPort.substring(0, colonIndex);
            try {
                port = Integer.parseInt(hostPort.substring(colonIndex + 1));
            } catch (NumberFormatException e) {
                host = hostPort;
                port = DEFAULT_HTTP_PORT;
            }
        } else {
            host = hostPort;
            port = DEFAULT_HTTP_PORT;
        }

        return new String[]{host, String.valueOf(port), pathAndQuery};
    }

    /**
     * 从 Header 区域解析 Host 头作为 fallback。
     * <p>
     * 支持格式：
     * <ul>
     *   <li>{@code Host: example.com} → host=example.com, port=80</li>
     *   <li>{@code Host: example.com:8080} → host=example.com, port=8080</li>
     * </ul>
     * </p>
     *
     * @param headers 从第一个 \r\n 开始的 header 区域字符串
     * @return [host, port字符串] 或 null（未找到 Host 头时）
     */
    private static String[] parseHostHeader(String headers) {
        String[] lines = headers.split(CRLF);
        for (String line : lines) {
            if (line.toLowerCase().startsWith("host:")) {
                String hostValue = line.substring(5).trim();
                if (hostValue.isEmpty()) {
                    continue;
                }
                int colonIndex = hostValue.lastIndexOf(':');
                if (colonIndex > 0) {
                    String host = hostValue.substring(0, colonIndex);
                    try {
                        int port = Integer.parseInt(hostValue.substring(colonIndex + 1));
                        return new String[]{host, String.valueOf(port)};
                    } catch (NumberFormatException e) {
                        // 端口不是数字，整个值作为 host
                        return new String[]{hostValue, String.valueOf(DEFAULT_HTTP_PORT)};
                    }
                } else {
                    return new String[]{hostValue, String.valueOf(DEFAULT_HTTP_PORT)};
                }
            }
        }
        return null;
    }

    /**
     * 移除 Proxy-Connection 头。
     * <p>
     * 逐行扫描 header，遇到以 {@code proxy-connection:} 开头（不区分大小写）的行，
     * 跳过不写入输出。这个头是浏览器和代理之间的约定，不应转发给目标服务器。
     * </p>
     *
     * @param headers 从第一个 \r\n 开始的 header 区域字符串（包含尾部的 \r\n\r\n）
     * @return 移除 Proxy-Connection 头后的 header 区域字符串
     */
    private static String removeProxyConnectionHeader(String headers) {
        // headers 以 \r\n 开头，以 \r\n\r\n 结尾
        // 先检查是否包含 proxy-connection（快速路径，避免不必要的字符串操作）
        if (!headers.toLowerCase().contains(PROXY_CONNECTION_PREFIX)) {
            return headers;
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = headers.split(CRLF, -1);
        for (String line : lines) {
            if (line.toLowerCase().startsWith(PROXY_CONNECTION_PREFIX)) {
                // 跳过 Proxy-Connection 头行
                continue;
            }
            sb.append(line).append(CRLF);
        }

        // split 会在末尾产生多余的 \r\n，需要修正
        // 原始 headers 以 \r\n\r\n 结尾，split 后最后两个元素是空字符串
        // 重新拼接后会是正确的格式
        String result = sb.toString();

        // 确保以 \r\n\r\n 结尾（即 header 区域和 body 的分隔符）
        if (!result.endsWith(HEADER_END)) {
            // 去掉多余的 \r\n，重新添加正确的结尾
            while (result.endsWith(CRLF)) {
                result = result.substring(0, result.length() - CRLF.length());
            }
            result = result + HEADER_END;
        }

        return result;
    }

    // 私有构造函数，防止实例化
    private HttpRequestParser() {
    }
}
