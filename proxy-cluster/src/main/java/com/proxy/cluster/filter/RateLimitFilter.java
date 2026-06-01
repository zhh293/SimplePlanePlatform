package com.proxy.cluster.filter;

import com.proxy.common.filter.*;
import com.proxy.common.spi.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流过滤器 —— 防止瞬间流量打爆远程服务器
 * <p>
 * 实现简单的滑动窗口限流：
 * - 全局限流：每秒最多 500 个请求
 * - 按域名限流：每个域名每秒最多 50 个请求
 * </p>
 */
@Activate(group = "client", order = 200)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 全局每秒最大请求数 */
    private static final int GLOBAL_QPS_LIMIT = 500;

    /** 每域名每秒最大请求数 */
    private static final int DOMAIN_QPS_LIMIT = 50;

    /** 全局滑动窗口 */
    private final SlidingWindowCounter globalCounter = new SlidingWindowCounter();

    /** 域名级滑动窗口 */
    private final ConcurrentHashMap<String, SlidingWindowCounter> domainCounters =
            new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException {
        String host = invocation.getTargetHost();

        // 全局限流检查
        if (!globalCounter.tryAcquire(GLOBAL_QPS_LIMIT)) {
            log.warn("Global rate limit exceeded, current QPS > {}", GLOBAL_QPS_LIMIT);
            return CompletableFuture.completedFuture(
                    Response.error("Global rate limit exceeded"));
        }

        // 域名级限流检查
        SlidingWindowCounter domainCounter = domainCounters.computeIfAbsent(
                host, k -> new SlidingWindowCounter());
        if (!domainCounter.tryAcquire(DOMAIN_QPS_LIMIT)) {
            log.warn("Domain rate limit exceeded for {}, current QPS > {}", host, DOMAIN_QPS_LIMIT);
            return CompletableFuture.completedFuture(
                    Response.error("Domain rate limit exceeded: " + host));
        }

        return invoker.invoke(invocation);
    }

    /**
     * 简单的滑动窗口计数器（1 秒窗口）
     */
    private static class SlidingWindowCounter {
        private final AtomicLong windowStart = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            long currentWindow = now / 1000; // 按秒取整

            long lastWindow = windowStart.get();
            if (currentWindow != lastWindow) {
                // 新的时间窗口，重置计数
                if (windowStart.compareAndSet(lastWindow, currentWindow)) {
                    count.set(0);
                }
            }

            // 尝试获取
            int current = count.incrementAndGet();
            return current <= limit;
        }
    }
}
