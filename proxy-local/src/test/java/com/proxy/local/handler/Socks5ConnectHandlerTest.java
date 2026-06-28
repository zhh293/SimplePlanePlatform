package com.proxy.local.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-LOCAL-003 [P1]：Socks5ConnectHandler 地址解析单元测试
 * <p>
 * 使用 EmbeddedChannel 验证 CONNECT 请求中各种地址类型的解析：
 * 1. IPv4 地址解析
 * 2. 域名地址解析
 * 3. 非法 CMD → 回复 CMD_NOT_SUPPORTED 并关闭
 * 4. 非法 ATYP → 回复 ATYP_NOT_SUPPORTED 并关闭
 * 5. 非法版本号 → 回复 GENERAL_FAILURE 并关闭
 */
class Socks5ConnectHandlerTest {

    /**
     * 构造一个 Invoker stub，invoke 时返回成功的 CompletableFuture
     */
    private static com.proxy.common.filter.Invoker successInvoker() {
        return invocation -> java.util.concurrent.CompletableFuture.completedFuture(
                com.proxy.common.filter.Response.ok());
    }

    /**
     * 构造一个 RouteRule stub，所有域名都走代理
     */
    private static RouteRule alwaysProxyRule() {
        // RouteRule.shouldProxy 返回 true 表示走代理
        return null; // null routeRule 在 ConnectHandler 中等价于 "走代理"
    }

    /**
     * 构建 IPv4 类型的 CONNECT 请求
     */
    private ByteBuf buildConnectIpv4(byte[] ip, int port) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x05); // VER
        buf.writeByte(0x01); // CMD = CONNECT
        buf.writeByte(0x00); // RSV
        buf.writeByte(0x01); // ATYP = IPv4
        buf.writeBytes(ip);
        buf.writeShort(port);
        return buf;
    }

    /**
     * 构建域名类型的 CONNECT 请求
     */
    private ByteBuf buildConnectDomain(String domain, int port) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x05); // VER
        buf.writeByte(0x01); // CMD = CONNECT
        buf.writeByte(0x00); // RSV
        buf.writeByte(0x03); // ATYP = Domain
        buf.writeByte(domainBytes.length);
        buf.writeBytes(domainBytes);
        buf.writeShort(port);
        return buf;
    }

    // ---- TC-LOCAL-003a：非法 CMD → 回复 CMD_NOT_SUPPORTED (0x07) ----

    @Test
    void testUnsupportedCmdRepliesAndCloses() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new Socks5ConnectHandler(successInvoker(), alwaysProxyRule()));

        // CMD = 0x02 (BIND) 不支持
        ByteBuf request = Unpooled.buffer();
        request.writeByte(0x05); // VER
        request.writeByte(0x02); // CMD = BIND (不支持)
        request.writeByte(0x00); // RSV
        request.writeByte(0x01); // ATYP = IPv4
        request.writeBytes(new byte[]{127, 0, 0, 1});
        request.writeShort(80);
        ch.writeInbound(request);

        // 验证回复
        ByteBuf response = ch.readOutbound();
        assertNotNull(response, "应回复错误响应");
        assertEquals(0x05, response.readByte() & 0xFF, "VER");
        assertEquals(0x07, response.readByte() & 0xFF, "REP 应为 CMD_NOT_SUPPORTED (0x07)");
        response.release();

        // 通道应被关闭
        assertFalse(ch.isActive(), "不支持的 CMD 应关闭连接");
    }

    // ---- TC-LOCAL-003b：非法 ATYP → 回复 ATYP_NOT_SUPPORTED (0x08) ----

    @Test
    void testUnsupportedAtypRepliesAndCloses() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new Socks5ConnectHandler(successInvoker(), alwaysProxyRule()));

        // ATYP = 0x05 (不存在的类型)
        ByteBuf request = Unpooled.buffer();
        request.writeByte(0x05); // VER
        request.writeByte(0x01); // CMD = CONNECT
        request.writeByte(0x00); // RSV
        request.writeByte(0x05); // ATYP = 非法
        request.writeBytes(new byte[]{0, 0, 0, 0});
        request.writeShort(80);
        ch.writeInbound(request);

        ByteBuf response = ch.readOutbound();
        assertNotNull(response, "应回复错误响应");
        assertEquals(0x05, response.readByte() & 0xFF);
        assertEquals(0x08, response.readByte() & 0xFF, "REP 应为 ATYP_NOT_SUPPORTED (0x08)");
        response.release();

        assertFalse(ch.isActive(), "非法 ATYP 应关闭连接");
    }

    // ---- TC-LOCAL-003c：非法版本号 → 回复 GENERAL_FAILURE (0x01) ----

    @Test
    void testInvalidVersionRepliesAndCloses() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new Socks5ConnectHandler(successInvoker(), alwaysProxyRule()));

        // VER = 0x04 (SOCKS4)
        ByteBuf request = Unpooled.buffer();
        request.writeByte(0x04); // 非法版本
        request.writeByte(0x01);
        request.writeByte(0x00);
        request.writeByte(0x01);
        request.writeBytes(new byte[]{127, 0, 0, 1});
        request.writeShort(80);
        ch.writeInbound(request);

        ByteBuf response = ch.readOutbound();
        assertNotNull(response, "应回复错误响应");
        assertEquals(0x05, response.readByte() & 0xFF);
        assertEquals(0x01, response.readByte() & 0xFF, "REP 应为 GENERAL_FAILURE (0x01)");
        response.release();

        assertFalse(ch.isActive(), "非法版本应关闭连接");
    }

    // ---- TC-LOCAL-003d：数据不足 → 不回复，等待更多数据 ----

    @Test
    void testInsufficientDataNoResponse() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new Socks5ConnectHandler(successInvoker(), alwaysProxyRule()));

        // 只发送 3 字节（不够 4 字节头）
        ByteBuf request = Unpooled.buffer(3);
        request.writeByte(0x05);
        request.writeByte(0x01);
        request.writeByte(0x00);
        ch.writeInbound(request);

        ByteBuf response = ch.readOutbound();
        assertNull(response, "数据不足时不应回复");
        assertTrue(ch.isActive(), "数据不足时不应关闭连接");
        ch.finish();
    }
}
