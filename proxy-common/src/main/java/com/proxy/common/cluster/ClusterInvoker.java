package com.proxy.common.cluster;

import com.proxy.common.filter.Invoker;
import com.proxy.common.spi.SPI;

import java.util.List;

/**
 * 集群容错 Invoker SPI 接口
 * <p>
 * 持有多个 Invoker（每个 = FilterChain + Client），
 * 通过 LoadBalance 选择一个执行，失败时按容错策略处理。
 * </p>
 * <p>
 * Server 层收到请求后直接调用 clusterInvoker.invoke()，
 * 不需要关心选哪个连接、怎么容错。
 * </p>
 */
@SPI("failover")
public interface ClusterInvoker extends Invoker {

    /**
     * 设置可用的 Invoker 列表
     *
     * @param invokers Invoker 列表
     */
    void setInvokers(List<Invoker> invokers);

    /**
     * 动态添加 Invoker（扩容时用）
     *
     * @param invoker 新的 Invoker
     */
    void addInvoker(Invoker invoker);

    /**
     * 移除 Invoker（缩容/连接断开时用）
     *
     * @param invoker 要移除的 Invoker
     */
    void removeInvoker(Invoker invoker);

    /**
     * 设置负载均衡策略
     *
     * @param loadBalance 负载均衡实例
     */
    void setLoadBalance(LoadBalance loadBalance);

    /**
     * 获取所有可用的 Invoker
     *
     * @return 可用 Invoker 列表
     */
    List<Invoker> getAvailableInvokers();
}
