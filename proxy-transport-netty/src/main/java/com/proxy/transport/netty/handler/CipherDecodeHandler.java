package com.proxy.transport.netty.handler;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CryptoException;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 入站解密 Handler —— 在收到网络数据之后、Codec 解码之前对数据进行解密
 * <p>
 * 通过 SPI 加载 {@link Cipher} 实现，对入站数据进行解密。
 * 默认使用 @SPI 注解指定的 aes-gcm 算法。
 * </p>
 * <p>
 * Pipeline 位置（入站方向）：
 * <pre>
 * 网络 → [CipherDecodeHandler] → ProxyMessageDecoder(Codec) → ProxyMessage
 * </pre>
 * </p>
 */
public class CipherDecodeHandler extends MessageToMessageDecoder<Http2DataFrame> {

    private static final Logger log = LoggerFactory.getLogger(CipherDecodeHandler.class);

    private final Cipher cipher;

    /**
     * 默认构造 —— 通过 SPI 加载默认 Cipher 实现
     */
    public CipherDecodeHandler() {
        this.cipher = ExtensionLoader.getLoader(Cipher.class).getDefaultExtension();
    }

    /**
     * 指定 Cipher 实例（用于外部注入或测试）
     */
    public CipherDecodeHandler(Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) throws Exception {
        ByteBuf content = frame.content();

        if (content.readableBytes() == 0) {
            // 空帧直接透传
            out.add(frame.retain());
            return;
        }

        // 提取密文
        byte[] ciphertext = new byte[content.readableBytes()];
        content.readBytes(ciphertext);

        try {
            // 解密
            byte[] plaintext = cipher.decrypt(ciphertext);

            // 封装为新的 Http2DataFrame 传递给下游 Codec 解码器
            out.add(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(plaintext), frame.isEndStream()));

            log.trace("CipherDecode: decrypted {} bytes → {} bytes", ciphertext.length, plaintext.length);
        } catch (CryptoException e) {
            log.error("Decryption failed, closing channel", e);
            ctx.close();
        }
    }
}
