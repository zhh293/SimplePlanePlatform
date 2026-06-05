package com.proxy.common.id;

import java.util.concurrent.atomic.AtomicLong;

/**
 * StreamId 工厂 —— 将 clientId 编码进 streamId 的高位
 * <p>
 * 64 位 streamId 的位布局：
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Bit 63                                                   Bit 0 │
 * ├──────────────────┬──────────────────────────────────────────────┤
 * │ clientId (16bit) │          sequence (48bit)                    │
 * │  高 16 位         │         低 48 位                             │
 * └──────────────────┴──────────────────────────────────────────────┘
 * </pre>
 * </p>
 * <p>
 * 设计优势：
 * <ul>
 *   <li>无需修改二进制协议格式（streamId 仍然是 8 字节 long）</li>
 *   <li>服务端无需感知 clientId 概念，只要 streamId 全局唯一，sessionKey 就不碰撞</li>
 *   <li>48 位序列号可表示 281,474,976,710,656 个会话，永不溢出</li>
 *   <li>16 位 clientId 支持 65535 个 local 实例并存</li>
 * </ul>
 * </p>
 * <p>
 * 使用方式：每个 local 实例启动时创建一个 StreamIdFactory 实例（自动生成 clientId），
 * 后续通过 {@link #nextStreamId()} 获取全局唯一的 streamId。
 * </p>
 */
public class StreamIdFactory {

    /** 48 位序列号掩码 */
    private static final long SEQUENCE_MASK = 0x0000_FFFF_FFFF_FFFFL;

    /** 当前 local 实例的 clientId（高 16 位） */
    private final long clientIdPrefix;

    /** 48 位递增序列号 */
    private final AtomicLong sequence = new AtomicLong(0);

    /** clientId 原始值（用于日志/监控） */
    private final int clientId;

    /**
     * 使用自动生成的 clientId 创建工厂
     */
    public StreamIdFactory() {
        this(ClientIdGenerator.generate());
    }

    /**
     * 使用指定的 clientId 创建工厂（测试用）
     *
     * @param clientId 客户端标识（1~65535）
     */
    public StreamIdFactory(int clientId) {
        if (clientId <= 0 || clientId > 0xFFFF) {
            throw new IllegalArgumentException("clientId must be in [1, 65535], got: " + clientId);
        }
        this.clientId = clientId;
        // 左移 48 位到高 16 位
        this.clientIdPrefix = ((long) clientId) << 48;
    }

    /**
     * 生成下一个全局唯一的 streamId
     * <p>
     * 结构：高 16 位为 clientId，低 48 位为递增序列号。
     * 线程安全，可并发调用。
     * </p>
     *
     * @return 全局唯一的 streamId
     */
    public long nextStreamId() {
        long seq = sequence.incrementAndGet() & SEQUENCE_MASK;
        return clientIdPrefix | seq;
    }

    /**
     * 从 streamId 中提取 clientId（高 16 位）
     *
     * @param streamId 完整的 streamId
     * @return clientId 部分
     */
    public static int extractClientId(long streamId) {
        return (int) ((streamId >>> 48) & 0xFFFF);
    }

    /**
     * 从 streamId 中提取序列号（低 48 位）
     *
     * @param streamId 完整的 streamId
     * @return 序列号部分
     */
    public static long extractSequence(long streamId) {
        return streamId & SEQUENCE_MASK;
    }

    /**
     * 获取当前 clientId
     */
    public int getClientId() {
        return clientId;
    }

    /**
     * 获取当前已生成的序列号数量（监控用）
     */
    public long getCurrentSequence() {
        return sequence.get();
    }

    @Override
    public String toString() {
        return "StreamIdFactory{clientId=" + clientId +
                " (0x" + Integer.toHexString(clientId) + ")" +
                ", currentSeq=" + sequence.get() + "}";
    }
}
