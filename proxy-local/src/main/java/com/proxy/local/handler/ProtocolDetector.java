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
 *
 * <h3>代理隧道与 TLS 握手的完整交互流程</h3>
 * <p>
 * 当浏览器配置了本地代理（HTTP/SOCKS5）后，访问 HTTPS 网站的完整流程如下：
 * </p>
 * <pre>
 * 1. 浏览器 → proxy-local：建立明文 TCP 连接（本步由本类嗅探协议类型）
 *
 * 2. 浏览器 → proxy-local：发送 CONNECT example.com:443 请求（HTTP 代理）
 *    或通过 SOCKS5 握手告知目标地址
 *
 * 3. proxy-local → proxy-remote（经 HTTP/2 隧道）：转发连接请求
 *
 * 4. proxy-remote → example.com:443：建立裸 TCP 连接（仅 TCP，无 TLS）
 *
 * 5. proxy-local → 浏览器：回复 "200 Connection Established"，隧道建立完成
 *
 * 6. 浏览器 → [隧道透传] → example.com：开始端到端 TLS 握手
 *    - 浏览器发送 TLS ClientHello（二进制 TLS record，非 HTTP 格式）
 *    - 经 proxy-local → HTTP/2 stream DATA 帧 → proxy-remote → example.com
 *    - example.com 回复 ServerHello + 证书 + 公钥
 *    - 原路返回：example.com → proxy-remote → HTTP/2 stream → proxy-local → 浏览器
 *    - 双方协商对称密钥，TLS 握手完成
 *
 * 7. 浏览器 ←→ example.com：后续所有 HTTP 请求/响应均在 TLS 加密层内传输，
 *    对代理而言全部是不透明的二进制数据，原样透传
 * </pre>
 * <p>
 * 关键点：代理不参与 TLS 握手，不持有目标网站的证书或密钥，
 * 因此无法解密隧道内的数据。TLS 握手帧使用 TLS Record Protocol 格式
 * （5 字节头：ContentType + Version + Length），与 HTTP 报文格式完全不同。
 * </p>
 */
public class ProtocolDetector extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDetector.class);

    private static final byte SOCKS5_VERSION = 0x05;

    private final Invoker invoker;
    private final boolean httpProxyEnabled;
    private final RouteRule routeRule;

    public ProtocolDetector(Invoker invoker, boolean httpProxyEnabled, RouteRule routeRule) {
        this.invoker = invoker;
        this.httpProxyEnabled = httpProxyEnabled;
        this.routeRule = routeRule;
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
            ctx.pipeline().addLast("socks5-init", new Socks5InitHandler(invoker, routeRule));
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        } else if (httpProxyEnabled && isHttpMethod(firstByte)) {
            // HTTP CONNECT 协议
            log.debug("Detected HTTP proxy protocol from {}", ctx.channel().remoteAddress());
            ctx.pipeline().addLast("http-connect", new HttpConnectHandler(invoker, routeRule));
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
