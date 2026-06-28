package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 代理请求处理器（支持 CONNECT 隧道 + HTTP 普通代理）
 * <p>
 * 处理两种 HTTP 代理模式：
 * <ul>
 *   <li>CONNECT 隧道（HTTPS）：收到 CONNECT 请求后建立隧道，切换到 {@link RelayHandler}</li>
 *   <li>HTTP 普通代理（HTTP）：收到 GET/POST 等请求后解析目标地址、重写 URL，
 *       切换到 {@link HttpProxyRelayHandler} 或 {@link DirectRelayHandler}</li>
 * </ul>
 * </p>
 * <p>
 * 继承 {@link ByteToMessageDecoder}，利用 Netty 内置的累积缓冲机制，
 * 自动处理 TCP 粘包/拆包问题和 ByteBuf 的生命周期管理，
 * 避免手动管理 cumulation 可能导致的内存泄漏。
 * </p>
 *
 * <h3>HTTPS 隧道建立与 TLS 握手流程</h3>
 * <p>
 * 浏览器访问 HTTPS 网站时与本处理器的交互时序：
 * </p>
 * <pre>
 * ┌────────┐          ┌────────────┐        ┌─────────────┐        ┌──────────────┐
 * │ Browser│          │ proxy-local│        │proxy-remote │        │example.com:443│
 * └───┬────┘          └─────┬──────┘        └──────┬──────┘        └──────┬───────┘
 *     │  TCP connect (明文)  │                      │                      │
 *     │────────────────────→│                      │                      │
 *     │                     │                      │                      │
 *     │ CONNECT example.com:443 HTTP/1.1           │                      │
 *     │────────────────────→│                      │                      │
 *     │                     │  ProxyMessage(CONNECT)│                      │
 *     │                     │─────────────────────→│                      │
 *     │                     │                      │  TCP connect (裸TCP) │
 *     │                     │                      │─────────────────────→│
 *     │                     │                      │  TCP connected       │
 *     │                     │                      │←─────────────────────│
 *     │                     │  CONNECT_RESPONSE     │                      │
 *     │                     │←─────────────────────│                      │
 *     │ HTTP/1.1 200 Connection Established        │                      │
 *     │←────────────────────│                      │                      │
 *     │                     │                      │                      │
 *     │  ═══════ 以下进入隧道透传模式（RelayHandler）═══════                │
 *     │                     │                      │                      │
 *     │ TLS ClientHello     │  DATA (透传)         │  TLS ClientHello     │
 *     │────────────────────→│─────────────────────→│─────────────────────→│
 *     │                     │                      │                      │
 *     │ TLS ServerHello+Cert│  DATA (透传)         │  TLS ServerHello+Cert│
 *     │←────────────────────│←─────────────────────│←─────────────────────│
 *     │                     │                      │                      │
 *     │ TLS Key Exchange    │  DATA (透传)         │  TLS Key Exchange    │
 *     │────────────────────→│─────────────────────→│─────────────────────→│
 *     │                     │                      │                      │
 *     │  ═══════ TLS 握手完成，开始加密通信 ═══════                        │
 *     │                     │                      │                      │
 *     │ Encrypted HTTP Req  │  DATA (透传密文)     │  Encrypted HTTP Req  │
 *     │────────────────────→│─────────────────────→│─────────────────────→│
 *     │                     │                      │                      │
 *     │ Encrypted HTTP Resp │  DATA (透传密文)     │  Encrypted HTTP Resp │
 *     │←────────────────────│←─────────────────────│←─────────────────────│
 * </pre>
 *
 * <h3>关键设计说明</h3>
 * <ul>
 *   <li>本处理器只负责解析 CONNECT 请求行并建立隧道，不参与后续 TLS 握手</li>
 *   <li>TLS 握手是浏览器与目标网站之间的端到端过程，代理仅做字节透传</li>
 *   <li>TLS 帧格式为 TLS Record Protocol（非 HTTP 文本），首字节 0x16=Handshake, 0x17=Application Data</li>
 *   <li>隧道建立后 pipeline 切换到 {@link RelayHandler}，本处理器从 pipeline 中移除</li>
 *   <li>代理无法解密隧道内容，仅能从 CONNECT 请求中获知目标域名和端口</li>
 * </ul>
 *
 * <h3>HTTP CONNECT 请求格式</h3>
 * <pre>
 * CONNECT www.google.com:443 HTTP/1.1\r\n
 * Host: www.google.com:443\r\n
 * \r\n
 * </pre>
 *
 * <h3>成功响应</h3>
 * <pre>
 * HTTP/1.1 200 Connection Established\r\n
 * \r\n
 * </pre>
 */
public class HttpConnectHandler extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectHandler.class);

    private static final String CONNECT_RESPONSE =
            "HTTP/1.1 200 Connection Established\r\n\r\n";
    private static final String BAD_GATEWAY =
            "HTTP/1.1 502 Bad Gateway\r\n\r\n";
    private static final String BAD_REQUEST =
            "HTTP/1.1 400 Bad Request\r\n\r\n";

    /**
     * HTTP 请求头最大长度限制，防止恶意客户端发送超大 header 耗尽内存
     */
    private static final int MAX_HEADER_SIZE = 8192;

    private final Invoker invoker;
    private final RouteRule routeRule;
    private final StreamChannelRegistry streamRegistry = StreamChannelRegistry.getInstance();

    public HttpConnectHandler(Invoker invoker, RouteRule routeRule) {
        this.invoker = invoker;
        this.routeRule = routeRule;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 防止过大的 HTTP 头消耗内存
        if (in.readableBytes() > MAX_HEADER_SIZE) {
            log.warn("HTTP header too large ({} bytes), closing connection from {}",
                    in.readableBytes(), ctx.channel().remoteAddress());
            in.skipBytes(in.readableBytes());
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
            ctx.close();
            return;
        }

        // 检查是否收到完整的 HTTP 请求头（以 \r\n\r\n 结尾）
        int readableBytes = in.readableBytes();
        // 从可读区域搜索 \r\n\r\n
        int headerEndIndex = findHeaderEnd(in);
        if (headerEndIndex < 0) {
            // 还没收完，等待更多数据（ByteToMessageDecoder 会自动累积）
            return;
        }

        // 读取完整的请求头内容
        int headerLength = headerEndIndex - in.readerIndex() + 4; // 包含 \r\n\r\n
        byte[] headerBytes = new byte[headerLength];
        in.readBytes(headerBytes);
        String data = new String(headerBytes, StandardCharsets.UTF_8);

        // 解析请求行
        String requestLine = data.substring(0, data.indexOf("\r\n"));
        String[] parts = requestLine.split(" ");

        // 请求行格式校验
        if (parts.length < 3) {
            log.warn("Malformed HTTP request line: {}", requestLine);
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
            ctx.close();
            return;
        }

        // 非 CONNECT → HTTP 普通代理模式
        if (!"CONNECT".equalsIgnoreCase(parts[0])) {
            handlePlainHttpProxy(ctx, headerBytes);
            return;
        }

        // 解析 host:port
        String hostPort = parts[1];
        String targetHost;
        int targetPort;

        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx > 0) {
            targetHost = hostPort.substring(0, colonIdx);
            try {
                targetPort = Integer.parseInt(hostPort.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                targetHost = hostPort;
                targetPort = 443; // 默认 HTTPS 端口
            }
        } else {
            targetHost = hostPort;
            targetPort = 443;
        }

        log.info("HTTP CONNECT request: {}:{} from {}", targetHost, targetPort, ctx.channel().remoteAddress());

        final String host = targetHost;
        final int port = targetPort;

        // 路由判断：走代理还是直连
        if (routeRule != null && !routeRule.shouldProxy(host)) {
            // === 直连模式 ===
            log.info("Route DIRECT: {}:{}", host, port);
            DirectRelayHandler directHandler = new DirectRelayHandler(host, port);
            directHandler.connect(ctx).addListener(future -> {
                if (future.isSuccess()) {
                    // 回复 200 Connection Established
                    ctx.writeAndFlush(Unpooled.copiedBuffer(CONNECT_RESPONSE, StandardCharsets.UTF_8));
                    // 切换到直连 relay 模式
                    ctx.pipeline().addLast("direct-relay", directHandler);
                    ctx.pipeline().remove(HttpConnectHandler.this);
                    log.info("HTTP direct tunnel established: {}:{}", host, port);
                } else {
                    // 失败详情已由 DirectRelayHandler 节流告警，这里降为 debug，避免重试风暴刷屏
                    log.debug("HTTP direct connection failed for {}:{}", host, port);
                    ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                    ctx.close();
                }
            });
        } else {
            // === 代理模式 ===
            log.info("Route PROXY: {}:{}", host, port);

            // 分配唯一 streamId 并注册浏览器 ctx
            final long streamId = streamRegistry.nextStreamId();
            streamRegistry.register(streamId, ctx);

            // 通过 ClusterInvoker 向远程发送 CONNECT 请求
            Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.CONNECT);
            invocation.setAttachment("streamId", streamId);

            // 异步建连：立即回复 200，不等待远端连接结果，减少浏览器 stalled 时间
            // 暂停读取，避免在远端连接尚未就绪时收到浏览器 TLS ClientHello
            ctx.channel().config().setAutoRead(false);

            // 先回复 200，让浏览器立即开始 TLS 握手
            ctx.writeAndFlush(Unpooled.copiedBuffer(CONNECT_RESPONSE, StandardCharsets.UTF_8));

            // 切换到 Relay 模式
            ctx.pipeline().addLast("relay", new RelayHandler(invoker, host, port, streamId));
            ctx.pipeline().remove(HttpConnectHandler.this);

            log.info("HTTP tunnel pre-established (async): {}:{}, streamId={}", host, port, streamId);

            // 异步等待远端建连结果
            invoker.invoke(invocation).whenComplete((response, throwable) -> {
                if (throwable != null) {
                    log.error("HTTP CONNECT async failed for {}:{}", host, port, throwable);
                    streamRegistry.unregister(streamId);
                    ctx.close();
                    return;
                }

                if (response != null && response.isSuccess()) {
                    // 远端建连成功，恢复读取，开始透传缓冲的 TLS 数据
                    ctx.channel().config().setAutoRead(true);
                    log.info("HTTP tunnel async connected: {}:{}, streamId={}", host, port, streamId);
                } else {
                    String errMsg = response != null ? response.getErrorMessage() : "unknown error";
                    log.warn("HTTP CONNECT async rejected for {}:{}: {}", host, port, errMsg);
                    streamRegistry.unregister(streamId);
                    ctx.close();
                }
            });
        }
    }

    /**
     * 在 ByteBuf 中搜索 \r\n\r\n 的位置
     *
     * @param buf 待搜索的 ByteBuf
     * @return \r\n\r\n 中第一个 \r 的绝对索引，未找到返回 -1
     */
    private int findHeaderEnd(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex() - 3; // 至少需要 4 字节 \r\n\r\n
        for (int i = start; i <= end; i++) {
            if (buf.getByte(i) == '\r'
                    && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r'
                    && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    // ==================== HTTP 普通代理相关方法 ====================

    /**
     * 处理 HTTP 普通代理请求（GET/POST/PUT 等非 CONNECT 方法）。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>用 {@link HttpRequestParser} 从请求中解析目标 host:port，并重写绝对 URL</li>
     *   <li>通过路由规则判断走代理还是直连</li>
     *   <li>建连成功后，将重写后的首条请求立即转发（不回 200）</li>
     *   <li>切换到 {@link HttpProxyRelayHandler} 或 {@link DirectRelayHandler} 继续透传</li>
     * </ol>
     * </p>
     *
     * @param ctx        当前 channel 上下文
     * @param rawRequest 原始 HTTP 请求头字节（包含 \r\n\r\n）
     */
    private void handlePlainHttpProxy(ChannelHandlerContext ctx, byte[] rawRequest) {
        // 1. 解析并重写
        HttpRequestParser.ParseResult result;
        try {
            result = HttpRequestParser.parse(rawRequest);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse HTTP proxy request: {}", e.getMessage());
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
            ctx.close();
            return;
        }

        String host = result.getHost();
        int port = result.getPort();
        byte[] rewritten = result.getRewritten();

        log.info("HTTP plain proxy request: {}:{} from {}", host, port, ctx.channel().remoteAddress());

        // 2. 路由判断
        if (routeRule != null && !routeRule.shouldProxy(host)) {
            // 直连模式
            handleDirectPlainProxy(ctx, host, port, rewritten);
        } else {
            // 代理模式
            handleRemotePlainProxy(ctx, host, port, rewritten);
        }
    }

    /**
     * HTTP 普通代理 —— 直连模式。
     * <p>
     * 直接建立到目标服务器的 TCP 连接，建连成功后不回复 200，
     * 而是通过 {@code fireChannelRead} 将首条请求注入 {@link DirectRelayHandler}。
     * </p>
     *
     * @param ctx       当前 channel 上下文
     * @param host      目标主机名
     * @param port      目标端口
     * @param rewritten 重写后的首条 HTTP 请求字节
     */
    private void handleDirectPlainProxy(ChannelHandlerContext ctx,
                                         String host, int port, byte[] rewritten) {
        log.info("Route DIRECT: {}:{}", host, port);
        DirectRelayHandler directHandler = new DirectRelayHandler(host, port);
        directHandler.connect(ctx).addListener(future -> {
            if (future.isSuccess()) {
                // ★ 不回 200！直接切换到 relay 模式
                ctx.pipeline().addLast("direct-relay", directHandler);
                ctx.pipeline().remove(HttpConnectHandler.this);
                // 首条请求立即发送给目标服务器
                ctx.fireChannelRead(Unpooled.wrappedBuffer(rewritten));
                log.info("HTTP plain proxy direct tunnel established: {}:{}", host, port);
            } else {
                log.debug("HTTP plain proxy direct connection failed for {}:{}", host, port);
                ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                ctx.close();
            }
        });
    }

    /**
     * HTTP 普通代理 —— 远程代理模式。
     * <p>
     * 先通过 CONNECT 在远端建立到目标服务器的 TCP 连接，
     * 成功后切换到 {@link HttpProxyRelayHandler}，由其在 handlerAdded 中发送首条请求。
     * </p>
     *
     * @param ctx       当前 channel 上下文
     * @param host      目标主机名
     * @param port      目标端口
     * @param rewritten 重写后的首条 HTTP 请求字节
     */
    private void handleRemotePlainProxy(ChannelHandlerContext ctx,
                                         String host, int port, byte[] rewritten) {
        log.info("Route PROXY: {}:{}", host, port);

        final long streamId = streamRegistry.nextStreamId();
        streamRegistry.register(streamId, ctx);

        // 先通过 CONNECT 在远端建立到目标的 TCP 连接
        Invocation connectInv = new Invocation(host, port, null, ProxyMessage.MessageType.CONNECT);
        connectInv.setAttachment("streamId", streamId);

        invoker.invoke(connectInv).whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.error("HTTP plain proxy CONNECT failed for {}:{}", host, port, throwable);
                streamRegistry.unregister(streamId);
                ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                ctx.close();
                return;
            }

            if (response != null && response.isSuccess()) {
                // ★ 不回 200！切换到 HttpProxyRelayHandler
                ctx.pipeline().addLast("http-proxy-relay",
                        new HttpProxyRelayHandler(invoker, host, port, streamId, rewritten));
                ctx.pipeline().remove(HttpConnectHandler.this);

                log.info("HTTP plain proxy tunnel established: {}:{}, streamId={}", host, port, streamId);
            } else {
                String errMsg = response != null ? response.getErrorMessage() : "unknown error";
                log.warn("HTTP plain proxy CONNECT rejected for {}:{}: {}", host, port, errMsg);
                streamRegistry.unregister(streamId);
                ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                ctx.close();
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP handler error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
