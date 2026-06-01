package com.proxy.transport.netty.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ProxyMessage → HTTP/2 DATA 帧编码器
 * <p>
 * 通过 SPI 加载 {@link Codec} 实现将 ProxyMessage 序列化为字节数组，
 * 然后封装进 HTTP/2 DATA 帧发送。
 * </p>
 * <p>
 * 职责分离：
 * - Codec（SPI 可替换）：负责 ProxyMessage ↔ byte[] 的序列化逻辑
 * - 本 Handler：负责 byte[] ↔ HTTP/2 DATA 帧的封装
 * </p>
 */
public class ProxyMessageEncoder extends MessageToMessageEncoder<ProxyMessage> {

    private static final Logger log = LoggerFactory.getLogger(ProxyMessageEncoder.class);

    private final Codec codec;

    public ProxyMessageEncoder() {
        this.codec = ExtensionLoader.getLoader(Codec.class).getDefaultExtension();
    }

    public ProxyMessageEncoder(Codec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ProxyMessage msg, List<Object> out) throws Exception {
        // 通过 SPI Codec 序列化
        byte[] encoded = codec.encode(msg);

        // 封装为 HTTP/2 DATA 帧
        out.add(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(encoded), false));

        log.trace("Encoded ProxyMessage to HTTP/2 DATA frame via Codec: type={}, requestId={}, size={}",
                msg.getType(), msg.getRequestId(), encoded.length);
    }
}
