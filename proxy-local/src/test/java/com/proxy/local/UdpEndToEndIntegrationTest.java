package com.proxy.local;

import com.proxy.cluster.ClientInvoker;
import com.proxy.cluster.StickyClusterInvoker;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.Server;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.local.config.ProxyConfig;
import com.proxy.local.handler.*;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.remote.outbound.OutboundSession;
import com.proxy.transport.http3.Http3Server;
import com.proxy.transport.netty.NettyServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SOCKS UDP -> exchange -> encrypted H2/H3 -> remote UDP -> real echo. */
class UdpEndToEndIntegrationTest {
    private static Socket openFramed(int localPort, int targetPort) throws Exception {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress("127.0.0.2", 0));
        socket.connect(new InetSocketAddress("127.0.0.1", localPort));
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(new byte[]{5, 1, 0});
        assertArrayEquals(new byte[]{5, 0}, socket.getInputStream().readNBytes(2));
        socket.getOutputStream().write(new byte[]{5, (byte) 0xf0, 0, 1, 127, 0, 0, 1,
                (byte) (targetPort >>> 8), (byte) targetPort});
        assertEquals(0, socket.getInputStream().readNBytes(10)[1]);
        return socket;
    }

    @ParameterizedTest
    @CsvSource({"netty,chacha20","netty,chacha20-poly1305-v2","netty,aes-gcm-v2", "http3,chacha20","http3,chacha20-poly1305-v2","http3,aes-gcm-v2"})
    void udpMatrix(String transport,String cipher) throws Exception {
        TestCertificates tls=TestCertificates.create();
        NioEventLoopGroup workers=new NioEventLoopGroup(2);
        ExecutorService business=Executors.newFixedThreadPool(4);
        OutboundConnector connector=new OutboundConnector(workers,2000) {
            @Override public CompletableFuture<Channel> connectUdp(String host,int port,OutboundSession session) {
                assertTrue(host.equals("echo.test") || host.equals("127.0.0.1")); return super.connectUdp("127.0.0.1",port,session);
            }
        };
        DispatchInvoker dispatch=new DispatchInvoker(business,connector,2000);
        Server remote=null; ExchangeClient client=null; Channel local=null;
        try (DatagramSocket echo=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            Thread echoThread=new Thread(()-> {
                try { while(!echo.isClosed()) {java.net.DatagramPacket p=new java.net.DatagramPacket(new byte[65535],65535);echo.receive(p);echo.send(p);} }
                catch(java.io.IOException stopped) {if(!echo.isClosed()) throw new RuntimeException(stopped);}
            },"udp-test-echo"); echoThread.setDaemon(true);echoThread.start();
            int port;
            if(transport.equals("http3")) {try(DatagramSocket free=new DatagramSocket(0)){port=free.getLocalPort();}}
            else {try(ServerSocket free=new ServerSocket(0)){port=free.getLocalPort();}}
            URL serverUrl=new URL("proxy","localhost",port).addParameter("transport",transport)
                    .addParameter("cipher",cipher).addParameter("cipherKey","0123456789abcdef0123456789abcdef")
                    .addParameter("http3.certificateFile",tls.certificate().getAbsolutePath())
                    .addParameter("http3.privateKeyFile",tls.privateKey().getAbsolutePath())
                    .addParameter("workerThreads","2");
            remote=transport.equals("http3") ? new Http3Server(serverUrl,new ExchangeHandler(dispatch)) : new NettyServer(serverUrl,new ExchangeHandler(dispatch));
            remote.start();
            URL url=new URL("proxy","localhost",port).addParameter("transport",transport).addParameter("ssl","false")
                    .addParameter("cipher",cipher).addParameter("cipherKey","0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile",tls.ca().getAbsolutePath()).addParameter("ioThreads","2");
            client=ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension().connect(url);
            client.setStreamRegistry(StreamChannelRegistry.getInstance().getInternalMap());
            client.setDatagramRegistry(StreamChannelRegistry.getInstance().getDatagramMap());
            StickyClusterInvoker invoker=new StickyClusterInvoker();
            ClientInvoker first=new ClientInvoker(client,2000);
            com.proxy.common.filter.FilterChainBuilder chain = ExtensionLoader.getLoader(com.proxy.common.filter.FilterChainBuilder.class).getDefaultExtension();
            java.util.List<com.proxy.common.filter.Filter> filters = ExtensionLoader.getLoader(com.proxy.common.filter.Filter.class).getActivateExtensions("client");
            com.proxy.common.filter.Invoker filteredFirst = chain.build(first,filters);
            invoker.setInvokers(Collections.singletonList(filteredFirst));invoker.setCurrentInvoker(filteredFirst);
            ProxyConfig.RouteConfig config=new ProxyConfig.RouteConfig();config.setDefaultRoute("proxy");
            RouteRule rules=new RouteRule(config,Collections.emptyList());
            local=new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel ch) {ch.pipeline().addLast(new ProtocolDetector(invoker,false,rules));}
            }).bind("127.0.0.1",0).sync().channel();
            int localPort=((InetSocketAddress)local.localAddress()).getPort();
            try(Socket framed = openFramed(localPort, echo.getLocalPort());
                Socket control=new Socket("127.0.0.1",localPort);DatagramSocket udp=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
                InetSocketAddress relay=Socks5UdpTest.associate(control,udp.getLocalPort()); udp.setSoTimeout(3000);
                for(int n:new int[]{0,1,1472,0,1000,17}) {
                    byte[] data=new byte[n];new Random(n).nextBytes(data);
                    Socks5UdpTest.send(udp,relay,"echo.test",echo.getLocalPort(),data);
                    Socks5UdpCodec.Datagram response=Socks5UdpTest.receive(udp);
                    assertEquals("echo.test",response.host);assertEquals(echo.getLocalPort(),response.port);assertArrayEquals(data,response.data);
                }
                // The empty first frame and two following frames share one TCP write.
                framed.getOutputStream().write(new byte[]{0, 0, 0, 1, 41, 0, 1, 42});
                assertArrayEquals(new byte[]{0, 0, 0, 1, 41, 0, 1, 42}, framed.getInputStream().readNBytes(8));
                long oldId=StreamChannelRegistry.getInstance().getDatagramMap().keySet().iterator().next();
                invoker.setCurrentInvoker(null);client.close();
                assertEquals(-1, framed.getInputStream().read(), "Retiring current must close framed TCP");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!StreamChannelRegistry.getInstance().getDatagramMap().isEmpty() && System.nanoTime()<until) Thread.sleep(5);
                assertTrue(StreamChannelRegistry.getInstance().getDatagramMap().isEmpty());
                client=ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension().connect(url);
                client.setStreamRegistry(StreamChannelRegistry.getInstance().getInternalMap());
                client.setDatagramRegistry(StreamChannelRegistry.getInstance().getDatagramMap());
                ClientInvoker replacement=new ClientInvoker(client,2000);
                com.proxy.common.filter.Invoker filteredReplacement = chain.build(replacement,filters);
                invoker.setInvokers(Collections.singletonList(filteredReplacement));invoker.setCurrentInvoker(filteredReplacement);
                Socks5UdpTest.send(udp,relay,"echo.test",echo.getLocalPort(),new byte[]{42});
                assertArrayEquals(new byte[]{42},Socks5UdpTest.receive(udp).data);
                assertFalse(StreamChannelRegistry.getInstance().getDatagramMap().containsKey(oldId));
                int count=client.getClient().getActiveStreamCount();
                ExchangeClient current=client;
                assertThrows(IllegalStateException.class,()->current.getClient().send(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(oldId).data(new byte[]{9}).build()));
                assertEquals(count,client.getClient().getActiveStreamCount(),"late old DATA must not create a stream on the new connection");
            }
            String probe=System.getProperty("proxy.udp.tunProbe");
            if(probe!=null) {
                Path log=Files.createTempFile("tun-udp-"+transport+"-",".log");
                Process process=new ProcessBuilder(probe,"127.0.0.1:"+localPort,"127.0.0.1",Integer.toString(echo.getLocalPort()))
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();fail("TUN packet probe timed out: "+log);}
                assertEquals(0,process.exitValue(),Files.readString(log));
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((dispatch.getSessionManager().activeCount()!=0 || !StreamChannelRegistry.getInstance().getDatagramMap().isEmpty()) && System.nanoTime()<end) Thread.sleep(10);
            assertEquals(0,dispatch.getSessionManager().activeCount(),"closed controls must release remote sockets");
            assertTrue(StreamChannelRegistry.getInstance().getDatagramMap().isEmpty());
        } finally {
            if(local!=null)local.close().sync();if(client!=null)client.close();if(remote!=null)remote.close();
            dispatch.shutdown();business.shutdownNow();workers.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();tls.delete();
        }
    }
}
