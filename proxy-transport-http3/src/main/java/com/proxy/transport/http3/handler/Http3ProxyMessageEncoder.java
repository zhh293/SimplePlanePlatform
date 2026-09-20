package com.proxy.transport.http3.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3DataFrame;

import java.util.List;

/** Encodes one ProxyMessage as one HTTP/3 DATA frame. Frame boundaries are not a protocol contract. */
public final class Http3ProxyMessageEncoder extends MessageToMessageEncoder<ProxyMessage> {
    private final Codec codec;
    private final int maxMessageBytes;

    public Http3ProxyMessageEncoder() {
        this(ExtensionLoader.getLoader(Codec.class).getDefaultExtension(), 8 * 1024 * 1024);
    }

    public Http3ProxyMessageEncoder(Codec codec, int maxMessageBytes) {
        this.codec = codec;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ProxyMessage message, List<Object> out) throws Exception {
        try {
            byte[] encoded = codec.encode(message);
            if (encoded.length > maxMessageBytes) {
                throw new IllegalArgumentException("ProxyMessage exceeds HTTP/3 limit: " + encoded.length);
            }
            out.add(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(encoded)));
        } catch (RuntimeException error) {
            // Encoding failures are scoped to this request stream. In particular, do not let
            // MessageToMessageEncoder propagate into ExchangeHandler.onError()/failAll().
            ctx.close();
        }
    }
}
