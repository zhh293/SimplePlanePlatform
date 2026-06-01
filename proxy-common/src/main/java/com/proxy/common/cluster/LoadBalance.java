package com.proxy.common.cluster;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.spi.SPI;

import java.util.*;

/**
 * 负载均衡 SPI 接口
 * <p>
 * 从多个 Invoker 中选择一个来执行。
 * 默认实现为加权轮询（RoundRobin）。
 * </p>
 */
@SPI("roundrobin")
public interface LoadBalance {

    /**
     * 选择一个 Invoker
     *
     * @param invokers   可用的 Invoker 列表
     * @param invocation 当前请求
     * @param excluded   需要排除的 Invoker（已经尝试失败的）
     * @return 选中的 Invoker
     */
    Invoker select(List<Invoker> invokers, Invocation invocation, Set<Invoker> excluded);

    /**
     * 选择多个 Invoker（Forking 模式用）
     *
     * @param invokers   可用的 Invoker 列表
     * @param invocation 当前请求
     * @param count      需要选择的数量
     * @return 选中的 Invoker 列表
     */
    default List<Invoker> selectMultiple(List<Invoker> invokers, Invocation invocation, int count) {
        List<Invoker> result = new ArrayList<>(count);
        Set<Invoker> selected = new HashSet<>();
        for (int i = 0; i < count && i < invokers.size(); i++) {
            Invoker invoker = select(invokers, invocation, selected);
            result.add(invoker);
            selected.add(invoker);
        }
        return result;
    }
}
