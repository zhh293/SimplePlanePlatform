package com.proxy.transport.http3.handler;

import com.proxy.common.model.URL;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.concurrent.TimeUnit;

/** Handles the HTTP/3 request/response headers while leaving ProxyMessage to the normal ExchangeHandler. */
public final class Http3StreamHandler extends ChannelDuplexHandler {
    // One shared daemon wheel; tasks only inspect terminal stream state, never idle duration.
    private static final HashedWheelTimer CLEANUP_TIMER = new HashedWheelTimer(r -> {
        Thread thread = new Thread(r, "http3-stream-cleanup");
        thread.setDaemon(true);
        return thread;
    }, 100, TimeUnit.MILLISECONDS, 512);
    private final boolean server;
    private final URL url;
    private final Runnable readyCallback;
    private final Runnable closedCallback;
    private final Runnable writableCallback;
    public static final io.netty.util.AttributeKey<Boolean> UDP_OPEN_SUPPORTED =
            io.netty.util.AttributeKey.valueOf("proxy.h3.udpOpenSupported");
    private boolean headersReady;
    private volatile Timeout cleanupTimeout;
    private volatile boolean removed;

    public Http3StreamHandler(boolean server, URL url, Runnable readyCallback, Runnable closedCallback) {
        this(server, url, readyCallback, closedCallback, null);
    }

    public Http3StreamHandler(boolean server, URL url, Runnable readyCallback,
                              Runnable closedCallback, Runnable writableCallback) {
        this.server = server;
        this.url = url;
        this.readyCallback = readyCallback;
        this.closedCallback = closedCallback;
        this.writableCallback = writableCallback;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleCleanup(ctx);
        if (!server) {
            DefaultHttp3Headers headers = new DefaultHttp3Headers();
            headers.method("POST").scheme("https").authority(url.getHost() + ":" + url.getPort())
                    .path("/proxy").set("content-type", "application/octet-stream")
                    .set("x-plane-protocol", "proxy-message-v1");
            ctx.writeAndFlush(new DefaultHttp3HeadersFrame(headers));
        }
    }

    private void scheduleCleanup(ChannelHandlerContext ctx) {
        if (removed || !ctx.channel().isOpen()) return;
        cleanupTimeout = CLEANUP_TIMER.newTimeout(timeout -> {
            if (removed || !ctx.channel().isOpen()) return;
            try {
                ctx.executor().execute(() -> {
                    if (removed || !ctx.channel().isOpen()) return;
                    if (ctx.channel() instanceof QuicStreamChannel) {
                        QuicStreamChannel stream = (QuicStreamChannel) ctx.channel();
                        if (stream.isInputShutdown() || stream.isOutputShutdown()) {
                            stream.shutdown(io.netty.handler.codec.http3.Http3ErrorCode.H3_REQUEST_CANCELLED.code())
                                    .addListener(ignored -> ctx.close());
                            return;
                        }
                    }
                    scheduleCleanup(ctx);
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                cancelCleanup();
            }
        }, Math.max(100, url.getParameter("http3.streamCleanupIntervalMs", 10000)), TimeUnit.MILLISECONDS);
    }

    private void cancelCleanup() {
        removed = true;
        Timeout timeout = cleanupTimeout;
        cleanupTimeout = null;
        if (timeout != null) timeout.cancel();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        // Deliver the event to record decoders first so truncated final records are rejected.
        super.userEventTriggered(ctx, event);
        if (event instanceof ChannelInputShutdownEvent) ctx.channel().close();
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        // Channel.close alone can leave native receive-side state behind. Explicitly
        // terminate both directions whenever the application abandons a request stream.
        if (ctx.channel() instanceof QuicStreamChannel) {
            QuicStreamChannel stream = (QuicStreamChannel) ctx.channel();
            if (!stream.isShutdown()) {
                stream.shutdown(io.netty.handler.codec.http3.Http3ErrorCode.H3_NO_ERROR.code())
                        .addListener(ignored -> ctx.close(promise));
                return;
            }
        }
        ctx.close(promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelCleanup();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http3HeadersFrame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Http3HeadersFrame frame = (Http3HeadersFrame) msg;
        CharSequence protocol = frame.headers().get("x-plane-protocol");
        if (server) {
            CharSequence method = frame.headers().method();
            CharSequence path = frame.headers().path();
            if (!"POST".contentEquals(method) || !"/proxy".contentEquals(path)
                    || protocol == null || !"proxy-message-v1".contentEquals(protocol)) {
                ReferenceCountUtil.release(msg);
                ctx.channel().close();
                return;
            }
            DefaultHttp3Headers response = new DefaultHttp3Headers();
            response.status("200").set("content-type", "application/octet-stream")
                    .set("x-plane-protocol", "proxy-message-v1");
            response.set("x-plane-udp-open", "1");
            headersReady = true;
            ctx.writeAndFlush(new DefaultHttp3HeadersFrame(response));
            // Http3HeadersFrame is reference-counted in Netty 4.2.  The frame is
            // consumed by this handler and must not remain retained for every
            // request stream.
            ReferenceCountUtil.release(msg);
            return;
        }
        ctx.channel().attr(UDP_OPEN_SUPPORTED).set("1".contentEquals(
                frame.headers().get("x-plane-udp-open", "")));
        CharSequence status = frame.headers().status();
        if (!"200".contentEquals(status) || protocol == null || !"proxy-message-v1".contentEquals(protocol)) {
            ReferenceCountUtil.release(msg);
            ctx.channel().close();
            return;
        }
        headersReady = true;
        if (readyCallback != null) {
            readyCallback.run();
        }
        // The response headers are consumed here; only DATA frames belong to
        // the proxy-message decoder below this handler.
        ReferenceCountUtil.release(msg);
    }

    public boolean isHeadersReady() {
        return headersReady;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelCleanup();
        if (closedCallback != null) {
            closedCallback.run();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (writableCallback != null) {
            writableCallback.run();
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Stream-local failure: do not call MessageHandler.onError()/DefaultFuture.failAll().
        ctx.channel().close();
    }
}
