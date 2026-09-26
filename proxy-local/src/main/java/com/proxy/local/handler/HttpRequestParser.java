package com.proxy.local.handler;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Parses HTTP forward-proxy requests and rewrites absolute request targets. */
public final class HttpRequestParser {

    private static final int DEFAULT_HTTP_PORT = 80;
    private static final String CRLF = "\r\n";
    private static final String HEADER_END = "\r\n\r\n";

    private HttpRequestParser() {
    }

    public static final class ParseResult {
        private final String host;
        private final int port;
        private final byte[] rewritten;

        private ParseResult(String host, int port, byte[] rewritten) {
            this.host = host;
            this.port = port;
            this.rewritten = rewritten;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public byte[] getRewritten() { return rewritten.clone(); }
    }

    /**
     * Resolves the target from an absolute {@code http://} request target or the
     * Host header, converts absolute targets to origin-form, and removes the
     * hop-by-hop Proxy-Connection header.
     */
    public static ParseResult parse(byte[] rawRequest) {
        if (rawRequest == null || rawRequest.length == 0) {
            throw new IllegalArgumentException("Raw request is null or empty");
        }

        String request = new String(rawRequest, StandardCharsets.ISO_8859_1);
        int firstLineEnd = request.indexOf(CRLF);
        if (firstLineEnd < 0) {
            throw new IllegalArgumentException("Malformed HTTP request: missing CRLF");
        }

        String[] requestLine = request.substring(0, firstLineEnd).split(" ", 3);
        if (requestLine.length != 3 || requestLine[0].isEmpty()
                || requestLine[1].isEmpty() || requestLine[2].isEmpty()) {
            throw new IllegalArgumentException("Malformed HTTP request line");
        }

        String method = requestLine[0];
        String target = requestLine[1];
        String version = requestLine[2];
        String rest = request.substring(firstLineEnd);

        Target parsedTarget;
        String rewrittenTarget;
        if (target.regionMatches(true, 0, "http://", 0, "http://".length())) {
            parsedTarget = parseAbsoluteTarget(target);
            rewrittenTarget = parsedTarget.pathAndQuery;
        } else {
            if (hasUriScheme(target)) {
                throw new IllegalArgumentException("Only absolute http:// targets are supported");
            }
            parsedTarget = parseHostHeader(rest);
            if (parsedTarget == null) {
                throw new IllegalArgumentException("Relative request target requires a Host header");
            }
            rewrittenTarget = target;
        }

        String filteredHeaders = removeProxyConnection(rest);
        byte[] rewritten = (method + " " + rewrittenTarget + " " + version + filteredHeaders)
                .getBytes(StandardCharsets.ISO_8859_1);
        return new ParseResult(parsedTarget.host, parsedTarget.port, rewritten);
    }

    private static Target parseAbsoluteTarget(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Only absolute http:// targets are supported");
            }
            return target(uri, "Invalid absolute request target");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid absolute request target", e);
        }
    }

    private static Target parseHostHeader(String headers) {
        Target found = null;
        for (String line : headers.split(CRLF)) {
            int colon = line.indexOf(':');
            if (colon < 0 || !"host".equals(line.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String authority = line.substring(colon + 1).trim();
            if (authority.isEmpty()) return null;
            if (found != null) throw new IllegalArgumentException("Multiple Host headers are not allowed");
            try {
                URI uri = new URI("http://" + authority);
                if (uri.getRawUserInfo() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException("Invalid Host header");
                }
                if (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":")) {
                    throw new IllegalArgumentException("Invalid Host header port");
                }
                found = target(uri, "Invalid Host header");
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid Host header", e);
            }
        }
        return found;
    }

    private static Target target(URI uri, String error) {
        String host = uri.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = uri.getPort() < 0 ? DEFAULT_HTTP_PORT : uri.getPort();
        if (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException(error);
        }
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException(error);
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = uri.getRawQuery();
        return new Target(host, port, query == null ? path : path + "?" + query);
    }

    private static boolean hasUriScheme(String target) {
        int colon = target.indexOf(':');
        if (colon <= 0) return false;
        if (!Character.isLetter(target.charAt(0))) return false;
        for (int i = 1; i < colon; i++) {
            char c = target.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '.' && c != '-') return false;
        }
        return true;
    }

    private static String removeProxyConnection(String headersAndBody) {
        int headerEnd = headersAndBody.indexOf(HEADER_END);
        if (headerEnd < 0) throw new IllegalArgumentException("Malformed HTTP request headers");
        if (!headersAndBody.substring(0, headerEnd).toLowerCase(Locale.ROOT).contains("proxy-connection:")) {
            return headersAndBody;
        }
        String headers = headersAndBody.substring(0, headerEnd + 2);
        String body = headersAndBody.substring(headerEnd + HEADER_END.length());
        StringBuilder filtered = new StringBuilder();
        boolean first = true;
        String[] lines = headers.split(CRLF, -1);
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Proxy-Connection:", 0, "Proxy-Connection:".length())) continue;
            if (!first) filtered.append(CRLF);
            filtered.append(line);
            first = false;
        }
        return filtered.append(CRLF).append(body).toString();
    }

    private static final class Target {
        private final String host;
        private final int port;
        private final String pathAndQuery;

        private Target(String host, int port, String pathAndQuery) {
            this.host = host;
            this.port = port;
            this.pathAndQuery = pathAndQuery;
        }
    }
}
