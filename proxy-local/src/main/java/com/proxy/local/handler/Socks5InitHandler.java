package com.proxy.local.handler;

import com.proxy.common.filter.Invoker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOCKS5 初始化握手 Handler —— 处理认证方法协商
 * <p>
 * SOCKS5 握手流程（RFC 1928）：
 * <pre>
 * Client → Server:
 *   +----+----------+----------+
 *   |VER | NMETHODS | METHODS  |
 *   +----+----------+----------+
 *   | 1  |    1     | 1 to 255 |
 *   +----+----------+----------+
 *
 * Server → Client:
 *   +----+--------+
 *   |VER | METHOD |
 *   +----+--------+
 *   | 1  |   1    |
 *   +----+--------+
 * </pre>
 * 本实现选择 NO AUTHENTICATION (0x00)。
 * 握手完成后替换为 Socks5ConnectHandler 处理 CONNECT 请求。
 * </p>
 */
public class Socks5InitHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Socks5InitHandler.class);

    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte NO_AUTH = 0x00;

    private final Invoker invoker;

    public Socks5InitHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() < 2) {
                // 数据不够
                return;
            }

            byte version = buf.readByte();
            if (version != SOCKS5_VERSION) {
                log.warn("Invalid SOCKS version: 0x{}", String.format("%02X", version));
                ctx.close();
                return;
            }

            int nMethods = buf.readByte() & 0xFF;
            if (buf.readableBytes() < nMethods) {
                // 数据不够，等下一次（实际上 SOCKS5 握手包很小，一般一次就到了）
                buf.resetReaderIndex();
                return;
            }

            // 跳过 methods 列表（我们统一回复 NO_AUTH）
            buf.skipBytes(nMethods);

            // 回复：选择 NO AUTHENTICATION
            ByteBuf response = Unpooled.buffer(2);
            response.writeByte(SOCKS5_VERSION);
            response.writeByte(NO_AUTH);
            ctx.writeAndFlush(response);

            log.debug("SOCKS5 auth negotiation complete (NO_AUTH) for {}", ctx.channel().remoteAddress());

            // 替换为 CONNECT 请求处理器
            ctx.pipeline().addLast("socks5-connect", new Socks5ConnectHandler(invoker));
            ctx.pipeline().remove(this);
        } finally {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS5 init error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
