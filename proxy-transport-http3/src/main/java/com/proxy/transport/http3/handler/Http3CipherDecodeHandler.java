package com.proxy.transport.http3.handler;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CryptoException;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.util.ReferenceCountUtil;

/** Reassembles cipher blocks across arbitrary HTTP/3 DATA frames. */
public final class Http3CipherDecodeHandler extends ChannelInboundHandlerAdapter {
    private final Cipher cipher;
    private final int maxCiphertextBytes;
    private ByteBuf cumulation;

    public Http3CipherDecodeHandler() {
        this(ExtensionLoader.getLoader(Cipher.class).getDefaultExtension(), 8 * 1024 * 1024 + 4096);
    }

    public Http3CipherDecodeHandler(Cipher cipher, int maxCiphertextBytes) {
        this.cipher = cipher;
        this.maxCiphertextBytes = maxCiphertextBytes;
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
                    cumulation = ctx.alloc().buffer(content.readableBytes());
                }
                cumulation.writeBytes(content, content.readerIndex(), content.readableBytes());
            }
            while (cumulation != null && cumulation.readableBytes() >= 4) {
                int length = cumulation.getInt(cumulation.readerIndex());
                if (length < 0 || length > maxCiphertextBytes) {
                    throw new IllegalArgumentException("Invalid ciphertext length: " + length);
                }
                if (cumulation.readableBytes() < 4L + length) {
                    break;
                }
                cumulation.skipBytes(4);
                byte[] ciphertext = new byte[length];
                cumulation.readBytes(ciphertext);
                byte[] plaintext = cipher.decrypt(ciphertext);
                ctx.fireChannelRead(new DefaultHttp3DataFrame(io.netty.buffer.Unpooled.wrappedBuffer(plaintext)));
            }
            if (cumulation != null) {
                if (!cumulation.isReadable()) {
                    cumulation.release();
                    cumulation = null;
                } else {
                    cumulation.discardReadBytes();
                }
            }
        } catch (RuntimeException error) {
            ctx.close();
        } finally {
            ReferenceCountUtil.release(frame);
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
