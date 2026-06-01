package com.proxy.cluster;

import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.ProxyException;
import com.proxy.common.filter.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 并行调用：同时调用多个 Invoker，取最快返回的结果
 * <p>
 * 适用于实时性要求高的场景，用冗余换速度。
 * 注意：会消耗更多资源（多个连接同时发送同一请求）
 * </p>
 */
public class ForkingClusterInvoker implements ClusterInvoker {

    private static final Logger log = LoggerFactory.getLogger(ForkingClusterInvoker.class);

    private volatile List<Invoker> invokers = new CopyOnWriteArrayList<>();
    private LoadBalance loadBalance;
    private int forks = 2; // 并行调用数

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        List<Invoker> available = getAvailableInvokers();
        if (available.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Response.error("No available invoker"));
        }

        // 选择 forks 个 Invoker 并行调用
        int parallelCount = Math.min(forks, available.size());
        List<Invoker> selected = loadBalance.selectMultiple(available, invocation, parallelCount);

        // 并行调用，取最快的结果
        CompletableFuture<Response>[] futures = selected.stream()
                .map(invoker -> {
                    try {
                        return invoker.invoke(invocation);
                    } catch (ProxyException e) {
                        return CompletableFuture.<Response>completedFuture(
                                Response.error(e.getMessage()));
                    }
                })
                .toArray(CompletableFuture[]::new);

        CompletableFuture<Object> anyOf = CompletableFuture.anyOf(futures);
        return anyOf.thenApply(result -> (Response) result);
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

    public void setForks(int forks) {
        this.forks = forks;
    }
}
