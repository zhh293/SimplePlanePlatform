package com.proxy.remote.dispatch;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.remote.outbound.OutboundSession;
import com.proxy.remote.outbound.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 服务端 Filter 链末端的请求分派器
 * <p>
 * 将请求提交到业务线程池异步处理，根据消息类型分派到不同的处理方法。
 * 通过 SessionManager 管理出站会话，通过 OutboundConnector 建立到目标的 TCP 连接。
 * </p>
 */
public class DispatchInvoker implements Invoker {

    private static final Logger log = LoggerFactory.getLogger(DispatchInvoker.class);

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
     * 创建 OutboundSession，通过 OutboundConnector 异步建立到目标的 TCP 连接。
     * 立即返回 OK（不等建连完成），后续 DATA 请求通过 awaitActive 等待连接就绪。
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

        String streamId = (String) invocation.getAttachment("streamId");
        ChannelHandlerContext inboundCtx = (ChannelHandlerContext) invocation.getAttachment("inboundCtx");

        // 创建出站会话
        OutboundSession session = new OutboundSession(inboundCtx, targetHost, targetPort, streamId);
        sessionManager.register(streamId, session);

        // 异步建立到目标的 TCP 连接
        connector.connect(targetHost, targetPort, session).whenComplete((channel, throwable) -> {
            if (throwable != null) {
                log.error("Outbound connect failed: target={}:{}, streamId={}", targetHost, targetPort, streamId, throwable);
                sessionManager.remove(streamId);
            } else {
                session.setOutboundChannel(channel);
            }
        });

        return Response.ok();
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

        // 桩模式（connector 未配置）：回显数据
        if (connector == null) {
            log.debug("Handle DATA (stub): dataLength={}", data != null ? data.length : 0);
            return Response.ok(data);
        }

        String streamId = (String) invocation.getAttachment("streamId");

        OutboundSession session = sessionManager.get(streamId);
        if (session == null) {
            log.warn("No session found for streamId={}, cannot forward DATA", streamId);
            return Response.error("No session for streamId=" + streamId);
        }

        // 如果还在 CONNECTING 状态，等待连接就绪
        if (session.getState() == OutboundSession.SessionState.CONNECTING) {
            if (!session.awaitActive(activeWaitTimeoutMs)) {
                log.warn("Session not active after waiting {}ms: streamId={}", activeWaitTimeoutMs, streamId);
                return Response.error("Outbound connection not ready, timeout");
            }
        }

        // 连接已关闭
        if (session.getState() == OutboundSession.SessionState.CLOSED) {
            log.warn("Session already closed: streamId={}", streamId);
            return Response.error("Session closed for streamId=" + streamId);
        }

        // 转发数据到目标
        session.forward(data);

        log.debug("Handle DATA: streamId={}, target={}:{}, dataLength={}",
                streamId, session.getTargetHost(), session.getTargetPort(),
                data != null ? data.length : 0);

        return Response.ok();
    }

    /**
     * 处理 DISCONNECT 请求
     * <p>
     * 通过 SessionManager 移除并关闭对应的 OutboundSession。
     * </p>
     */
    private Response handleDisconnect(Invocation invocation) {
        log.info("Handle DISCONNECT: target={}:{}", invocation.getTargetHost(), invocation.getTargetPort());

        // 桩模式（connector 未配置）：直接返回 OK
        if (connector == null) {
            return Response.ok();
        }

        String streamId = (String) invocation.getAttachment("streamId");
        sessionManager.remove(streamId);
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
