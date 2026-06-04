package com.proxy.transport.netty.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ProxyMessage → HTTP/2 帧编码器
 * <p>
 * 通过 SPI 加载 {@link Codec} 实现将 ProxyMessage 序列化为字节数组，
 * 然后封装进 HTTP/2 帧发送。
 * </p>
 * <p>
 * HTTP/2 协议要求每个 Stream 的第一帧必须是 HEADERS 帧，
 * 因此本 Handler 会在 Stream 上首次发送消息时自动补发 HEADERS 帧。
 * </p>
 */
public class ProxyMessageEncoder extends MessageToMessageEncoder<ProxyMessage> {

    private static final Logger log = LoggerFactory.getLogger(ProxyMessageEncoder.class);

    private final Codec codec;
    private final boolean isServer;
    private boolean headersSent = false;

    public ProxyMessageEncoder() {
        this(false);
    }

    public ProxyMessageEncoder(boolean isServer) {
        this.codec = ExtensionLoader.getLoader(Codec.class).getDefaultExtension();
        this.isServer = isServer;
    }

    public ProxyMessageEncoder(Codec codec) {
        this.codec = codec;
        this.isServer = false;
    }

    public ProxyMessageEncoder(Codec codec, boolean isServer) {
        this.codec = codec;
        this.isServer = isServer;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ProxyMessage msg, List<Object> out) throws Exception {
        // 通过 SPI Codec 序列化
        byte[] encoded = codec.encode(msg);

        // HTTP/2 协议要求 Stream 的第一帧必须是 HEADERS 帧
        if (!headersSent) {
            Http2Headers headers = new DefaultHttp2Headers();
            if (isServer) {
                // 服务端响应 headers
                headers.status("200");
            } else {
                // 客户端请求 headers
                headers.method("POST");
                headers.path("/proxy");
                headers.scheme("http");
            }
            headers.add("content-type", "application/octet-stream");
            out.add(new DefaultHttp2HeadersFrame(headers, false));
            headersSent = true;
        }

        // 封装为 HTTP/2 DATA 帧
        out.add(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(encoded), false));

        log.trace("Encoded ProxyMessage to HTTP/2 frames via Codec: type={}, requestId={}, size={}",
                msg.getType(), msg.getRequestId(), encoded.length);
    }
}
