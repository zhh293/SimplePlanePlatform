package com.proxy.transport.http3.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.util.ReferenceCountUtil;

/** Decodes a byte stream, independent of HTTP/3 DATA frame boundaries. */
public final class Http3ProxyMessageDecoder extends ChannelInboundHandlerAdapter {
    private static final int FIXED_HEADER_SIZE = 28;
    private final Codec codec;
    private final int maxMessageBytes;
    private CompositeByteBuf cumulation;

    public Http3ProxyMessageDecoder() {
        this(ExtensionLoader.getLoader(Codec.class).getDefaultExtension(), 8 * 1024 * 1024);
    }

    public Http3ProxyMessageDecoder(Codec codec, int maxMessageBytes) {
        this.codec = codec;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http3DataFrame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Http3DataFrame frame = (Http3DataFrame) msg;
        try {
            ByteBuf content = frame.content();
            if (content.isReadable()) {
                if (cumulation == null) {
                    cumulation = ctx.alloc().compositeBuffer();
                }
                cumulation.addComponent(true, content.retain());
            }
            decodeAvailable(ctx);
        } catch (Throwable error) {
            // A malformed stream must not reach ExchangeHandler.onError(), which is connection-wide.
            closeStream(ctx, error);
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    private void decodeAvailable(ChannelHandlerContext ctx) throws Exception {
        while (cumulation != null && cumulation.readableBytes() >= FIXED_HEADER_SIZE) {
            int index = cumulation.readerIndex();
            int hostLength = cumulation.getUnsignedShort(index + 18);
            long headerLength = (long) FIXED_HEADER_SIZE + hostLength;
            if (headerLength > maxMessageBytes || cumulation.readableBytes() < headerLength) {
                if (headerLength > maxMessageBytes) {
                    throw new IllegalArgumentException("ProxyMessage header exceeds configured limit");
                }
                return;
            }
            int dataLength = cumulation.getInt(index + 24 + hostLength);
            if (dataLength < 0) {
                throw new IllegalArgumentException("Negative ProxyMessage data length: " + dataLength);
            }
            long totalLength = headerLength + dataLength;
            if (totalLength > maxMessageBytes || totalLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("ProxyMessage length exceeds configured limit: " + totalLength);
            }
            if (cumulation.readableBytes() < totalLength) {
                return;
            }
            byte[] bytes = new byte[(int) totalLength];
            cumulation.readBytes(bytes);
            ProxyMessage message = codec.decode(bytes);
            if (message == null || message.getType() == null) {
                throw new IllegalArgumentException("Invalid ProxyMessage type");
            }
            ctx.fireChannelRead(message);
        }
        if (cumulation != null) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                cumulation = null;
            } else {
                cumulation.discardReadComponents();
            }
        }
    }

    private void closeStream(ChannelHandlerContext ctx, Throwable error) {
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releaseCumulation();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseCumulation();
        super.channelInactive(ctx);
    }

    private void releaseCumulation() {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }
}
