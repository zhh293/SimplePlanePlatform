package com.proxy.cluster;

import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 失败自动恢复：调用失败后返回空结果，后台定时重试
 * <p>
 * 适用于消息通知等不要求实时性的场景。
 * 失败的请求会被放入重试队列，每 5 秒重试一次。
 * </p>
 */
public class FailbackClusterInvoker implements ClusterInvoker {

    private static final Logger log = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    private volatile List<Invoker> invokers = new CopyOnWriteArrayList<>();
    private LoadBalance loadBalance;
    private final ConcurrentLinkedQueue<Invocation> failedQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService retryExecutor;

    public FailbackClusterInvoker() {
        // 每 5 秒重试一次失败的请求
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "failback-retry");
            t.setDaemon(true);
            return t;
        });
        retryExecutor.scheduleWithFixedDelay(this::retryFailed, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        List<Invoker> available = getAvailableInvokers();
        if (available.isEmpty()) {
            failedQueue.offer(invocation);
            return CompletableFuture.completedFuture(Response.empty());
        }

        Invoker selected = loadBalance.select(available, invocation, Collections.emptySet());
        return selected.invoke(invocation)
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        // 失败放入重试队列，返回空结果
                        failedQueue.offer(invocation);
                        log.warn("Invoke failed, will retry later: {}", throwable.getMessage());
                        return Response.empty();
                    }
                    return response;
                });
    }

    private void retryFailed() {
        if (failedQueue.isEmpty()) {
            return;
        }

        int retryCount = 0;
        Invocation invocation;
        while ((invocation = failedQueue.poll()) != null) {
            try {
                List<Invoker> available = getAvailableInvokers();
                if (!available.isEmpty()) {
                    Invoker selected = loadBalance.select(available, invocation, Collections.emptySet());
                    selected.invoke(invocation);
                    retryCount++;
                } else {
                    // 还是没有可用的，放回去
                    failedQueue.offer(invocation);
                    break;
                }
            } catch (Exception e) {
                failedQueue.offer(invocation);
                log.warn("Retry failed: {}", e.getMessage());
            }
        }

        if (retryCount > 0) {
            log.info("Failback retry completed, retried {} invocations", retryCount);
        }
    }

    @Override
    public List<Invoker> getAvailableInvokers() {
        return invokers.stream()
                .filter(Invoker::isAvailable)
                .collect(Collectors.toList());
    }

    @Override
    public void setInvokers(List<Invoker> invokers) {
        this.invokers = new CopyOnWriteArrayList<>(invokers);
    }

    @Override
    public void addInvoker(Invoker invoker) {
        this.invokers.add(invoker);
    }

    @Override
    public void removeInvoker(Invoker invoker) {
        this.invokers.remove(invoker);
    }

    @Override
    public void setLoadBalance(LoadBalance loadBalance) {
        this.loadBalance = loadBalance;
    }

    @Override
    public boolean isAvailable() {
        return !getAvailableInvokers().isEmpty();
    }

    @Override
    public int getActiveCount() {
        return invokers.stream()
                .mapToInt(Invoker::getActiveCount)
                .sum();
    }

    /**
     * 关闭重试线程池
     */
    public void destroy() {
        retryExecutor.shutdown();
    }
}
