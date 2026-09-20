package com.proxy.transport.http3;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.ProxyMessage;
import com.proxy.crypto.NoneCipher;
import com.proxy.transport.http3.handler.Http3CipherDecodeHandler;
import com.proxy.transport.http3.handler.Http3CipherEncodeHandler;
import com.proxy.transport.http3.handler.Http3ProxyMessageDecoder;
import com.proxy.transport.http3.handler.Http3ProxyMessageEncoder;
import com.proxy.transport.netty.codec.ProxyCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Http3CodecHandlerTest {
    @Test
    void decodesOneMessageSplitAcrossDataFrames() {
        ProxyMessage message = message(7, ProxyMessage.MessageType.DATA, "split".getBytes(StandardCharsets.UTF_8));
        Http3DataFrame encoded = encode(message);
        ByteBuf bytes = encoded.content();
        int split = Math.max(1, bytes.readableBytes() / 2);
        Http3DataFrame first = new DefaultHttp3DataFrame(bytes.copy(bytes.readerIndex(), split));
        Http3DataFrame second = new DefaultHttp3DataFrame(bytes.copy(bytes.readerIndex() + split,
                bytes.readableBytes() - split));
        encoded.release();

        EmbeddedChannel inbound = inboundChannel();
        assertFalse(inbound.writeInbound(first));
        assertTrue(inbound.writeInbound(second));
        ProxyMessage decoded = inbound.readInbound();
        assertNotNull(decoded);
        assertEquals(message.getStreamId(), decoded.getStreamId());
        assertArrayEquals(message.getData(), decoded.getData());
        inbound.finishAndReleaseAll();
    }

    @Test
    void decodesMultipleMessagesMergedIntoOneDataFrame() {
        Http3DataFrame one = encode(message(1, ProxyMessage.MessageType.CONNECT, null));
        Http3DataFrame two = encode(message(2, ProxyMessage.MessageType.DISCONNECT, "bye".getBytes(StandardCharsets.UTF_8)));
        ByteBuf merged = Unpooled.buffer(one.content().readableBytes() + two.content().readableBytes());
        merged.writeBytes(one.content(), one.content().readerIndex(), one.content().readableBytes());
        merged.writeBytes(two.content(), two.content().readerIndex(), two.content().readableBytes());
        one.release();
        two.release();

        EmbeddedChannel inbound = inboundChannel();
        inbound.writeInbound(new DefaultHttp3DataFrame(merged));
        ProxyMessage first = inbound.readInbound();
        ProxyMessage second = inbound.readInbound();
        assertEquals(1, first.getStreamId());
        assertEquals(2, second.getStreamId());
        inbound.finishAndReleaseAll();
    }

    @Test
    void noneCipherRoundTripKeepsPayload() {
        ProxyMessage message = message(9, ProxyMessage.MessageType.DATA, new byte[]{1, 2, 3, 4});
        Http3DataFrame encoded = encode(message);
        EmbeddedChannel inbound = inboundChannel();
        inbound.writeInbound(encoded);
        ProxyMessage decoded = inbound.readInbound();
        assertNotNull(decoded);
        assertArrayEquals(message.getData(), decoded.getData());
        inbound.finishAndReleaseAll();
    }

    private Http3DataFrame encode(ProxyMessage message) {
        Cipher cipher = new NoneCipher();
        cipher.init(new CipherConfig());
        EmbeddedChannel outbound = new EmbeddedChannel(
                new Http3CipherEncodeHandler(cipher, 1024 * 1024),
                new Http3ProxyMessageEncoder(new ProxyCodec(), 1024 * 1024));
        outbound.writeOutbound(message);
        Http3DataFrame frame = outbound.readOutbound();
        outbound.finishAndReleaseAll();
        return frame;
    }

    private EmbeddedChannel inboundChannel() {
        Cipher cipher = new NoneCipher();
        cipher.init(new CipherConfig());
        return new EmbeddedChannel(
                new Http3CipherDecodeHandler(cipher, 1024 * 1024),
                new Http3ProxyMessageDecoder(new ProxyCodec(), 1024 * 1024));
    }

    private ProxyMessage message(long streamId, ProxyMessage.MessageType type, byte[] data) {
        return ProxyMessage.builder().streamId(streamId).requestId(0).type(type)
                .host("example.com").port(443).data(data).build();
    }
}
