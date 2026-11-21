package com.proxy.local.handler;

import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.local.config.ProxyConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class Socks5UdpTest {
    @Test void codecPreservesEmptyAndBoundariesAndRejectsMalformedPackets() {
        for (String host : new String[]{"127.0.0.1", "echo.test"}) for (int n : new int[]{0,1,1472}) {
            byte[] data = new byte[n]; new Random(n).nextBytes(data);
            ByteBuf packet = Socks5UdpCodec.encode(host, 443, data);
            try {
                Socks5UdpCodec.Datagram decoded = Socks5UdpCodec.decode(packet.duplicate());
                assertEquals(host, decoded.host); assertEquals(443, decoded.port); assertArrayEquals(data, decoded.data);
                for (int length=0; length<packet.readableBytes()-n; length++) assertNull(Socks5UdpCodec.decode(packet.slice(0,length)));
                packet.setByte(2,1); assertNull(Socks5UdpCodec.decode(packet.duplicate()));
            } finally { packet.release(); }
        }
        assertThrows(IllegalArgumentException.class, () -> Socks5UdpCodec.encode("echo.test",443,new byte[1473]));
    }
    @Test void fragmentedAuthenticationAndCommandAreRetainedAndUnsupportedAuthRejected() {
        AtomicInteger connects = new AtomicInteger();
        Invoker invoker = call -> { connects.incrementAndGet(); return CompletableFuture.completedFuture(Response.error("test rejection")); };
        EmbeddedChannel channel = new EmbeddedChannel(new Socks5InitHandler(invoker,null));
        byte[] combined = {5,1,0,5,1,0,1,1,2,3,4,0,80};
        for (byte b : combined) channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{b}));
        assertEquals(1,connects.get()); channel.finishAndReleaseAll();
        EmbeddedChannel coalesced = new EmbeddedChannel(new Socks5InitHandler(invoker,null));
        coalesced.writeInbound(Unpooled.wrappedBuffer(combined));
        assertEquals(2,connects.get(),"coalesced negotiation and command must also be decoded");
        coalesced.finishAndReleaseAll();
        EmbeddedChannel denied = new EmbeddedChannel(new Socks5InitHandler(invoker,null));
        denied.writeInbound(Unpooled.wrappedBuffer(new byte[]{5,1,2}));
        ByteBuf response=denied.readOutbound(); assertEquals(255,response.getUnsignedByte(1)); response.release();
        assertFalse(denied.isActive()); denied.finishAndReleaseAll();
    }
    @Test void loopbackDirectForwardsEmptyPacketsAndControlCloseReleasesRelay() throws Exception {
        try (DatagramSocket echo = new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
             Fixture fixture = new Fixture(call -> { fail("DIRECT must not invoke remote"); return null; },"direct");
             Socket control = fixture.control(); DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            InetSocketAddress relay=associate(control,udp.getLocalPort());
            udp.setSoTimeout(2000); echo.setSoTimeout(2000);
            for (int n : new int[]{0,1,1472}) {
                byte[] data=new byte[n]; Arrays.fill(data,(byte) n);
                send(udp,relay,"127.0.0.1",echo.getLocalPort(),data);
                java.net.DatagramPacket packet=new java.net.DatagramPacket(new byte[65535],65535); echo.receive(packet);
                assertEquals(n,packet.getLength()); echo.send(new java.net.DatagramPacket(data,n,packet.getSocketAddress()));
                assertArrayEquals(data,receive(udp).data);
            }
            control.close(); Thread.sleep(100);
            send(udp,relay,"127.0.0.1",echo.getLocalPort(),new byte[]{1});
            echo.setSoTimeout(150); assertThrows(SocketTimeoutException.class, () -> echo.receive(new java.net.DatagramPacket(new byte[8],8)));
        }
    }
    @Test void proxyAssociationsAreIsolatedMultiTargetAndRejectForeignSource() throws Exception {
        AtomicInteger opens=new AtomicInteger();
        Invoker invoker=call -> {
            long id=Long.parseLong(call.getAttachment("streamId").toString());
            if (call.getType()==ProxyMessage.MessageType.UDP_CONNECT) opens.incrementAndGet();
            if (call.getType()==ProxyMessage.MessageType.DATA) StreamChannelRegistry.getInstance().getDatagramMap().get(id).onDatagram(call.getData());
            return CompletableFuture.completedFuture(Response.ok("udp-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        };
        try (Fixture fixture=new Fixture(invoker,"proxy"); Socket a=fixture.control(); Socket b=fixture.control();
             DatagramSocket ua=new DatagramSocket(new InetSocketAddress("127.0.0.1",0)); DatagramSocket ub=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            InetSocketAddress ra=associate(a,ua.getLocalPort()), rb=associate(b,ub.getLocalPort()); ua.setSoTimeout(1000); ub.setSoTimeout(1000);
            send(ub,ra,"echo.test",9000,new byte[]{99}); Thread.sleep(50); assertEquals(0,opens.get());
            send(ua,ra,"echo.test",9000,new byte[]{1}); assertArrayEquals(new byte[]{1},receive(ua).data);
            send(ua,ra,"other.test",9001,new byte[0]); assertEquals("other.test",receive(ua).host);
            send(ub,rb,"echo.test",9000,new byte[]{2}); assertArrayEquals(new byte[]{2},receive(ub).data);
            assertEquals(3,opens.get()); a.close(); b.close();
            awaitEmpty();
        }
    }
    @Test void closingDuringOpenCancelsFutureAndBadCapabilityNeverFlushesData() throws Exception {
        CompletableFuture<Response> opening=new CompletableFuture<>(); AtomicInteger data=new AtomicInteger();
        Invoker invoker=call -> {
            if (call.getType()==ProxyMessage.MessageType.UDP_CONNECT) return opening;
            if (call.getType()==ProxyMessage.MessageType.DATA) data.incrementAndGet();
            return CompletableFuture.completedFuture(Response.ok());
        };
        try (Fixture fixture=new Fixture(invoker,"proxy"); Socket control=fixture.control(); DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            InetSocketAddress relay=associate(control,udp.getLocalPort()); send(udp,relay,"echo.test",9000,new byte[]{7});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(StreamChannelRegistry.getInstance().getDatagramMap().isEmpty() && System.nanoTime()<end) Thread.sleep(5);
            control.close(); awaitEmpty(); assertTrue(opening.isCancelled()); assertEquals(0,data.get());
        }
        Invoker noCapability=call -> { if(call.getType()==ProxyMessage.MessageType.DATA) data.incrementAndGet(); return CompletableFuture.completedFuture(Response.ok()); };
        try (Fixture fixture=new Fixture(noCapability,"proxy"); Socket control=fixture.control(); DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            send(udp,associate(control,udp.getLocalPort()),"echo.test",9000,new byte[]{7}); Thread.sleep(100); assertEquals(0,data.get()); awaitEmpty();
        }
    }
    @Test void rejectCreatesNoOutbound() throws Exception {
        try (Fixture fixture=new Fixture(call -> {fail("REJECT must not invoke remote");return null;},"reject");
             Socket control=fixture.control(); DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            send(udp,associate(control,udp.getLocalPort()),"echo.test",9000,new byte[]{7});
            udp.setSoTimeout(150); assertThrows(SocketTimeoutException.class,()->receive(udp));
        }
    }
    static void awaitEmpty() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!StreamChannelRegistry.getInstance().getDatagramMap().isEmpty() && System.nanoTime()<end) Thread.sleep(5);
        assertTrue(StreamChannelRegistry.getInstance().getDatagramMap().isEmpty());
    }
    public static InetSocketAddress associate(Socket socket,int clientPort) throws Exception {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(new byte[]{5,1,0}); assertArrayEquals(new byte[]{5,0},socket.getInputStream().readNBytes(2));
        ByteBuf request=Unpooled.buffer().writeBytes(new byte[]{5,3,0,1,127,0,0,1}).writeShort(clientPort);
        byte[] bytes=new byte[request.readableBytes()]; request.readBytes(bytes); request.release(); socket.getOutputStream().write(bytes);
        byte[] response=socket.getInputStream().readNBytes(10); assertEquals(10,response.length); assertArrayEquals(new byte[]{5,0,0,1},Arrays.copyOf(response,4));
        return new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(response,4,8)),((response[8]&255)<<8)|(response[9]&255));
    }
    public static void send(DatagramSocket udp,InetSocketAddress relay,String host,int port,byte[] data) throws Exception {
        ByteBuf encoded=Socks5UdpCodec.encode(host,port,data); byte[] bytes=new byte[encoded.readableBytes()]; encoded.readBytes(bytes); encoded.release();
        udp.send(new java.net.DatagramPacket(bytes,bytes.length,relay));
    }
    public static Socks5UdpCodec.Datagram receive(DatagramSocket udp) throws Exception {
        java.net.DatagramPacket packet=new java.net.DatagramPacket(new byte[65535],65535); udp.receive(packet);
        ByteBuf bytes=Unpooled.wrappedBuffer(packet.getData(),0,packet.getLength());
        try { Socks5UdpCodec.Datagram result=Socks5UdpCodec.decode(bytes); assertNotNull(result); return result; } finally {bytes.release();}
    }
    static final class Fixture implements AutoCloseable {
        final NioEventLoopGroup workers=new NioEventLoopGroup(2); final Channel server;
        Fixture(Invoker invoker,String action) throws Exception {
            ProxyConfig.RouteConfig config=new ProxyConfig.RouteConfig(); config.setDefaultRoute(action);
            RouteRule rules=new RouteRule(config,Collections.emptyList());
            server=new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel ch) {ch.pipeline().addLast(new ProtocolDetector(invoker,false,rules));}
            }).bind("127.0.0.1",0).sync().channel();
        }
        Socket control() throws Exception { return new Socket("127.0.0.1",((InetSocketAddress)server.localAddress()).getPort()); }
        @Override public void close() throws Exception {server.close().sync();workers.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }
}
