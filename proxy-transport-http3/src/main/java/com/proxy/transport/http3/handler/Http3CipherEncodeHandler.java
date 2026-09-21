package com.proxy.transport.http3.handler;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3DataFrame;

import java.util.List;

/** Adds a 4-byte ciphertext length before each application cipher block. */
public final class Http3CipherEncodeHandler extends MessageToMessageEncoder<Http3DataFrame> {
    private final Cipher cipher;
    private final int maxCiphertextBytes;

    public Http3CipherEncodeHandler() {
        this(ExtensionLoader.getLoader(Cipher.class).getDefaultExtension(), 8 * 1024 * 1024 + 4096);
    }

    public Http3CipherEncodeHandler(Cipher cipher, int maxCiphertextBytes) {
        this.cipher = cipher;
        this.maxCiphertextBytes = maxCiphertextBytes;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Http3DataFrame frame, List<Object> out) {
        ByteBuf content = frame.content();
        byte[] plaintext = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), plaintext);
        try {
            byte[] ciphertext = cipher.encrypt(plaintext);
            if (ciphertext.length > maxCiphertextBytes) {
                throw new IllegalArgumentException("Ciphertext exceeds HTTP/3 limit");
            }
            ByteBuf framed = Unpooled.buffer(4 + ciphertext.length);
            framed.writeInt(ciphertext.length).writeBytes(ciphertext);
            out.add(new DefaultHttp3DataFrame(framed));
        } catch (RuntimeException e) {
            // Cipher and framing failures are request-stream failures, not connection failures.
            ctx.close();
        }
    }
}
