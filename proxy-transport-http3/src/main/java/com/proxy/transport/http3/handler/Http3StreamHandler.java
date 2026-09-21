package com.proxy.transport.http3.handler;

import com.proxy.common.model.URL;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.ReferenceCountUtil;

/** Handles the HTTP/3 request/response headers while leaving ProxyMessage to the normal ExchangeHandler. */
public final class Http3StreamHandler extends ChannelDuplexHandler {
    private final boolean server;
    private final URL url;
    private final Runnable readyCallback;
    private final Runnable closedCallback;
    private final Runnable writableCallback;
    private boolean headersReady;

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
        if (!server) {
            DefaultHttp3Headers headers = new DefaultHttp3Headers();
            headers.method("POST").scheme("https").authority(url.getHost() + ":" + url.getPort())
                    .path("/proxy").set("content-type", "application/octet-stream")
                    .set("x-plane-protocol", "proxy-message-v1");
            ctx.writeAndFlush(new DefaultHttp3HeadersFrame(headers));
        }
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
                ctx.close();
                return;
            }
            DefaultHttp3Headers response = new DefaultHttp3Headers();
            response.status("200").set("content-type", "application/octet-stream")
                    .set("x-plane-protocol", "proxy-message-v1");
            headersReady = true;
            ctx.writeAndFlush(new DefaultHttp3HeadersFrame(response));
            // Http3HeadersFrame is reference-counted in Netty 4.2.  The frame is
            // consumed by this handler and must not remain retained for every
            // request stream.
            ReferenceCountUtil.release(msg);
            return;
        }
        CharSequence status = frame.headers().status();
        if (!"200".contentEquals(status) || protocol == null || !"proxy-message-v1".contentEquals(protocol)) {
            ReferenceCountUtil.release(msg);
            ctx.close();
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
        ctx.close();
    }
}
