package com.proxy.cluster.loadbalance;

import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 最少活跃数负载均衡
 * <p>
 * 选择当前正在处理请求最少的 Invoker，
 * 让处理能力强的节点承担更多请求。
 * </p>
 */
public class LeastActiveLoadBalance implements LoadBalance {

    @Override
    public Invoker select(List<Invoker> invokers, Invocation invocation, Set<Invoker> excluded) {
        List<Invoker> candidates = invokers.stream()
                .filter(i -> !excluded.contains(i))
                .filter(Invoker::isAvailable)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = invokers.stream()
                    .filter(Invoker::isAvailable)
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            return invokers.get(0);
        }

        // 选活跃数最小的
        return candidates.stream()
                .min(Comparator.comparingInt(Invoker::getActiveCount))
                .orElse(candidates.get(0));
    }
}
