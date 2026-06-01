package com.proxy.transport.netty.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * HTTP/2 DATA 帧 → ProxyMessage 解码器
 * <p>
 * 通过 SPI 加载 {@link Codec} 实现将字节数组反序列化为 ProxyMessage。
 * </p>
 * <p>
 * 职责分离：
 * - 本 Handler：负责从 HTTP/2 DATA 帧中提取 byte[]
 * - Codec（SPI 可替换）：负责 byte[] → ProxyMessage 的反序列化逻辑
 * </p>
 * <p>
 * HTTP/2 本身已经处理了帧边界（每个 DATA 帧有明确的长度），
 * 所以这里不需要再处理 TCP 粘包/拆包问题。
 * </p>
 */
public class ProxyMessageDecoder extends MessageToMessageDecoder<Http2DataFrame> {

    private static final Logger log = LoggerFactory.getLogger(ProxyMessageDecoder.class);

    private final Codec codec;

    public ProxyMessageDecoder() {
        this.codec = ExtensionLoader.getLoader(Codec.class).getDefaultExtension();
    }

    public ProxyMessageDecoder(Codec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) throws Exception {
        ByteBuf content = frame.content();

        if (content.readableBytes() == 0) {
            log.debug("Received empty HTTP/2 DATA frame, skipping");
            return;
        }

        // 从 DATA 帧中提取字节数组
        byte[] data = new byte[content.readableBytes()];
        content.readBytes(data);

        // 通过 SPI Codec 反序列化
        ProxyMessage msg = codec.decode(data);

        if (msg != null) {
            out.add(msg);
            log.trace("Decoded HTTP/2 DATA frame to ProxyMessage via Codec: type={}, requestId={}",
                    msg.getType(), msg.getRequestId());
        }
    }
}
