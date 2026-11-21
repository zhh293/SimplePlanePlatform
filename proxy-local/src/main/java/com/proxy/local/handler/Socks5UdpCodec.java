package com.proxy.local.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.NetUtil;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** One SOCKS5 UDP packet. IPv4/domain addresses and FRAG=0. */
public final class Socks5UdpCodec {
    public static final int MAX_PAYLOAD = 1472;
    private Socks5UdpCodec() { }
    public static final class Datagram {
        public final String host;
        public final int port;
        public final byte[] data;
        Datagram(String host, int port, byte[] data) { this.host = host; this.port = port; this.data = data; }
    }
    public static Datagram decode(ByteBuf in) {
        if (in.readableBytes() < 4 || in.readUnsignedShort() != 0 || in.readUnsignedByte() != 0) return null;
        int type = in.readUnsignedByte(); String host;
        if (type == 1) {
            if (in.readableBytes() < 6) return null;
            host = in.readUnsignedByte()+"."+in.readUnsignedByte()+"."+in.readUnsignedByte()+"."+in.readUnsignedByte();
        } else if (type == 3) {
            if (!in.isReadable()) return null;
            int count = in.readUnsignedByte();
            if (count == 0 || in.readableBytes() < count + 2) return null;
            host = in.readCharSequence(count, StandardCharsets.US_ASCII).toString().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9_.-]+")) return null;
            if (host.endsWith(".")) host = host.substring(0, host.length()-1);
            if (host.isEmpty()) return null;
        } else return null;
        int port = in.readUnsignedShort();
        if (port == 0 || in.readableBytes() > MAX_PAYLOAD) return null;
        byte[] data = new byte[in.readableBytes()]; in.readBytes(data);
        return new Datagram(host, port, data);
    }
    public static ByteBuf encode(String host, int port, byte[] data) {
        if (port < 1 || port > 65535 || data.length > MAX_PAYLOAD) throw new IllegalArgumentException("Invalid UDP datagram");
        ByteBuf out = Unpooled.buffer(10 + host.length() + data.length);
        out.writeShort(0).writeByte(0);
        byte[] address = NetUtil.createByteArrayFromIpAddressString(host);
        if (address != null && address.length == 4) out.writeByte(1).writeBytes(address);
        else {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if (address != null || name.length == 0 || name.length > 255) { out.release(); throw new IllegalArgumentException("Unsupported UDP address"); }
            out.writeByte(3).writeByte(name.length).writeBytes(name);
        }
        return out.writeShort(port).writeBytes(data);
    }
}
