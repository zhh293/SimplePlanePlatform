package com.proxy.cluster.loadbalance;

import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 随机负载均衡
 * <p>
 * 从可用 Invoker 中随机选择一个。
 * 在大量请求下，随机选择的效果趋近于轮询。
 * </p>
 */
public class RandomLoadBalance implements LoadBalance {

    @Override
    public Invoker select(List<Invoker> invokers, Invocation invocation, Set<Invoker> excluded) {
        List<Invoker> candidates = invokers.stream()
                .filter(i -> !excluded.contains(i))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = invokers;
        }

        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(index);
    }
}
