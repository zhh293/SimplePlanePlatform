package com.proxy.cluster.loadbalance;

import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 加权轮询负载均衡（默认）
 * <p>
 * 按顺序轮流选择 Invoker，过滤掉已排除的节点。
 * 简单高效，适用于节点性能相近的场景。
 * </p>
 */
public class RoundRobinLoadBalance implements LoadBalance {

    private final AtomicInteger sequence = new AtomicInteger(0);

    @Override
    public Invoker select(List<Invoker> invokers, Invocation invocation, Set<Invoker> excluded) {
        List<Invoker> candidates = invokers.stream()
                .filter(i -> !excluded.contains(i))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            // 所有都被排除了，从原列表选
            candidates = invokers;
        }

        int index = Math.abs(sequence.getAndIncrement() % candidates.size());
        return candidates.get(index);
    }
}
