package com.proxy.exchange.header;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局唯一 requestId 生成器
 * <p>
 * 使用 AtomicLong 递增，保证在单 JVM 内全局唯一。
 * 从 1 开始递增，0 保留作为无效 ID。
 * </p>
 */
public class RequestIdGenerator {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    /**
     * 生成下一个全局唯一的 requestId
     *
     * @return 唯一的 requestId
     */
    public static long next() {
        return ID_GENERATOR.incrementAndGet();
    }

    /**
     * 获取当前已生成的最大 ID（监控用）
     */
    public static long current() {
        return ID_GENERATOR.get();
    }

    /**
     * 重置（仅用于测试）
     */
    static void reset() {
        ID_GENERATOR.set(0);
    }
}
