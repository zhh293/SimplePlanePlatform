package com.ladder.client.handler;

import com.ladder.client.crypto.EncryptionUtil;
import com.ladder.client.protocol.LadderMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class HttpProxyHandler extends ChannelInboundHandlerAdapter {
    private static final AtomicLong requestIdGenerator = new AtomicLong(0);
    private static final ConcurrentHashMap<Long, Channel> requestMap = new ConcurrentHashMap<>();
    private final Channel serverChannel;
    private final byte[] encryptionKey;
    
    // Tunnel mode state
    private boolean isTunnel = false;
    private long currentRequestId = -1;

    public HttpProxyHandler(Channel serverChannel, byte[] encryptionKey) {
        this.serverChannel = serverChannel;
        this.encryptionKey = encryptionKey;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (isTunnel) {
            // Tunnel mode: forward raw bytes
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                handleRelay(ctx, buf);
            } else {
                // Should not happen if codecs are removed correctly
                ctx.fireChannelRead(msg);
            }
            return;
        }

        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            if (HttpMethod.CONNECT.equals(request.method())) {
                handleConnect(ctx, request);
            } else {
                handleHttpRequest(ctx, request);
            }
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String host = request.uri();
        int port = 443;
        if (host.contains(":")) {
            String[] parts = host.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                // Default to 443 if port parsing fails
                port = 443;
            }
        }

        // Generate request ID
        currentRequestId = requestIdGenerator.incrementAndGet();
        requestMap.put(currentRequestId, ctx.channel());

        // Send TYPE_CONNECT to server
        String target = host + ":" + port;
        byte[] targetBytes = target.getBytes();
        byte[] encryptedTarget = EncryptionUtil.encrypt(targetBytes, encryptionKey);
        
        LadderMessage connectMsg = new LadderMessage(LadderMessage.TYPE_CONNECT, currentRequestId, encryptedTarget);
        serverChannel.writeAndFlush(connectMsg);

        // Respond 200 Connection Established to client
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, 
                new HttpResponseStatus(200, "Connection Established"));
        ctx.writeAndFlush(response);

        // Switch to Tunnel Mode
        switchToTunnelMode(ctx);
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Parse host from headers or URI
        String host = request.headers().get(HttpHeaderNames.HOST);
        int port = 80;
        if (host == null) {
            // Try to parse from URI
            // Simple parsing, assuming valid URI
            // Not robust but works for basic cases
             // In a real implementation, URI parsing should be more robust
             host = "unknown";
        }
        if (host.contains(":")) {
             String[] parts = host.split(":");
             host = parts[0];
             try {
                 port = Integer.parseInt(parts[1]);
             } catch (NumberFormatException e) {
                 port = 80;
             }
        }

        // Generate request ID
        currentRequestId = requestIdGenerator.incrementAndGet();
        requestMap.put(currentRequestId, ctx.channel());

        // Send TYPE_CONNECT to server (port 80 default for HTTP)
        String target = host + ":" + port;
        byte[] targetBytes = target.getBytes();
        byte[] encryptedTarget = EncryptionUtil.encrypt(targetBytes, encryptionKey);
        
        LadderMessage connectMsg = new LadderMessage(LadderMessage.TYPE_CONNECT, currentRequestId, encryptedTarget);
        serverChannel.writeAndFlush(connectMsg);

        // Send original request as TYPE_DATA
        byte[] requestBytes = httpRequestToBytes(request);
        byte[] encryptedData = EncryptionUtil.encrypt(requestBytes, encryptionKey);
        LadderMessage dataMsg = new LadderMessage(LadderMessage.TYPE_DATA, currentRequestId, encryptedData);
        serverChannel.writeAndFlush(dataMsg);

        // Switch to Tunnel Mode
        switchToTunnelMode(ctx);
    }
    
    private void switchToTunnelMode(ChannelHandlerContext ctx) {
        // Remove HTTP codecs to handle raw bytes
        if (ctx.pipeline().get(HttpServerCodec.class) != null) {
            ctx.pipeline().remove(HttpServerCodec.class);
        }
        if (ctx.pipeline().get(HttpObjectAggregator.class) != null) {
            ctx.pipeline().remove(HttpObjectAggregator.class);
        }
        isTunnel = true;
    }
    
    private void handleRelay(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        
        // Encrypt and send as TYPE_DATA
        byte[] encryptedData = EncryptionUtil.encrypt(bytes, encryptionKey);
        LadderMessage dataMsg = new LadderMessage(LadderMessage.TYPE_DATA, currentRequestId, encryptedData);
        serverChannel.writeAndFlush(dataMsg);
    }

    /**
     * Handles messages received from the Proxy Server.
     * Called by ServerHandler.
     */
    public static void handleServerMessage(long requestId, byte[] encryptedData, byte[] encryptionKey, byte type)
            throws Exception {
        // Get client channel
        Channel clientChannel = requestMap.get(requestId);
        if (clientChannel == null || !clientChannel.isActive()) {
            return;
        }

        if (type == LadderMessage.TYPE_DATA) {
            // Decrypt data
            byte[] decryptedData = EncryptionUtil.decrypt(encryptedData, encryptionKey);
            
            // Write to client channel
            clientChannel.writeAndFlush(Unpooled.wrappedBuffer(decryptedData));
        } else if (type == LadderMessage.TYPE_DISCONNECT) {
            // Close connection
            requestMap.remove(requestId);
            clientChannel.close();
        } else if (type == LadderMessage.TYPE_CONNECT) {
            // Connection established confirmation (optional handling)
            // Currently we assume success on client side for CONNECT
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (currentRequestId != -1) {
            requestMap.remove(currentRequestId);
            // Notify server to disconnect
            LadderMessage disconnectMsg = new LadderMessage(LadderMessage.TYPE_DISCONNECT, currentRequestId, null);
            serverChannel.writeAndFlush(disconnectMsg);
        }
        super.channelInactive(ctx);
    }

    private byte[] httpRequestToBytes(FullHttpRequest request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // 写入请求行
        baos.write(
                (request.method().name() + " " + request.uri() + " " + request.protocolVersion() + "\r\n").getBytes());
        // 写入请求头
        for (HttpHeaderNames name : request.headers().names()) {
            baos.write((name + ": " + request.headers().get(name) + "\r\n").getBytes());
        }
        baos.write("\r\n".getBytes());
        // 写入请求体
        if (request.content() != null && request.content().readableBytes() > 0) {
            byte[] content = new byte[request.content().readableBytes()];
            request.content().readBytes(content);
            baos.write(content);
        }
        return baos.toByteArray();
    }
}
