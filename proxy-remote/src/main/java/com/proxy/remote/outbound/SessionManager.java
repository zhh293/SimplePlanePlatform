package com.proxy.remote.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话映射管理器
 * <p>
 * 管理 streamId → OutboundSession 的映射关系，
 * 提供会话的注册、查询、移除和批量清理能力。
 * </p>
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, OutboundSession> sessions = new ConcurrentHashMap<>();

    /**
     * 定期扫描清理不活跃 session 的调度器
     */
    private final ScheduledExecutorService cleanupScheduler;

    public SessionManager() {
        // 每 10 秒扫描一次，清理 inboundCtx 已不活跃的 session
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::cleanupInactiveSessions, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 注册会话
     *
     * @param streamId 客户端 Stream ID
     * @param session  出站会话
     */
    public void register(String streamId, OutboundSession session) {
        OutboundSession prev = sessions.put(streamId, session);
        if (prev != null) {
            log.warn("Replacing existing session for streamId={}, closing old session", streamId);
            prev.close();
        }
        log.debug("Session registered: streamId={}, target={}:{}", streamId, session.getTargetHost(), session.getTargetPort());
    }

    /**
     * 查询会话
     *
     * @param streamId 客户端 Stream ID
     * @return 对应的 OutboundSession，不存在返回 null
     */
    public OutboundSession get(String streamId) {
        return sessions.get(streamId);
    }

    /**
     * 移除并关闭会话
     *
     * @param streamId 客户端 Stream ID
     * @return 被移除的 session，不存在返回 null
     */
    public OutboundSession remove(String streamId) {
        OutboundSession session = sessions.remove(streamId);
        if (session != null) {
            session.close();
            log.debug("Session removed and closed: streamId={}, target={}:{}",
                    streamId, session.getTargetHost(), session.getTargetPort());
        }
        return session;
    }

    /**
     * Remove a session only if it is still the mapping registered for the key.
     * This prevents an old outbound channel's inactive callback from deleting
     * a newer session after a stream id has been reused by a reconnecting client.
     */
    public boolean removeIfSame(String streamId, OutboundSession expected) {
        if (!sessions.remove(streamId, expected)) {
            return false;
        }
        expected.close();
        log.debug("Session removed after outbound channel closed: streamId={}, target={}:{}",
                streamId, expected.getTargetHost(), expected.getTargetPort());
        return true;
    }

    /**
     * 当前活跃会话数（监控用）
     */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * 扫描并清理 inboundCtx 已不活跃的 session
     * <p>
     * 当客户端 HTTP/2 stream 断开但 DISCONNECT 未发到时，
     * 这个定期清理机制保证 session 不会永远泄漏。
     * </p>
     */
    private void cleanupInactiveSessions() {
        int cleaned = 0;
        for (java.util.Map.Entry<String, OutboundSession> entry : sessions.entrySet()) {
            OutboundSession session = entry.getValue();
            if (!session.getInboundCtx().channel().isActive()) {
                sessions.remove(entry.getKey(), session);
                session.close();
                cleaned++;
                log.info("Cleanup: closed inactive session, sessionKey={}, target={}:{}",
                        entry.getKey(), session.getTargetHost(), session.getTargetPort());
            }
        }
        if (cleaned > 0) {
            log.info("Session cleanup completed: {} sessions cleaned, {} remaining", cleaned, sessions.size());
        }
    }

    /**
     * 关闭所有会话（服务关闭时调用）
     */
    public void closeAll() {
        cleanupScheduler.shutdownNow();
        int count = sessions.size();
        sessions.forEach((streamId, session) -> {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Error closing session: streamId={}", streamId, e);
            }
        });
        sessions.clear();
        log.info("SessionManager closeAll: {} sessions closed", count);
    }
}
