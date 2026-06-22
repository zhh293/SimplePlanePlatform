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

    /**
     * Session 最大存活时间（毫秒）。
     * <p>
     * 兜底机制：当 DISCONNECT 因网络抖动或客户端异常退出而丢失，
     * session 不会被正常流程关闭。超过此时间的 session 会被强制回收，
     * 防止 outbound TCP 连接和内存持续泄漏。
     * 默认 10 分钟，与客户端侧 Stream 兜底时间保持一致。
     * </p>
     */
    private static final long SESSION_MAX_LIFETIME_MS = 10 * 60 * 1000;

    private final ConcurrentHashMap<String, OutboundSession> sessions = new ConcurrentHashMap<>();

    /**
     * 定期扫描清理不活跃或超龄 session 的调度器
     */
    private final ScheduledExecutorService cleanupScheduler;

    public SessionManager() {
        // 每 10 秒扫描一次
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::cleanupSessions, 10, 10, TimeUnit.SECONDS);
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
     * 当前活跃会话数（监控用）
     */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * 扫描并清理 session：
     * <ol>
     *   <li><b>Inactive 清理</b>：inboundCtx channel 已不活跃（HTTP/2 stream 断开后触发），
     *       说明连接层已经断开，对应 session 可以立即回收。</li>
     *   <li><b>超龄兜底</b>：session 存活超过 {@link #SESSION_MAX_LIFETIME_MS}，
     *       但 inbound channel 仍然 active——这是 DISCONNECT 丢失导致的泄漏场景，
     *       强制关闭并记录 warn，供后续排查。</li>
     * </ol>
     */
    private void cleanupSessions() {
        int inactiveCleaned = 0;
        int staleCleaned = 0;
        long now = System.currentTimeMillis();

        for (java.util.Map.Entry<String, OutboundSession> entry : sessions.entrySet()) {
            OutboundSession session = entry.getValue();
            String key = entry.getKey();

            if (!session.getInboundCtx().channel().isActive()) {
                // 正常路径：stream channel 断了，session 应随之清理
                sessions.remove(key, session);
                session.close();
                inactiveCleaned++;
                log.info("Cleanup[inactive]: closed session, sessionKey={}, target={}:{}",
                        key, session.getTargetHost(), session.getTargetPort());

            } else if (now - session.getCreateTime() > SESSION_MAX_LIFETIME_MS) {
                // 兜底路径：channel 仍然 active，但 session 存活时间异常，DISCONNECT 可能已丢失
                sessions.remove(key, session);
                session.close();
                staleCleaned++;
                log.warn("Cleanup[stale]: force-closed long-lived session, sessionKey={}, target={}:{}, age={}s",
                        key, session.getTargetHost(), session.getTargetPort(),
                        (now - session.getCreateTime()) / 1000);
            }
        }

        if (inactiveCleaned > 0 || staleCleaned > 0) {
            log.info("Session cleanup: inactive={}, stale={}, remaining={}",
                    inactiveCleaned, staleCleaned, sessions.size());
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
