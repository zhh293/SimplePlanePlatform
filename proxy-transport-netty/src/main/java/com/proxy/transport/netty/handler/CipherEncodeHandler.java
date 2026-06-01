package com.proxy.transport.netty.handler;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CryptoException;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 出站加密 Handler —— 在 Codec 编码之后、发送到网络之前对数据进行加密
 * <p>
 * 通过 SPI 加载 {@link Cipher} 实现，对出站数据进行加密。
 * 默认使用 @SPI 注解指定的 aes-gcm 算法。
 * </p>
 * <p>
 * Pipeline 位置（出站方向）：
 * <pre>
 * ProxyMessage → ProxyMessageEncoder(Codec) → [CipherEncodeHandler] → 网络
 * </pre>
 * </p>
 */
public class CipherEncodeHandler extends MessageToMessageEncoder<Http2DataFrame> {

    private static final Logger log = LoggerFactory.getLogger(CipherEncodeHandler.class);

    private final Cipher cipher;

    /**
     * 默认构造 —— 通过 SPI 加载默认 Cipher 实现
     */
    public CipherEncodeHandler() {
        this.cipher = ExtensionLoader.getLoader(Cipher.class).getDefaultExtension();
    }

    /**
     * 指定 Cipher 实例（用于外部注入或测试）
     */
    public CipherEncodeHandler(Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) throws Exception {
        ByteBuf content = frame.content();

        if (content.readableBytes() == 0) {
            // 空帧直接透传
            out.add(frame.retain());
            return;
        }

        // 提取明文
        byte[] plaintext = new byte[content.readableBytes()];
        content.readBytes(plaintext);

        try {
            // 加密
            byte[] ciphertext = cipher.encrypt(plaintext);

            // 封装为新的 HTTP/2 DATA 帧
            out.add(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(ciphertext), frame.isEndStream()));

            log.trace("CipherEncode: encrypted {} bytes → {} bytes", plaintext.length, ciphertext.length);
        } catch (CryptoException e) {
            log.error("Encryption failed, closing channel", e);
            ctx.close();
        }
    }
}
