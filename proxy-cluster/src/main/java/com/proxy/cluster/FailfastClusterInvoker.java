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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 快速失败：调用失败立即报错，不重试
 * <p>
 * 适用于非幂等写操作（如支付、下单），
 * 失败后由上层业务决定如何处理。
 * </p>
 */
public class FailfastClusterInvoker implements ClusterInvoker {

    private static final Logger log = LoggerFactory.getLogger(FailfastClusterInvoker.class);

    private volatile List<Invoker> invokers = new CopyOnWriteArrayList<>();
    private LoadBalance loadBalance;

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
        List<Invoker> available = getAvailableInvokers();
        if (available.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Response.error("No available invoker"));
        }

        // 只调用一次，失败直接返回错误
        Invoker selected = loadBalance.select(available, invocation, Collections.emptySet());
        return selected.invoke(invocation);
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
}
