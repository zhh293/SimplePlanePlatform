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
 * <p>
 * 长度前缀分帧：HTTP/2 不保证 DATA 帧边界与发送侧一一对应（受 maxFrameSize、
 * 流控、合帧影响），大数据传输时一个加密块可能被拆到多帧、或多个加密块被合到一帧。
 * 因此发送侧（{@link CipherEncodeHandler}）在每个密文块前写入 4 字节大端长度，
 * 本 Handler 维护一个累积缓冲 {@link #cumulation}，把所有进来的帧字节累积起来，
 * 循环“读 4 字节长度 → 够长就切出一个完整密文块解密 → 不够就等下一帧”，
 * 彻底摆脱对 HTTP/2 帧边界的依赖。
 * </p>
 */
public class CipherDecodeHandler extends MessageToMessageDecoder<Http2DataFrame> {

    private static final Logger log = LoggerFactory.getLogger(CipherDecodeHandler.class);

    /** 长度前缀字节数 */
    private static final int LENGTH_FIELD_LENGTH = 4;

    private final Cipher cipher;

    /** 跨帧累积缓冲：尚未凑成完整密文块的剩余字节暂存于此 */
    private ByteBuf cumulation;

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
        boolean endStream = frame.isEndStream();

        // 把本帧字节追加到累积缓冲
        if (content.readableBytes() > 0) {
            if (cumulation == null) {
                cumulation = ctx.alloc().buffer(content.readableBytes());
            }
            cumulation.writeBytes(content);
        }

        try {
            // 循环切出所有完整的密文块
            int produced = 0;
            while (cumulation != null && cumulation.readableBytes() >= LENGTH_FIELD_LENGTH) {
                // 先读 4 字节长度（不前移读指针，长度不够时需要回退等待）
                int ctLen = cumulation.getInt(cumulation.readerIndex());
                if (ctLen < 0) {
                    // 异常长度，视为协议错误
                    log.error("CipherDecode: invalid ciphertext length {}, closing channel", ctLen);
                    ctx.close();
                    return;
                }
                if (cumulation.readableBytes() < LENGTH_FIELD_LENGTH + ctLen) {
                    // 本块还没收齐，等待后续帧
                    break;
                }

                // 跳过长度字段，切出完整密文块
                cumulation.skipBytes(LENGTH_FIELD_LENGTH);
                byte[] ciphertext = new byte[ctLen];
                cumulation.readBytes(ciphertext);

                // 解密
                byte[] plaintext = cipher.decrypt(ciphertext);

                // 封装为新的 Http2DataFrame 传递给下游 Codec 解码器
                // 注意：endStream 只在“最后一个产出帧”上置位，这里先全部置 false，
                // 循环结束后若 endStream 为 true 再补一个/或在最后块上置位。
                out.add(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(plaintext), false));
                produced++;

                log.trace("CipherDecode: decrypted block {} bytes → {} bytes", ctLen, plaintext.length);
            }

            // 丢弃已消费的字节，回收缓冲
            if (cumulation != null) {
                if (cumulation.readableBytes() == 0) {
                    cumulation.release();
                    cumulation = null;
                } else {
                    cumulation.discardReadBytes();
                }
            }

            // 处理 endStream：HTTP/2 的 endStream 标志必须传递给下游以正常结束流。
            // 用一个空 DATA 帧携带 endStream（下游 Codec 能识别 0 长度 + endStream）。
            if (endStream) {
                out.add(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true));
            }

            if (produced == 0 && !endStream) {
                log.trace("CipherDecode: no complete block yet, waiting for more frames");
            }
        } catch (CryptoException e) {
            log.error("Decryption failed, closing channel", e);
            ctx.close();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        super.handlerRemoved(ctx);
    }
}
