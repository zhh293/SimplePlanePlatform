package com.proxy.local.handler;

import com.proxy.common.id.StreamIdFactory;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stream 通道注册表 —— 维护 streamId → 浏览器 ChannelHandlerContext 的映射
 * <p>
 * 当浏览器通过 HTTP CONNECT 或 SOCKS5 CONNECT 建立隧道时，
 * 分配一个唯一的 streamId 并将浏览器的 ctx 注册到此表。
 * </p>
 * <p>
 * 远程服务器推送的 DATA（requestId=0）通过 streamId 从此表查找对应的浏览器 ctx，
 * 将目标服务器返回的数据写回浏览器。
 * </p>
 */
public class StreamChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamChannelRegistry.class);

    /**
     * 全局单例
     */
    private static final StreamChannelRegistry INSTANCE = new StreamChannelRegistry();

    /**
     * streamId 工厂：高 16 位编码 clientId（标识本 local 实例），低 48 位递增序列号。
     * 保证多个 local 实例连同一个 remote 时 streamId 绝不碰撞。
     */
    private final StreamIdFactory streamIdFactory = new StreamIdFactory();

    /**
     * streamId → 浏览器 ChannelHandlerContext
     */
    private final ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();

    private StreamChannelRegistry() {
    }

    public static StreamChannelRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 分配下一个全局唯一 streamId
     * <p>
     * streamId 结构：高 16 位为本 local 实例的 clientId，低 48 位为递增序列号。
     * 不同 local 实例的 clientId 不同，因此即使多 local 连同一 remote，streamId 也不碰撞。
     * </p>
     */
    public long nextStreamId() {
        return streamIdFactory.nextStreamId();
    }

    /**
     * 获取当前 local 实例的 clientId（监控/日志用）
     */
    public int getClientId() {
        return streamIdFactory.getClientId();
    }

    /**
     * 注册 streamId 与浏览器 ctx 的映射
     *
     * @param streamId 流标识
     * @param ctx      浏览器的 ChannelHandlerContext
     */
    public void register(long streamId, ChannelHandlerContext ctx) {
        registry.put(streamId, ctx);
        log.debug("Registered streamId={} → channel={}", streamId, ctx.channel().remoteAddress());
    }

    /**
     * 通过 streamId 查找浏览器 ctx
     *
     * @param streamId 流标识
     * @return 浏览器 ctx，未找到返回 null
     */
    public ChannelHandlerContext get(long streamId) {
        return registry.get(streamId);
    }

    /**
     * 注销 streamId 映射（浏览器断开时调用）
     *
     * @param streamId 流标识
     */
    public void unregister(long streamId) {
        ChannelHandlerContext removed = registry.remove(streamId);
        if (removed != null) {
            log.debug("Unregistered streamId={}", streamId);
        }
    }

    /**
     * 获取当前注册数量（监控用）
     */
    public int size() {
        return registry.size();
    }
}
