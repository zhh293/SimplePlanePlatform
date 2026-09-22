package com.proxy.remote.dispatch;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.remote.outbound.OutboundSession;
import com.proxy.remote.outbound.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 服务端 Filter 链末端的请求分派器
 * <p>
 * 将请求提交到业务线程池异步处理，根据消息类型分派到不同的处理方法。
 * 通过 SessionManager 管理出站会话，通过 OutboundConnector 建立到目标的 TCP 连接。
 * </p>
 */
public class DispatchInvoker implements Invoker {

    private static final Logger log = LoggerFactory.getLogger(DispatchInvoker.class);
    private static final int CONNECT_ATTEMPTS = 2;

    private final ExecutorService bizExecutor;
    private final OutboundConnector connector;
    private final SessionManager sessionManager;
    private final long activeWaitTimeoutMs;

    /**
     * Phase 1 兼容构造函数 —— 不接入出站连接，CONNECT/DISCONNECT 直接返回 OK，DATA 回显
     *
     * @param bizExecutor 业务线程池
     */
    public DispatchInvoker(ExecutorService bizExecutor) {
        this(bizExecutor, null, 5000);
    }

    /**
     * Phase 2 完整构造函数 —— 接入 OutboundConnector 实现完整出站逻辑
     *
     * @param bizExecutor          业务线程池
     * @param connector            出站连接器（null 时走桩逻辑）
     * @param activeWaitTimeoutMs  等待出站连接就绪的超时时间（毫秒）
     */
    public DispatchInvoker(ExecutorService bizExecutor, OutboundConnector connector, long activeWaitTimeoutMs) {
        this.bizExecutor = bizExecutor;
        this.connector = connector;
        this.sessionManager = connector != null ? new SessionManager() : null;
        this.activeWaitTimeoutMs = activeWaitTimeoutMs;
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        CompletableFuture<Response> future = new CompletableFuture<>();

        // DATA 消息：如果 session 已 ACTIVE 则直接在 IO 线程处理（非阻塞 writeAndFlush），
        // 保证同一个 stream 的帧严格按到达顺序 forward，防止 TLS record 乱序。
        // 如果 session 还在 CONNECTING，则退化到线程池等待（此时只有第一个 DATA 帧需要等待）。
        if (invocation.getType() == ProxyMessage.MessageType.DATA && connector != null) {
            String sk = (String) invocation.getAttachment("streamId");
            OutboundSession sess = sessionManager.get(sk);
            if (sess != null && sess.getState() == OutboundSession.SessionState.ACTIVE) {
                // 已就绪，直接在当前 IO 线程转发（保序）
                try {
                    sess.forward(invocation.getData());
                    log.debug("Handle DATA (fast-path): sessionKey={}, dataLength={}",
                            sk, invocation.getData() != null ? invocation.getData().length : 0);
                } catch (Exception e) {
                    log.error("Fast-path DATA error: sessionKey={}", sk, e);
                    future.complete(Response.error("Forward error: " + e.getMessage()));
                    return future;
                }
                future.complete(null); // DATA 为发后即忘
                return future;
            }
            // session 不存在或还在 CONNECTING → 退化到线程池
        }

        // CONNECT / DISCONNECT 提交到业务线程池异步处理
        try {
            bizExecutor.execute(() -> {
                try {
                    Response response = dispatch(invocation);
                    future.complete(response);
                } catch (Exception e) {
                    log.error("Dispatch error for type={}, targetHost={}:{}",
                            invocation.getType(), invocation.getTargetHost(), invocation.getTargetPort(), e);
                    future.complete(Response.error("Dispatch error: " + e.getMessage()));
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Business thread pool is full, rejecting request: type={}", invocation.getType());
            future.complete(Response.error("Server busy: thread pool exhausted"));
        }

        return future;
    }

    /**
     * 根据消息类型分派到对应的处理方法
     */
    private Response dispatch(Invocation invocation) {
        ProxyMessage.MessageType type = invocation.getType();
        if (type == null) {
            return Response.error("Unsupported message type: null");
        }

        switch (type) {
            case CONNECT:
                return handleConnect(invocation);
            case DATA:
                return handleData(invocation);
            case DISCONNECT:
                return handleDisconnect(invocation);
            default:
                return Response.error("Unsupported message type: " + type);
        }
    }

    /**
     * 处理 CONNECT 请求
     * <p>
     * 创建 OutboundSession，通过 OutboundConnector 建立到目标的 TCP 连接。
     * 同步等待连接建立完成后才返回 Response，确保 DefaultFuture 拿到的响应
     * 语义为"连接已建立"或"连接失败"，而非仅仅"请求已收到"。
     * </p>
     * <p>
     * 注意：此方法运行在 bizExecutor 线程池中，阻塞等待不会影响 Netty IO 线程。
     * </p>
     */
    private Response handleConnect(Invocation invocation) {
        String targetHost = invocation.getTargetHost();
        int targetPort = invocation.getTargetPort();

        log.info("Handle CONNECT: target={}:{}", targetHost, targetPort);

        // 桩模式（connector 未配置）：直接返回 OK
        if (connector == null) {
            return Response.ok();
        }

        String sessionKey = (String) invocation.getAttachment("streamId");
        long rawStreamId = (Long) invocation.getAttachment("rawStreamId");
        ChannelHandlerContext inboundCtx = (ChannelHandlerContext) invocation.getAttachment("inboundCtx");

        // 创建出站会话（sessionKey 用于 SessionManager 查找，rawStreamId 用于回写消息）
        OutboundSession session = new OutboundSession(inboundCtx, targetHost, targetPort, sessionKey, rawStreamId);
        sessionManager.register(sessionKey, session);

        // 同步等待出站连接建立（在 bizExecutor 线程中阻塞，不影响 IO 线程）
        Exception lastError = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                Channel channel = connector.connect(targetHost, targetPort, session, sessionManager)
                        .get(activeWaitTimeoutMs, TimeUnit.MILLISECONDS);
                session.setOutboundChannel(channel);
                log.info("CONNECT success: target={}:{}, sessionKey={}, attempt={}",
                        targetHost, targetPort, sessionKey, attempt);
                return Response.ok();
            } catch (Exception e) {
                lastError = e;
                log.warn("CONNECT attempt failed: target={}:{}, sessionKey={}, attempt={}/{}",
                        targetHost, targetPort, sessionKey, attempt, CONNECT_ATTEMPTS, e);
            }
        }

        log.error("CONNECT failed after retries: target={}:{}, sessionKey={}",
                targetHost, targetPort, sessionKey, lastError);
        sessionManager.remove(sessionKey);
        return Response.error("Connect to " + targetHost + ":" + targetPort
                + " failed after retries: " + (lastError != null ? lastError.getMessage() : "unknown error"));
    }

    /**
     * 处理 DATA 请求
     * <p>
     * 从 SessionManager 获取 session，如果连接仍在建立中则等待就绪，
     * 就绪后将数据转发到目标服务器。
     * </p>
     */
    private Response handleData(Invocation invocation) {
        byte[] data = invocation.getData();

        // 桩模式（connector 未配置）：以“服务端推送”的方式回显数据。
        // 数据面已统一为流式 push：不再走请求-响应，而是构造 requestId=0 + streamId 的
        // DATA 消息直接经 inboundCtx 回写，模拟真实出站会话的反向数据路径。
        if (connector == null) {
            log.debug("Handle DATA (stub push): dataLength={}", data != null ? data.length : 0);
            ChannelHandlerContext inboundCtx =
                    (ChannelHandlerContext) invocation.getAttachment("inboundCtx");
            Object rawStreamIdObj = invocation.getAttachment("rawStreamId");
            if (inboundCtx != null && rawStreamIdObj instanceof Long
                    && inboundCtx.channel().isActive()) {
                ProxyMessage push = ProxyMessage.builder()
                        .type(ProxyMessage.MessageType.DATA)
                        .host(invocation.getTargetHost())
                        .port(invocation.getTargetPort())
                        .streamId((Long) rawStreamIdObj)
                        .data(data)
                        .build();
                // requestId 默认 0：标记为服务端推送，客户端经 ExchangeHandler.handlePush() 路由
                inboundCtx.writeAndFlush(push);
            } else {
                log.warn("Stub DATA push skipped: inboundCtx unavailable or inactive, streamId={}",
                        rawStreamIdObj);
            }
            // DATA 为发后即忘，无需自动回写响应
            return null;
        }

        String sessionKey = (String) invocation.getAttachment("streamId");

        OutboundSession session = sessionManager.get(sessionKey);
        if (session == null) {
            log.warn("No session found for sessionKey={}, cannot forward DATA", sessionKey);
            return Response.error("No session for sessionKey=" + sessionKey);
        }

        // 如果还在 CONNECTING 状态，等待连接就绪
        if (session.getState() == OutboundSession.SessionState.CONNECTING) {
            if (!session.awaitActive(activeWaitTimeoutMs)) {
                log.warn("Session not active after waiting {}ms: sessionKey={}", activeWaitTimeoutMs, sessionKey);
                return Response.error("Outbound connection not ready, timeout");
            }
        }

        // 连接已关闭
        if (session.getState() == OutboundSession.SessionState.CLOSED) {
            log.warn("Session already closed: sessionKey={}", sessionKey);
            return Response.error("Session closed for sessionKey=" + sessionKey);
        }

        // 转发数据到目标
        session.forward(data);

        log.debug("Handle DATA: sessionKey={}, target={}:{}, dataLength={}",
                sessionKey, session.getTargetHost(), session.getTargetPort(),
                data != null ? data.length : 0);

        // DATA 为发后即忘：目标的回包由 OutboundSession 经 inboundCtx 主动推送，
        // 此处无需自动回写响应。
        return null;
    }

    /**
     * 处理 DISCONNECT 请求
     * <p>
     * 通过 SessionManager 移除并关闭对应的 OutboundSession。
     * 同步等待 outbound channel 真正关闭后才返回 Response，
     * 确保 DefaultFuture 拿到的响应语义为"隧道已断开"。
     * </p>
     */
    private Response handleDisconnect(Invocation invocation) {
        log.info("Handle DISCONNECT: target={}:{}", invocation.getTargetHost(), invocation.getTargetPort());

        // 桩模式（connector 未配置）：直接返回 OK
        if (connector == null) {
            return Response.ok();
        }

        String sessionKey = (String) invocation.getAttachment("streamId");
        OutboundSession session = sessionManager.remove(sessionKey);

        if (session == null) {
            // session 已不存在（可能已被异常清理），直接返回 OK
            log.debug("DISCONNECT but session not found: sessionKey={}", sessionKey);
            return Response.ok();
        }

        // 等待 outbound channel 真正关闭
        Channel outbound = session.getOutboundChannel();
        if (outbound != null && outbound.isActive()) {
            try {
                outbound.close().await(activeWaitTimeoutMs, TimeUnit.MILLISECONDS);
                log.info("DISCONNECT completed: sessionKey={}, target={}:{}",
                        sessionKey, invocation.getTargetHost(), invocation.getTargetPort());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("DISCONNECT interrupted: sessionKey={}", sessionKey);
            }
        } else {
            log.info("DISCONNECT completed (channel already inactive): sessionKey={}", sessionKey);
        }

        return Response.ok();
    }

    /**
     * 关闭所有出站会话（服务关闭时调用）
     */
    public void shutdown() {
        sessionManager.closeAll();
    }

    /**
     * 获取 SessionManager（监控/测试用）
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
