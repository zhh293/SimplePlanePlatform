package com.ladder.client;

import com.ladder.client.handler.HttpProxyHandler;
import com.ladder.client.handler.ServerHandler;
import com.ladder.client.protocol.LadderMessageDecoder;
import com.ladder.client.protocol.LadderMessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class LadderClient {
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 8888;
    private static final int PROXY_PORT = 1080;
    private static final byte[] ENCRYPTION_KEY = "1234567890123456".getBytes(); // 16字节密钥

    public static void main(String[] args) throws Exception {
        // 连接到远程服务器
        Channel serverChannel = connectToServer();

        // 启动本地代理服务器
        startProxyServer(serverChannel);

        System.out.println("Ladder client started. Proxy port: " + PROXY_PORT);
        System.out.println("Connected to server: " + SERVER_HOST + ":" + SERVER_PORT);
    }

    private static Channel connectToServer() throws Exception {
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new LadderMessageDecoder())
                                    .addLast(new LadderMessageEncoder())
                                    .addLast(new ServerHandler(ENCRYPTION_KEY));
                        }
                    });

            ChannelFuture f = b.connect(SERVER_HOST, SERVER_PORT).sync();
            return f.channel();
        } catch (Exception e) {
            workerGroup.shutdownGracefully();
            throw e;
        }
    }

    private static void startProxyServer(Channel serverChannel) throws Exception {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new HttpProxyHandler(serverChannel, ENCRYPTION_KEY));
                        }
                    });

            ChannelFuture f = b.bind(PROXY_PORT).sync();
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}