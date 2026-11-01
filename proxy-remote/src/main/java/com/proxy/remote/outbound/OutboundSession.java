package com.proxy.remote.outbound;

import com.proxy.common.model.ProxyMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 出站会话 —— 绑定一个客户端 Stream 到一条目标 TCP 连接或固定目标 UDP 关联
 * <p>
 * 每个 OutboundSession 代表一个从客户端发起的 CONNECT 请求所建立的隧道：
 * 客户端 Stream ←→ OutboundSession ←→ 目标服务器 TCP 连接
 * </p>
 * <p>
 * 生命周期：CONNECTING → ACTIVE → CLOSED
 * </p>
 */
public class OutboundSession {

    private static final Logger log = LoggerFactory.getLogger(OutboundSession.class);

    /**
     * 会话状态枚举
     */
    public enum SessionState {
        CONNECTING, ACTIVE, CLOSED
    }

    private final ChannelHandlerContext inboundCtx;
    private final String targetHost;
    private final int targetPort;
    private final String sessionKey;
    private final long rawStreamId;
    private final boolean udp;
    public static final int MAX_UDP_PAYLOAD = 1472;

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CONNECTING);
    private final CompletableFuture<Void> activeFuture = new CompletableFuture<>();

    private volatile Channel outboundChannel;
    private volatile Runnable closeListener = () -> { };

    public void setCloseListener(Runnable listener) {
        closeListener = listener;
    }

    /**
     * @param inboundCtx  客户端 Stream 的 ChannelHandlerContext，用于回写数据
     * @param targetHost  目标主机
     * @param targetPort  目标端口
     * @param sessionKey  复合 session key（parentChannelId:streamId），用于 SessionManager 查找
     * @param rawStreamId 原始数字 streamId，用于回写推送消息时设置到 ProxyMessage
     */
    public OutboundSession(ChannelHandlerContext inboundCtx, String targetHost, int targetPort, String sessionKey, long rawStreamId) {
        this(inboundCtx, targetHost, targetPort, sessionKey, rawStreamId, false);
    }

    public OutboundSession(ChannelHandlerContext inboundCtx, String targetHost, int targetPort, String sessionKey, long rawStreamId, boolean udp) {
        this.inboundCtx = inboundCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.sessionKey = sessionKey;
        this.rawStreamId = rawStreamId;
        this.udp = udp;
    }

    /**
     * 设置出站 Channel（连接成功后调用），状态迁移到 ACTIVE
     */
    public synchronized boolean setOutboundChannel(Channel channel) {
        if (state.get() != SessionState.CONNECTING || !inboundCtx.channel().isActive()) {
            channel.close();
            close();
            return false;
        }
        this.outboundChannel = channel;
        if (state.compareAndSet(SessionState.CONNECTING, SessionState.ACTIVE)) {
            activeFuture.complete(null);
            log.debug("OutboundSession active: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
        }
        return true;
    }

    /**
     * 将数据转发到目标服务器
     * <p>
     * 通过 outboundChannel 的 EventLoop 串行执行写入，保证同一个 session
     * 的多个 DATA 帧即使在线程池中被并发调度，写入目标的顺序也与到达顺序一致。
     * </p>
     *
     * @param data 待转发的数据
     */
    public void forward(byte[] data) {
        // ProxyCodec represents a wire dataLength of zero as null.
        if (udp && data == null) data = new byte[0];
        if (udp && (data.length > MAX_UDP_PAYLOAD
                || outboundChannel == null || !outboundChannel.isWritable())) {
            log.debug("UDP drop: invalid size or outbound backpressure, sessionKey={}", sessionKey);
            return;
        }
        if (state.get() != SessionState.ACTIVE) {
            log.warn("Cannot forward data, session not active: sessionKey={}, state={}", sessionKey, state.get());
            return;
        }
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(data)).addListener(f -> {
                if (!f.isSuccess()) closeFromTarget();
            });
        } else {
            // outbound 已断开，关闭 session 避免无效的后续调用
            log.info("Outbound channel inactive, closing session: sessionKey={}", sessionKey);
            closeFromTarget();
        }
    }

    /**
     * 将目标返回的数据回写给客户端（仅 write，不 flush）
     * <p>
     * 封装为 ProxyMessage（type=DATA），通过 inboundCtx 写入出站缓冲区。
     * 调用方应在一批数据写完后调用 {@link #flush()} 统一刷出。
     * </p>
     *
     * @param data 目标服务器返回的数据
     */
    public void writeBack(byte[] data) {
        if (udp && (data.length > MAX_UDP_PAYLOAD || !inboundCtx.channel().isWritable())) {
            log.debug("UDP drop: oversized response or inbound backpressure, sessionKey={}", sessionKey);
            return;
        }
        if (state.get() == SessionState.CLOSED) {
            log.debug("Session closed, ignoring writeBack: sessionKey={}", sessionKey);
            return;
        }
        if (inboundCtx.channel().isActive()) {
            ProxyMessage message = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .host(targetHost)
                    .port(targetPort)
                    .streamId(rawStreamId)
                    .data(data)
                    .build();
            inboundCtx.writeAndFlush(message).addListener(f -> {
                if (!f.isSuccess()) {
                    close();
                    inboundCtx.close();
                }
            });
            log.debug("writeBack: sessionKey={}, dataLen={}", sessionKey, data.length);
        } else {
            // 客户端 stream 已断开，主动关闭 outbound 连接，停止从目标网站接收数据
            log.info("Inbound channel inactive, closing outbound session: sessionKey={}, target={}:{}",
                    sessionKey, targetHost, targetPort);
            close();
        }
    }

    /**
     * 刷出 inbound channel 上已积累的数据。
     * <p>
     * 由 OutboundHandler.channelReadComplete 在一批 read 结束后调用，
     * 将多个 ProxyMessage 合并为一次 flush（一次 syscall），大幅提升吞吐。
     * </p>
     */
    public void flush() {
        if (state.get() != SessionState.CLOSED && inboundCtx.channel().isActive()) {
            inboundCtx.flush();
        }
    }

    /**
     * 等待会话变为 ACTIVE 状态
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return true 表示已就绪，false 表示超时
     */
    public boolean awaitActive(long timeoutMs) {
        if (state.get() == SessionState.ACTIVE) {
            return true;
        }
        if (state.get() == SessionState.CLOSED) {
            return false;
        }
        try {
            activeFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return state.get() == SessionState.ACTIVE;
        } catch (Exception e) {
            log.warn("awaitActive timeout: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
            return false;
        }
    }

    /**
     * 关闭会话，释放出站连接
     */
    public void close() {
        closeSession();
    }

    /** Unsolicited target EOF/error: flush prior DATA before ending the tunnel. */
    public void closeFromTarget() {
        if (!closeSession()) return;
        if (inboundCtx.channel().isActive()) {
            ProxyMessage end = ProxyMessage.builder().type(ProxyMessage.MessageType.DISCONNECT)
                    .streamId(rawStreamId).host(targetHost).port(targetPort).build();
            inboundCtx.writeAndFlush(end).addListener(write -> {
                if (!write.isSuccess()) {
                    inboundCtx.close();
                } else if (inboundCtx.channel() instanceof io.netty.channel.socket.DuplexChannel) {
                    ((io.netty.channel.socket.DuplexChannel) inboundCtx.channel()).shutdownOutput();
                } else {
                    inboundCtx.close();
                }
            });
        } else {
            inboundCtx.close();
        }
    }

    private synchronized boolean closeSession() {
        if (state.getAndSet(SessionState.CLOSED) == SessionState.CLOSED) {
            return false;
        }
        // 唤醒可能等待的 awaitActive
        activeFuture.completeExceptionally(new java.nio.channels.ClosedChannelException());

        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.close();
            log.debug("Outbound channel closed: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
        }
        closeListener.run();
        return true;
    }

    // ---- Getters ----

    public SessionState getState() {
        return state.get();
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public long getRawStreamId() {
        return rawStreamId;
    }

    public ChannelHandlerContext getInboundCtx() {
        return inboundCtx;
    }

    public Channel getOutboundChannel() {
        return outboundChannel;
    }
}
