package com.proxy.remote.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

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
     * 关闭所有会话（服务关闭时调用）
     */
    public void closeAll() {
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
