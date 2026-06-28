package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 CONNECT 请求处理器
 * <p>
 * SOCKS5 CONNECT 请求格式（RFC 1928）：
 * <pre>
 * +----+-----+-------+------+----------+----------+
 * |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
 * +----+-----+-------+------+----------+----------+
 * | 1  |  1  | X'00' |  1   | Variable |    2     |
 * +----+-----+-------+------+----------+----------+
 *
 * ATYP:
 *   0x01 = IPv4 (4 bytes)
 *   0x03 = Domain (1 byte len + domain)
 *   0x04 = IPv6 (16 bytes)
 *
 * CMD:
 *   0x01 = CONNECT
 * </pre>
 * <p>
 * 收到 CONNECT 后，通过 ClusterInvoker 向远程发送 CONNECT 请求，
 * 远程连接目标成功后回复 SOCKS5 成功响应，然后切换到 RelayHandler 双向转发。
 * </p>
 */
public class Socks5ConnectHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Socks5ConnectHandler.class);

    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;

    // SOCKS5 Reply codes
    private static final byte REP_SUCCESS = 0x00;
    private static final byte REP_GENERAL_FAILURE = 0x01;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CMD_NOT_SUPPORTED = 0x07;
    private static final byte REP_ATYP_NOT_SUPPORTED = 0x08;

    private final Invoker invoker;
    private final RouteRule routeRule;
    private final StreamChannelRegistry streamRegistry = StreamChannelRegistry.getInstance();

    public Socks5ConnectHandler(Invoker invoker, RouteRule routeRule) {
        this.invoker = invoker;
        this.routeRule = routeRule;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() < 4) {
                return;
            }

            byte version = buf.readByte();
            byte cmd = buf.readByte();
            buf.readByte(); // RSV
            byte atyp = buf.readByte();

            if (version != SOCKS5_VERSION) {
                log.warn("Invalid SOCKS5 version in CONNECT: 0x{}", String.format("%02X", version));
                sendReply(ctx, REP_GENERAL_FAILURE);
                ctx.close();
                return;
            }

            if (cmd != CMD_CONNECT) {
                log.warn("Unsupported SOCKS5 command: 0x{}", String.format("%02X", cmd));
                sendReply(ctx, REP_CMD_NOT_SUPPORTED);
                ctx.close();
                return;
            }

            // 解析目标地址
            String targetHost;
            switch (atyp) {
                case ATYP_IPV4:
                    if (buf.readableBytes() < 6) return;
                    targetHost = (buf.readByte() & 0xFF) + "." +
                                 (buf.readByte() & 0xFF) + "." +
                                 (buf.readByte() & 0xFF) + "." +
                                 (buf.readByte() & 0xFF);
                    break;
                case ATYP_DOMAIN:
                    if (buf.readableBytes() < 1) return;
                    int domainLen = buf.readByte() & 0xFF;
                    if (buf.readableBytes() < domainLen + 2) return;
                    byte[] domainBytes = new byte[domainLen];
                    buf.readBytes(domainBytes);
                    targetHost = new String(domainBytes, StandardCharsets.UTF_8);
                    break;
                case ATYP_IPV6:
                    if (buf.readableBytes() < 18) return;
                    byte[] ipv6 = new byte[16];
                    buf.readBytes(ipv6);
                    targetHost = formatIpv6(ipv6);
                    break;
                default:
                    log.warn("Unsupported address type: 0x{}", String.format("%02X", atyp));
                    sendReply(ctx, REP_ATYP_NOT_SUPPORTED);
                    ctx.close();
                    return;
            }

            int targetPort = buf.readUnsignedShort();

            log.info("SOCKS5 CONNECT request: {}:{} from {}", targetHost, targetPort, ctx.channel().remoteAddress());

            final String host = targetHost;
            final int port = targetPort;

            // 路由判断：走代理还是直连
            if (routeRule != null && !routeRule.shouldProxy(host)) {
                // === 直连模式 ===
                log.info("Route DIRECT (SOCKS5): {}:{}", host, port);
                DirectRelayHandler directHandler = new DirectRelayHandler(host, port);
                directHandler.connect(ctx).addListener(future -> {
                    if (future.isSuccess()) {
                        sendReply(ctx, REP_SUCCESS);
                        ctx.pipeline().addLast("direct-relay", directHandler);
                        ctx.pipeline().remove(Socks5ConnectHandler.this);
                        log.info("SOCKS5 direct tunnel established: {}:{}", host, port);
                    } else {
                        // 失败详情已由 DirectRelayHandler 节流告警，这里降为 debug，避免重试风暴刷屏
                        log.debug("SOCKS5 direct connection failed for {}:{}", host, port);
                        sendReply(ctx, REP_HOST_UNREACHABLE);
                        ctx.close();
                    }
                });
            } else {
                // === 代理模式 ===
                log.info("Route PROXY (SOCKS5): {}:{}", host, port);

                // 分配唯一 streamId 并注册浏览器 ctx
                final long streamId = streamRegistry.nextStreamId();
                streamRegistry.register(streamId, ctx);

                // 通过 ClusterInvoker 向远程发送 CONNECT 请求
                Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.CONNECT);
                invocation.setAttachment("streamId", streamId);

                invoker.invoke(invocation).whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        log.error("CONNECT failed for {}:{}", host, port, throwable);
                        streamRegistry.unregister(streamId);
                        sendReply(ctx, REP_HOST_UNREACHABLE);
                        ctx.close();
                        return;
                    }

                    if (response != null && response.isSuccess()) {
                        // 连接成功，回复 SOCKS5 成功
                        sendReply(ctx, REP_SUCCESS);

                        // 切换到 Relay 模式
                        ctx.pipeline().addLast("relay", new RelayHandler(invoker, host, port, streamId));
                        ctx.pipeline().remove(Socks5ConnectHandler.this);

                        log.info("SOCKS5 tunnel established: {}:{}, streamId={}", host, port, streamId);
                    } else {
                        String errMsg = response != null ? response.getErrorMessage() : "unknown error";
                        log.warn("CONNECT rejected for {}:{}: {}", host, port, errMsg);
                        streamRegistry.unregister(streamId);
                        sendReply(ctx, REP_HOST_UNREACHABLE);
                        ctx.close();
                    }
                });
            }
        } finally {
            buf.release();
        }
    }

    /**
     * 发送 SOCKS5 回复
     * <pre>
     * +----+-----+-------+------+----------+----------+
     * |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
     * +----+-----+-------+------+----------+----------+
     * | 1  |  1  | X'00' |  1   |    4     |    2     |
     * +----+-----+-------+------+----------+----------+
     * </pre>
     */
    private void sendReply(ChannelHandlerContext ctx, byte rep) {
        ByteBuf reply = Unpooled.buffer(10);
        reply.writeByte(SOCKS5_VERSION);
        reply.writeByte(rep);
        reply.writeByte(0x00); // RSV
        reply.writeByte(ATYP_IPV4); // ATYP = IPv4
        reply.writeInt(0); // BND.ADDR = 0.0.0.0
        reply.writeShort(0); // BND.PORT = 0
        ctx.writeAndFlush(reply);
    }

    private String formatIpv6(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
        }
        return sb.toString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS5 CONNECT error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
