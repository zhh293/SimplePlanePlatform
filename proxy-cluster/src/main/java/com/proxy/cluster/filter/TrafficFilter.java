package com.proxy.cluster.filter;

import com.proxy.common.filter.*;
import com.proxy.common.spi.Activate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

/**
 * 流量统计过滤器 —— 统计上下行字节数
 * <p>
 * 记录所有经过代理的上行（请求）和下行（响应）流量。
 * 提供静态方法供外部查询流量数据。
 * </p>
 */
@Activate(group = "client", order = 600)
public class TrafficFilter implements Filter {

    private static final LongAdder UPLOAD_BYTES = new LongAdder();
    private static final LongAdder DOWNLOAD_BYTES = new LongAdder();
    private static final LongAdder REQUEST_COUNT = new LongAdder();

    @Override
    public CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException {
        // 统计上行流量
        if (invocation.getData() != null) {
            UPLOAD_BYTES.add(invocation.getData().length);
        }
        REQUEST_COUNT.increment();

        return invoker.invoke(invocation)
                .thenApply(response -> {
                    // 统计下行流量
                    if (response.getData() != null) {
                        DOWNLOAD_BYTES.add(response.getData().length);
                    }
                    return response;
                });
    }

    // ==================== 流量数据查询 ====================

    public static long getUploadBytes() {
        return UPLOAD_BYTES.sum();
    }

    public static long getDownloadBytes() {
        return DOWNLOAD_BYTES.sum();
    }

    public static long getTotalBytes() {
        return UPLOAD_BYTES.sum() + DOWNLOAD_BYTES.sum();
    }

    public static long getRequestCount() {
        return REQUEST_COUNT.sum();
    }

    /**
     * 获取人类可读的流量信息
     */
    public static String getTrafficSummary() {
        return String.format("Traffic: upload=%s, download=%s, total=%s, requests=%d",
                humanReadableBytes(UPLOAD_BYTES.sum()),
                humanReadableBytes(DOWNLOAD_BYTES.sum()),
                humanReadableBytes(getTotalBytes()),
                REQUEST_COUNT.sum());
    }

    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
