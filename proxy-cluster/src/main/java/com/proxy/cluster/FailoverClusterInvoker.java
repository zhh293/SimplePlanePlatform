package com.proxy.cluster;

import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 失败自动切换：调用失败后，自动切换到下一个可用 Invoker 重试
 * <p>
 * 适用于读操作或幂等写操作。
 * 重试次数 = retries 配置值（默认 2 次，加上第一次共 3 次机会）
 * </p>
 */
public class FailoverClusterInvoker implements ClusterInvoker {

    private static final Logger log = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    private volatile List<Invoker> invokers = new CopyOnWriteArrayList<>();
    private LoadBalance loadBalance;
    private int retries = 2; // 额外重试次数

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        List<Invoker> available = getAvailableInvokers();
        if (available.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Response.error("No available invoker"));
        }

        Set<Invoker> tried = new HashSet<>();
        int maxAttempts = retries + 1;

        return doInvoke(invocation, available, tried, 0, maxAttempts);
    }

    private CompletableFuture<Response> doInvoke(
            Invocation invocation, List<Invoker> available,
            Set<Invoker> tried, int attempt, int maxAttempts) {

        // 负载均衡选择一个 Invoker（排除已尝试过的）
        Invoker selected = loadBalance.select(available, invocation, tried);
        tried.add(selected);

        return selected.invoke(invocation)
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        // 异常情况
                        if (attempt + 1 >= maxAttempts) {
                            log.error("All invokers failed after {} attempts: {}",
                                    maxAttempts, throwable.getMessage());
                            return CompletableFuture.completedFuture(
                                    Response.error("All invokers failed after " + maxAttempts
                                            + " attempts: " + throwable.getMessage()));
                        }
                        log.warn("Invoker failed, switching to next. attempt={}/{}, error={}",
                                attempt + 1, maxAttempts, throwable.getMessage());
                        return doInvoke(invocation, available, tried, attempt + 1, maxAttempts);
                    }
                    // 正常响应
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(future -> future);
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

    public void setRetries(int retries) {
        this.retries = retries;
    }
}
