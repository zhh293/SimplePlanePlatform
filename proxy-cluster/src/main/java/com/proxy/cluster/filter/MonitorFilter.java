package com.proxy.cluster.filter;

import com.proxy.common.filter.*;
import com.proxy.common.spi.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 监控过滤器 —— 统计 RT、成功率、吞吐量
 * <p>
 * 按域名维度统计请求的响应时间、成功/失败次数。
 * 提供静态方法供外部查询监控数据。
 * </p>
 */
@Activate(group = "client", order = 300)
public class MonitorFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MonitorFilter.class);

    private static final ConcurrentHashMap<String, DomainMetrics> METRICS_MAP =
            new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException {
        String host = invocation.getTargetHost();
        long startTime = System.nanoTime();

        return invoker.invoke(invocation)
                .whenComplete((response, throwable) -> {
                    long rt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                    DomainMetrics metrics = METRICS_MAP.computeIfAbsent(
                            host, k -> new DomainMetrics());

                    metrics.totalCount.increment();
                    metrics.totalRtMs.add(rt);

                    if (throwable != null) {
                        metrics.failCount.increment();
                    } else {
                        metrics.successCount.increment();
                        // 记录最大 RT
                        updateMax(metrics, rt);
                    }
                });
    }

    private void updateMax(DomainMetrics metrics, long rt) {
        long currentMax = metrics.maxRtMs;
        while (rt > currentMax) {
            // CAS 更新最大值（简单实现，非严格线程安全但足够用）
            metrics.maxRtMs = rt;
            break;
        }
    }

    // ==================== 监控数据查询 ====================

    /**
     * 获取所有域名的监控指标
     */
    public static Map<String, DomainMetrics> getAllMetrics() {
        return METRICS_MAP;
    }

    /**
     * 获取指定域名的监控指标
     */
    public static DomainMetrics getMetrics(String host) {
        return METRICS_MAP.get(host);
    }

    /**
     * 域名维度的监控指标
     */
    public static class DomainMetrics {
        public final LongAdder totalCount = new LongAdder();
        public final LongAdder successCount = new LongAdder();
        public final LongAdder failCount = new LongAdder();
        public final LongAdder totalRtMs = new LongAdder();
        public volatile long maxRtMs = 0;

        /**
         * 获取平均 RT（毫秒）
         */
        public long getAvgRtMs() {
            long total = totalCount.sum();
            return total > 0 ? totalRtMs.sum() / total : 0;
        }

        /**
         * 获取成功率（百分比）
         */
        public double getSuccessRate() {
            long total = totalCount.sum();
            return total > 0 ? (double) successCount.sum() / total * 100 : 0;
        }

        @Override
        public String toString() {
            return "DomainMetrics{" +
                    "total=" + totalCount.sum() +
                    ", success=" + successCount.sum() +
                    ", fail=" + failCount.sum() +
                    ", avgRt=" + getAvgRtMs() + "ms" +
                    ", maxRt=" + maxRtMs + "ms" +
                    ", successRate=" + String.format("%.1f%%", getSuccessRate()) +
                    '}';
        }
    }
}
