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
 * HTTP CONNECT 隧道处理器
 * <p>
 * 处理 HTTP 代理的 CONNECT 方法，建立隧道后切换到 RelayHandler。
 * </p>
 * <p>
 * 继承 {@link ByteToMessageDecoder}，利用 Netty 内置的累积缓冲机制，
 * 自动处理 TCP 粘包/拆包问题和 ByteBuf 的生命周期管理，
 * 避免手动管理 cumulation 可能导致的内存泄漏。
 * </p>
 * <p>
 * HTTP CONNECT 请求格式：
 * <pre>
 * CONNECT www.google.com:443 HTTP/1.1\r\n
 * Host: www.google.com:443\r\n
 * \r\n
 * </pre>
 * <p>
 * 成功响应：
 * <pre>
 * HTTP/1.1 200 Connection Established\r\n
 * \r\n
 * </pre>
 * </p>
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

    public HttpConnectHandler(Invoker invoker) {
        this.invoker = invoker;
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

        if (parts.length < 3 || !"CONNECT".equalsIgnoreCase(parts[0])) {
            // 不是 CONNECT 方法，暂不支持普通 HTTP 代理
            log.warn("Non-CONNECT HTTP request not supported: {}", requestLine);
            ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_REQUEST, StandardCharsets.UTF_8));
            ctx.close();
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

        // 通过 ClusterInvoker 向远程发送 CONNECT 请求
        Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.CONNECT);
        final String host = targetHost;
        final int port = targetPort;

        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.error("HTTP CONNECT failed for {}:{}", host, port, throwable);
                ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                ctx.close();
                return;
            }

            if (response != null && response.isSuccess()) {
                // 回复 200 Connection Established
                ctx.writeAndFlush(Unpooled.copiedBuffer(CONNECT_RESPONSE, StandardCharsets.UTF_8));

                // 切换到 Relay 模式：先添加 RelayHandler，再移除自身
                // 注意：ByteToMessageDecoder 移除时会自动将未读数据 fire 给下一个 Handler
                ctx.pipeline().addLast("relay", new RelayHandler(invoker, host, port));
                ctx.pipeline().remove(HttpConnectHandler.this);

                log.info("HTTP tunnel established: {}:{}", host, port);
            } else {
                String errMsg = response != null ? response.getErrorMessage() : "unknown error";
                log.warn("HTTP CONNECT rejected for {}:{}: {}", host, port, errMsg);
                ctx.writeAndFlush(Unpooled.copiedBuffer(BAD_GATEWAY, StandardCharsets.UTF_8));
                ctx.close();
            }
        });
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP CONNECT error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
