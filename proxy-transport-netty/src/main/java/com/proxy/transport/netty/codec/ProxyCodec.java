package com.proxy.transport.netty.codec;

import com.proxy.common.codec.Codec;
import com.proxy.common.codec.CodecException;
import com.proxy.common.model.ProxyMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 默认编解码器实现 —— 自定义二进制协议
 * <p>
 * 通过 SPI 注册，名称为 "proxy"。
 * </p>
 * <p>
 * 帧格式：
 * <pre>
 * +--------+--------+--------+--------+--------+--------+
 * | Type   | Status |         RequestId (8B)            |
 * | (1B)   | (1B)   |                                   |
 * +--------+--------+--------+--------+--------+--------+
 * |         StreamId (8B)             | HostLen (2B)    |
 * +--------+--------+--------+--------+--------+--------+
 * | Host (变长)     | Port (4B)       | DataLen (4B)    |
 * +--------+--------+--------+--------+--------+--------+
 * | Data (变长)                                         |
 * +--------+--------+--------+--------+--------+--------+
 * </pre>
 * 固定头部 28 字节：Type(1) + Status(1) + RequestId(8) + StreamId(8) + HostLen(2) + Port(4) + DataLen(4)
 * </p>
 */
public class ProxyCodec implements Codec {

    private static final int FIXED_HEADER_SIZE = 28;

    @Override
    public byte[] encode(ProxyMessage message) throws CodecException {
        if (message == null) {
            throw new CodecException("Cannot encode null message");
        }

        try {
            byte[] hostBytes = message.getHost() != null
                    ? message.getHost().getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] data = message.getData();
            int dataLen = data != null ? data.length : 0;

            int totalLen = FIXED_HEADER_SIZE + hostBytes.length + dataLen;
            ByteBuffer buf = ByteBuffer.allocate(totalLen);

            // Type (1 byte)
            buf.put((byte) (message.getType() != null ? message.getType().ordinal() : 0));

            // Status (1 byte)
            buf.put((byte) message.getStatus());

            // RequestId (8 bytes)
            buf.putLong(message.getRequestId());

            // StreamId (8 bytes)
            buf.putLong(message.getStreamId());

            // Host Length (2 bytes) + Host
            buf.putShort((short) hostBytes.length);
            if (hostBytes.length > 0) {
                buf.put(hostBytes);
            }

            // Port (4 bytes)
            buf.putInt(message.getPort());

            // Data Length (4 bytes) + Data
            buf.putInt(dataLen);
            if (dataLen > 0) {
                buf.put(data);
            }

            return buf.array();
        } catch (Exception e) {
            throw new CodecException("Failed to encode ProxyMessage", e);
        }
    }

    @Override
    public ProxyMessage decode(byte[] data) throws CodecException {
        if (data == null || data.length < FIXED_HEADER_SIZE) {
            throw new CodecException("Data too short to decode: " +
                    (data != null ? data.length : 0) + " bytes, minimum " + FIXED_HEADER_SIZE);
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(data);

            // Type (1 byte)
            int typeOrdinal = buf.get() & 0xFF;
            ProxyMessage.MessageType type = null;
            ProxyMessage.MessageType[] types = ProxyMessage.MessageType.values();
            if (typeOrdinal < types.length) {
                type = types[typeOrdinal];
            }

            // Status (1 byte)
            int status = buf.get() & 0xFF;

            // RequestId (8 bytes)
            long requestId = buf.getLong();

            // StreamId (8 bytes)
            long streamId = buf.getLong();

            // Host Length (2 bytes) + Host
            int hostLen = buf.getShort() & 0xFFFF;
            String host = null;
            if (hostLen > 0) {
                if (buf.remaining() < hostLen) {
                    throw new CodecException("Incomplete host data, expected " + hostLen +
                            " bytes but only " + buf.remaining() + " remaining");
                }
                byte[] hostBytes = new byte[hostLen];
                buf.get(hostBytes);
                host = new String(hostBytes, StandardCharsets.UTF_8);
            }

            // Port (4 bytes)
            int port = buf.getInt();

            // Data Length (4 bytes) + Data
            int dataLen = buf.getInt();
            byte[] payload = null;
            if (dataLen > 0) {
                if (buf.remaining() < dataLen) {
                    throw new CodecException("Incomplete payload data, expected " + dataLen +
                            " bytes but only " + buf.remaining() + " remaining");
                }
                payload = new byte[dataLen];
                buf.get(payload);
            }

            return ProxyMessage.builder()
                    .type(type)
                    .status(status)
                    .requestId(requestId)
                    .streamId(streamId)
                    .host(host)
                    .port(port)
                    .data(payload)
                    .build();
        } catch (CodecException e) {
            throw e;
        } catch (Exception e) {
            throw new CodecException("Failed to decode ProxyMessage", e);
        }
    }
}
