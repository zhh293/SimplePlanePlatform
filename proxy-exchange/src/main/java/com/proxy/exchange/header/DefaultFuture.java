package com.proxy.exchange.header;

import com.proxy.common.filter.Response;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心组件：管理 requestId → CompletableFuture 的映射
 * <p>
 * 发送端: 创建 Future 放入 MAP → 发送请求 → 阻塞等待
 * 接收端: 收到响应 → 根据 requestId 查 MAP → complete(response) → 唤醒发送端
 * </p>
 * <p>
 * 与 Dubbo 的 DefaultFuture 设计完全一致。
 * </p>
 */
public class DefaultFuture extends CompletableFuture<Response> {

    private static final Logger log = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * 全局映射: requestId → Future
     */
    private static final ConcurrentHashMap<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();

    /**
     * 超时检测轮（50ms tick，512 slots）
     */
    private static final HashedWheelTimer TIMEOUT_TIMER = new HashedWheelTimer(
            r -> {
                Thread t = new Thread(r, "exchanger-timeout-checker");
                t.setDaemon(true);
                return t;
            },
            50, TimeUnit.MILLISECONDS, 512);

    /**
     * 标记是否已关闭（防止关闭后继续创建 Future）
     */
    private static final AtomicBoolean CLOSED = new AtomicBoolean(false);

    private final long requestId;
    private final long timeoutMs;
    private final long createTime;
    private Timeout timeoutTask;

    private DefaultFuture(long requestId, long timeoutMs) {
        this.requestId = requestId;
        this.timeoutMs = timeoutMs;
        this.createTime = System.currentTimeMillis();
    }

    /**
     * 创建新的 Future 并注册到全局映射
     *
     * @param requestId 请求 ID
     * @param timeoutMs 超时时间（毫秒）
     * @return DefaultFuture 实例
     * @throws IllegalStateException 如果已关闭
     */
    public static DefaultFuture newFuture(long requestId, long timeoutMs) {
        if (CLOSED.get()) {
            throw new IllegalStateException("DefaultFuture has been shut down, cannot create new future");
        }
        DefaultFuture future = new DefaultFuture(requestId, timeoutMs);
        FUTURES.put(requestId, future);

        // 注册超时任务
        future.timeoutTask = TIMEOUT_TIMER.newTimeout(timeout -> {
            DefaultFuture f = FUTURES.remove(requestId);
            if (f != null && !f.isDone()) {
                long elapsed = System.currentTimeMillis() - f.createTime;
                f.completeExceptionally(new TimeoutException(
                        "Request timeout, requestId=" + requestId
                                + ", timeout=" + timeoutMs + "ms"
                                + ", elapsed=" + elapsed + "ms"
                ));
                log.warn("Request timeout: requestId={}, timeout={}ms, elapsed={}ms",
                        requestId, timeoutMs, elapsed);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return future;
    }

    /**
     * IO 线程收到响应时调用此方法
     * 根据 requestId 找到 Future 并 complete，唤醒阻塞的业务线程
     *
     * @param requestId 请求 ID
     * @param response  响应对象
     */
    public static void received(long requestId, Response response) {
        DefaultFuture future = FUTURES.remove(requestId);
        if (future != null) {
            // 取消超时任务
            if (future.timeoutTask != null) {
                future.timeoutTask.cancel();
            }
            // 唤醒业务线程
            future.complete(response);
            log.debug("Request completed: requestId={}, status={}, elapsed={}ms",
                    requestId, response.getStatus(),
                    System.currentTimeMillis() - future.createTime);
        } else {
            // future == null，说明已超时被移除，丢弃响应
            log.warn("Received response for unknown/expired requestId={}, discarding", requestId);
        }
    }

    /**
     * 连接异常时，使所有未完成的 Future 失败
     * 防止业务线程永远阻塞
     *
     * @param cause 异常原因
     */
    public static void failAll(Throwable cause) {
        for (Long requestId : FUTURES.keySet()) {
            DefaultFuture future = FUTURES.remove(requestId);
            if (future != null && !future.isDone()) {
                if (future.timeoutTask != null) {
                    future.timeoutTask.cancel();
                }
                future.completeExceptionally(cause);
            }
        }
        if (!FUTURES.isEmpty()) {
            log.warn("All pending futures failed due to: {}", cause.getMessage());
        }
    }

    /**
     * 优雅关闭：停止时间轮，使所有 pending Future 失败
     * <p>
     * 应在应用关闭时调用（如 shutdown hook），确保：
     * 1. 停止 HashedWheelTimer，释放其工作线程
     * 2. 所有 pending Future 收到关闭异常，不会永久阻塞
     * 3. 后续不再接受新的 Future 创建
     * </p>
     */
    public static void shutdown() {
        if (CLOSED.compareAndSet(false, true)) {
            log.info("Shutting down DefaultFuture, pending futures: {}", FUTURES.size());

            // 1. 停止时间轮（不再调度新的超时任务）
            TIMEOUT_TIMER.stop();

            // 2. 使所有 pending Future 失败
            IllegalStateException shutdownCause =
                    new IllegalStateException("DefaultFuture shut down, all pending requests cancelled");
            for (Long requestId : FUTURES.keySet()) {
                DefaultFuture future = FUTURES.remove(requestId);
                if (future != null && !future.isDone()) {
                    if (future.timeoutTask != null) {
                        future.timeoutTask.cancel();
                    }
                    future.completeExceptionally(shutdownCause);
                }
            }

            log.info("DefaultFuture shut down complete, timer stopped");
        }
    }

    /**
     * 是否已关闭
     */
    public static boolean isShutdown() {
        return CLOSED.get();
    }

    /**
     * 获取当前活跃的 Future 数量（监控用）
     */
    public static int getActiveFutureCount() {
        return FUTURES.size();
    }

    public long getRequestId() {
        return requestId;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getCreateTime() {
        return createTime;
    }
}
