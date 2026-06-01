package com.proxy.local.handler;

import com.proxy.common.filter.Invoker;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 协议检测器 —— 根据客户端首字节自动识别代理协议
 * <p>
 * SOCKS5 协议首字节为 0x05（版本号），
 * HTTP CONNECT 首字节为 ASCII 字母（如 'C' = 0x43）。
 * </p>
 * <p>
 * 检测完成后动态添加对应的 Handler 到 pipeline，然后移除自身。
 * </p>
 */
public class ProtocolDetector extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDetector.class);

    private static final byte SOCKS5_VERSION = 0x05;

    private final Invoker invoker;
    private final boolean httpProxyEnabled;

    public ProtocolDetector(Invoker invoker, boolean httpProxyEnabled) {
        this.invoker = invoker;
        this.httpProxyEnabled = httpProxyEnabled;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        if (buf.readableBytes() < 1) {
            // 数据不够，等下一次
            return;
        }

        // 窥探首字节（不消费）
        byte firstByte = buf.getByte(buf.readerIndex());

        if (firstByte == SOCKS5_VERSION) {
            // SOCKS5 协议
            log.debug("Detected SOCKS5 protocol from {}", ctx.channel().remoteAddress());
            ctx.pipeline().addLast("socks5-init", new Socks5InitHandler(invoker));
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        } else if (httpProxyEnabled && isHttpMethod(firstByte)) {
            // HTTP CONNECT 协议
            log.debug("Detected HTTP proxy protocol from {}", ctx.channel().remoteAddress());
            ctx.pipeline().addLast("http-connect", new HttpConnectHandler(invoker));
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        } else {
            // 未知协议，关闭连接
            log.warn("Unknown protocol (first byte: 0x{}) from {}, closing",
                    String.format("%02X", firstByte), ctx.channel().remoteAddress());
            buf.release();
            ctx.close();
        }
    }

    /**
     * 判断首字节是否为 HTTP 方法的起始字符
     * 支持：CONNECT, GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH
     */
    private boolean isHttpMethod(byte b) {
        return b == 'C' || b == 'G' || b == 'P' || b == 'D' ||
               b == 'H' || b == 'O' || b == 'T';
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Protocol detection error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
