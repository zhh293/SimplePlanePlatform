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
 * 出站会话 —— 绑定一个客户端 Stream 到一条目标 TCP 连接
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

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CONNECTING);
    private final CompletableFuture<Void> activeFuture = new CompletableFuture<>();

    private volatile Channel outboundChannel;

    /**
     * @param inboundCtx  客户端 Stream 的 ChannelHandlerContext，用于回写数据
     * @param targetHost  目标主机
     * @param targetPort  目标端口
     * @param sessionKey  复合 session key（parentChannelId:streamId），用于 SessionManager 查找
     * @param rawStreamId 原始数字 streamId，用于回写推送消息时设置到 ProxyMessage
     */
    public OutboundSession(ChannelHandlerContext inboundCtx, String targetHost, int targetPort, String sessionKey, long rawStreamId) {
        this.inboundCtx = inboundCtx;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.sessionKey = sessionKey;
        this.rawStreamId = rawStreamId;
    }

    /**
     * 设置出站 Channel（连接成功后调用），状态迁移到 ACTIVE
     */
    public void setOutboundChannel(Channel channel) {
        this.outboundChannel = channel;
        if (state.compareAndSet(SessionState.CONNECTING, SessionState.ACTIVE)) {
            activeFuture.complete(null);
            log.debug("OutboundSession active: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
        }
    }

    /**
     * 将数据转发到目标服务器
     *
     * @param data 待转发的数据
     */
    public void forward(byte[] data) {
        if (state.get() != SessionState.ACTIVE) {
            log.warn("Cannot forward data, session not active: sessionKey={}, state={}", sessionKey, state.get());
            return;
        }
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(data));
        } else {
            log.warn("Outbound channel inactive, cannot forward: sessionKey={}", sessionKey);
        }
    }

    /**
     * 将目标返回的数据回写给客户端
     * <p>
     * 封装为 ProxyMessage（type=DATA），通过 inboundCtx 推回客户端 Stream。
     * </p>
     *
     * @param data 目标服务器返回的数据
     */
    public void writeBack(byte[] data) {
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
            inboundCtx.writeAndFlush(message);
        } else {
            log.warn("Inbound channel inactive, cannot writeBack: sessionKey={}", sessionKey);
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
            return true;
        } catch (Exception e) {
            log.warn("awaitActive timeout: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
            return false;
        }
    }

    /**
     * 关闭会话，释放出站连接
     */
    public void close() {
        if (state.getAndSet(SessionState.CLOSED) == SessionState.CLOSED) {
            return; // 已关闭，幂等
        }
        // 唤醒可能等待的 awaitActive
        activeFuture.complete(null);

        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.close();
            log.debug("Outbound channel closed: sessionKey={}, target={}:{}", sessionKey, targetHost, targetPort);
        }
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
