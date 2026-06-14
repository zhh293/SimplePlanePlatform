package com.proxy.local.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 直连中继 Handler —— 不经过远程代理，直接与目标建立 TCP 连接并双向转发
 * <p>
 * 当路由规则判定某个域名应该直连时，使用此 Handler 代替 RelayHandler。
 * 在浏览器和目标服务器之间建立直接的 TCP 隧道。
 * </p>
 */
public class DirectRelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DirectRelayHandler.class);

    /**
     * 直连失败负缓存：host:port -> 失败截止时间戳（毫秒）。
     * 某个目标直连失败后，在 {@link #FAIL_CACHE_TTL_MS} 内对同一目标直接快速失败，
     * 不再发起真实 TCP 连接，避免客户端反复重试导致的连接风暴与日志刷屏。
     */
    private static final ConcurrentHashMap<String, Long> FAIL_CACHE = new ConcurrentHashMap<>();
    private static final long FAIL_CACHE_TTL_MS = 30000L;
    /** 同一目标在抑制期内的告警节流：仅每隔一段时间打印一次 warn。 */
    private static final ConcurrentHashMap<String, AtomicLong> LAST_WARN_AT = new ConcurrentHashMap<>();
    private static final long WARN_THROTTLE_MS = 10000L;

    /**
     * 可选的自定义 DNS resolver，通过系统属性 proxy.dns.nameservers 配置。
     * <p>
     * TUN 模式下系统 DNS 被 FakeDNS 劫持，需要指定真实 DNS 服务器进行解析。
     * 系统代理模式下不设置此属性，使用系统默认 DNS，完全兼容原有逻辑。
     * </p>
     */
    private static final AddressResolverGroup<?> CUSTOM_RESOLVER = initResolver();

    private final String targetHost;
    private final int targetPort;
    private volatile Channel outboundChannel;

    public DirectRelayHandler(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /**
     * 根据系统属性 proxy.dns.nameservers 初始化 Netty DNS resolver。
     * 格式：逗号分隔的 IP 列表，如 "114.114.114.114,223.5.5.5"。
     * 未设置时返回 null，表示使用系统默认 DNS。
     * <p>
     * 配置的所有 DNS 服务器都会生效：使用
     * {@link SequentialDnsServerAddressStreamProvider} 按配置顺序逐个尝试，
     * 当前一个 DNS 解析失败时自动 fallback 到下一个，实现多 DNS 兜底。
     * </p>
     */
    private static AddressResolverGroup<?> initResolver() {
        String dnsServers = System.getProperty("proxy.dns.nameservers");
        if (dnsServers == null || dnsServers.trim().isEmpty()) {
            return null;
        }
        List<InetSocketAddress> dnsAddrs = new ArrayList<>();
        for (String s : dnsServers.split(",")) {
            String ip = s.trim();
            if (!ip.isEmpty()) {
                dnsAddrs.add(new InetSocketAddress(ip, 53));
            }
        }
        if (dnsAddrs.isEmpty()) {
            return null;
        }
        log.info("DirectRelay using custom DNS resolvers (sequential fallback): {}", dnsAddrs);
        return new DnsAddressResolverGroup(
                new DnsNameResolverBuilder()
                        .channelType(io.netty.channel.socket.nio.NioDatagramChannel.class)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(
                                dnsAddrs.toArray(new InetSocketAddress[0]))));
    }

    /**
     * 建立到目标服务器的直连，并开始双向转发
     *
     * @param browserCtx 浏览器端的 ChannelHandlerContext
     * @return ChannelFuture 连接完成的 future
     */
    public ChannelFuture connect(ChannelHandlerContext browserCtx) {
        final String key = targetHost + ":" + targetPort;

        // 负缓存命中：该目标近期直连失败，直接快速失败，避免反复发起真实连接造成风暴
        Long until = FAIL_CACHE.get(key);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                throttledWarn(key, "Direct connection to {} suppressed (recently failed), fast-fail", key);
                // 返回失败 future，由调用方按其原有逻辑处理（发送失败回复 / 关闭浏览器连接）
                return browserCtx.channel().newFailedFuture(
                        new IllegalStateException("direct connection suppressed: " + key));
            }
            // 已过期，清理后重新尝试
            FAIL_CACHE.remove(key);
        }

        Bootstrap b = new Bootstrap();
        b.group(browserCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new BackendHandler(browserCtx));
                    }
                });

        // TUN 模式下使用自定义 DNS resolver 绕过 FakeDNS
        if (CUSTOM_RESOLVER != null) {
            b.resolver(CUSTOM_RESOLVER);
        }

        ChannelFuture connectFuture = b.connect(targetHost, targetPort);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                outboundChannel = future.channel();
                // 连接成功，清除该目标的失败记录
                FAIL_CACHE.remove(key);
                log.debug("Direct connection established to {}:{}", targetHost, targetPort);
            } else {
                // 写入负缓存，并对告警做节流，避免日志刷屏
                FAIL_CACHE.put(key, System.currentTimeMillis() + FAIL_CACHE_TTL_MS);
                throttledWarn(key, "Direct connection failed to {}: {} (suppress for {}ms)",
                        key, future.cause().getMessage(), FAIL_CACHE_TTL_MS);
                browserCtx.close();
            }
        });
        return connectFuture;
    }

    /**
     * 对同一目标的告警进行节流：在 {@link #WARN_THROTTLE_MS} 内最多打印一次，
     * 避免大量重试请求把日志刷爆。
     */
    private static void throttledWarn(String key, String format, Object... args) {
        long now = System.currentTimeMillis();
        AtomicLong last = LAST_WARN_AT.computeIfAbsent(key, k -> new AtomicLong(0L));
        long prev = last.get();
        if (now - prev >= WARN_THROTTLE_MS && last.compareAndSet(prev, now)) {
            log.warn(format, args);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(msg);
        } else {
            // 连接还没建好或已断开，释放消息
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                    .addListener(ChannelFutureListener.CLOSE);
        }
        log.debug("Browser disconnected, closing direct connection to {}:{}", targetHost, targetPort);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Direct relay error for {}:{}: {}", targetHost, targetPort, cause.getMessage());
        ctx.close();
    }

    /**
     * 后端 Handler —— 从目标服务器读取数据写回浏览器
     */
    private static class BackendHandler extends ChannelInboundHandlerAdapter {
        private final ChannelHandlerContext browserCtx;

        BackendHandler(ChannelHandlerContext browserCtx) {
            this.browserCtx = browserCtx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (browserCtx.channel().isActive()) {
                browserCtx.writeAndFlush(msg);
            } else {
                if (msg instanceof ByteBuf) {
                    ((ByteBuf) msg).release();
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (browserCtx.channel().isActive()) {
                browserCtx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Direct backend error: {}", cause.getMessage());
            ctx.close();
        }

        private static final Logger log = LoggerFactory.getLogger(BackendHandler.class);
    }
}
