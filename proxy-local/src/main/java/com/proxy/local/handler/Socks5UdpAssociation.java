package com.proxy.local.handler;

import com.proxy.common.exchange.DatagramReceiver;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.transport.netty.udp.UdpOutboundChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** One loopback SOCKS control association; all mutable state lives on its EventLoop. */
final class Socks5UdpAssociation extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Semaphore ASSOCIATIONS = new Semaphore(512), TARGETS = new Semaphore(1024);
    private static final byte[] CAPABILITY = "udp-v1".getBytes(StandardCharsets.US_ASCII);
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final Invoker invoker;
    private final RouteRule routes;
    private final byte[] requestedIp;
    private final int requestedPort;
    private final Map<String, Target> targets = new HashMap<>();
    private ChannelHandlerContext control;
    private Channel relay;
    private InetSocketAddress client;
    private InetAddress expectedIp;
    private boolean closed, permit, tunProxy;
    private long activity = System.nanoTime();
    private ScheduledFuture<?> sweep;

    Socks5UdpAssociation(Invoker invoker, RouteRule routes, byte[] ip, int port) {
        this.invoker = invoker; this.routes = routes; requestedIp = ip; requestedPort = port;
    }
    static void reply(ChannelHandlerContext ctx, int status, InetSocketAddress relay) {
        ByteBuf out = Unpooled.buffer(10).writeByte(5).writeByte(status).writeByte(0).writeByte(1);
        out.writeBytes(relay == null ? new byte[4] : relay.getAddress().getAddress());
        ctx.writeAndFlush(out.writeShort(relay == null ? 0 : relay.getPort()));
    }
    @Override public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        control = ctx;
        if (!(ctx.channel().remoteAddress() instanceof InetSocketAddress)) { ctx.close(); return; }
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        InetAddress claimed = InetAddress.getByAddress(requestedIp);
        expectedIp = remote.getAddress();
        if (!expectedIp.isLoopbackAddress() || expectedIp.getAddress().length != 4
                || (!claimed.isAnyLocalAddress() && !claimed.equals(expectedIp)) || !ASSOCIATIONS.tryAcquire()) {
            reply(ctx, 2, null); ctx.close(); return;
        }
        permit = true;
        tunProxy = "127.0.0.2".equals(expectedIp.getHostAddress());
        new Bootstrap().group(ctx.channel().eventLoop()).channel(NioDatagramChannel.class)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(65535))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 65536))
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override protected void channelRead0(ChannelHandlerContext c, DatagramPacket packet) { receive(packet); }
                    @Override public void exceptionCaught(ChannelHandlerContext c, Throwable error) { control.close(); }
                }).bind("127.0.0.1", 0).addListener((ChannelFutureListener) bound -> {
                    if (!bound.isSuccess() || closed || !ctx.channel().isActive()) {
                        bound.channel().close(); if (!closed) { reply(ctx, 1, null); ctx.close(); } return;
                    }
                    relay = bound.channel();
                    reply(ctx, 0, (InetSocketAddress) relay.localAddress());
                    sweep = ctx.executor().scheduleAtFixedRate(() -> {
                        long now = System.nanoTime();
                        for (Target target : new ArrayList<>(targets.values()))
                            if (now - target.lastActivity >= IDLE_NANOS) target.close(true);
                        if (now - activity >= IDLE_NANOS) ctx.close();
                    }, 5, 5, TimeUnit.SECONDS);
                });
    }
    private void receive(DatagramPacket packet) {
        InetSocketAddress source = packet.sender();
        if (closed || !expectedIp.equals(source.getAddress())
                || (requestedPort != 0 && requestedPort != source.getPort())
                || (client != null && !client.equals(source))) return;
        Socks5UdpCodec.Datagram datagram = Socks5UdpCodec.decode(packet.content());
        if (datagram == null) return;
        if (client == null) client = source;
        activity = System.nanoTime();
        InetSocketAddress own = (InetSocketAddress) relay.localAddress();
        if ((datagram.host.equals(own.getAddress().getHostAddress()) || datagram.host.equals("localhost"))
                && (datagram.port == own.getPort() || datagram.port == ((InetSocketAddress) control.channel().localAddress()).getPort())) return;
        String key = datagram.host + ":" + datagram.port;
        Target target = targets.get(key);
        if (target == null) {
            RouteDecision decision = routes == null ? null : routes.decide(datagram.host, datagram.port, RouteContext.Protocol.UDP);
            if (!tunProxy && decision != null && decision.isReject()) return;
            if (targets.size() >= 64 || !TARGETS.tryAcquire()) return;
            target = new Target(key, datagram.host, datagram.port, tunProxy || decision == null || decision.isProxy());
            targets.put(key, target);
            target.enqueue(datagram.data);
            target.open();
        } else target.enqueue(datagram.data);
    }
    private void execute(Runnable action) {
        try {
            if (control.executor().inEventLoop()) action.run(); else control.executor().execute(action);
        } catch (java.util.concurrent.RejectedExecutionException stopped) { /* EventLoop shutdown closes owned channels. */ }
    }
    @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf data) { ctx.close(); }
    @Override public void channelInactive(ChannelHandlerContext ctx) {
        closed = true;
        if (sweep != null) sweep.cancel(false);
        if (relay != null) relay.close();
        for (Target target : new ArrayList<>(targets.values())) target.close(true);
        if (permit) { permit = false; ASSOCIATIONS.release(); }
        ctx.fireChannelInactive();
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }

    private final class Target implements DatagramReceiver {
        final String key, host;
        final int port;
        final boolean proxy;
        final long id = StreamChannelRegistry.getInstance().nextStreamId();
        final Deque<byte[]> pending = new ArrayDeque<>();
        int pendingBytes;
        boolean active, done;
        long lastActivity = System.nanoTime();
        CompletableFuture<Response> opening;
        ChannelFuture connecting;
        Channel direct;
        Target(String key, String host, int port, boolean proxy) {
            this.key = key; this.host = host; this.port = port; this.proxy = proxy;
        }
        Invocation message(ProxyMessage.MessageType type, byte[] bytes) {
            Invocation call = new Invocation(host, port, bytes, type);
            call.setAttachment("streamId", id);
            call.setAttachment("route", "proxy"); // The UDP ingress already made the final route decision.
            return call;
        }
        void open() {
            if (proxy) {
                StreamChannelRegistry.getInstance().getDatagramMap().put(id, this);
                try {
                    opening = invoker.invoke(message(ProxyMessage.MessageType.UDP_CONNECT, CAPABILITY));
                    opening.whenComplete((response, error) -> execute(() -> {
                        if (done) return;
                        if (error != null || response == null || !response.isSuccess()
                                || !Arrays.equals(CAPABILITY, response.getData())) { close(true); return; }
                        ready();
                    }));
                } catch (RuntimeException error) { close(true); }
            } else {
                connecting = new Bootstrap().group(control.channel().eventLoop()).channelFactory(UdpOutboundChannel::create)
                        .resolver(UdpOutboundChannel.RESOLVER)
                        .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(65535))
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 65536))
                        .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                                if (!packet.sender().equals(UdpOutboundChannel.peer(ctx.channel()))) return;
                                byte[] data = new byte[packet.content().readableBytes()]; packet.content().readBytes(data); onDatagram(data);
                            }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { close(false); }
                            @Override public void channelInactive(ChannelHandlerContext ctx) { close(false); }
                        }).connect(host, port);
                connecting.addListener((ChannelFutureListener) result -> {
                    if (!result.isSuccess() || done || closed) { result.channel().close(); close(false); return; }
                    direct = result.channel();
                    try { UdpOutboundChannel.prepare(direct); ready(); }
                    catch (java.io.IOException failure) { close(false); }
                });
            }
        }
        void ready() {
            active = true;
            while (!pending.isEmpty() && !done) send(pending.removeFirst());
            pendingBytes = 0;
        }
        void enqueue(byte[] data) {
            if (done) return;
            lastActivity = System.nanoTime();
            if (active) { send(data); return; }
            if (pending.size() >= 64 || pendingBytes + data.length > 65536) return;
            pending.addLast(data); pendingBytes += data.length;
        }
        void send(byte[] data) {
            if (done) return;
            if (proxy) {
                try {
                    invoker.invoke(message(ProxyMessage.MessageType.DATA, data)).whenComplete((response, error) -> {
                        if (error != null || response == null || !response.isSuccess()) execute(() -> close(true));
                    });
                } catch (RuntimeException failure) { close(true); }
            } else if (direct != null && direct.isActive() && direct.isWritable()) {
                direct.writeAndFlush(Unpooled.wrappedBuffer(data)).addListener(f -> { if (!f.isSuccess()) close(false); });
            }
        }
        @Override public void onDatagram(byte[] data) {
            execute(() -> {
                if (done || closed || client == null || data.length > Socks5UdpCodec.MAX_PAYLOAD || !relay.isWritable()) return;
                activity = lastActivity = System.nanoTime();
                relay.writeAndFlush(new DatagramPacket(Socks5UdpCodec.encode(host, port, data), client));
            });
        }
        @Override public void onClosed(Throwable cause) { execute(() -> close(false)); }
        void close(boolean notify) {
            if (done) return;
            done = true; active = false;
            targets.remove(key, this);
            StreamChannelRegistry.getInstance().getDatagramMap().remove(id, this);
            pending.clear(); pendingBytes = 0;
            if (opening != null && !opening.isDone()) opening.cancel(false);
            if (connecting != null) { connecting.cancel(false); connecting.channel().close(); }
            if (direct != null) direct.close();
            if (proxy && notify) {
                try { invoker.invoke(message(ProxyMessage.MessageType.DISCONNECT, null)); } catch (RuntimeException ignored) { }
            }
            TARGETS.release();
        }
    }
}
