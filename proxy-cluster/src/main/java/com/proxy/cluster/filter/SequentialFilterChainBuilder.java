package com.proxy.cluster.filter;

import com.proxy.common.filter.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 顺序责任链构建器（默认）
 * <p>
 * 将 Filter 列表按顺序组装成责任链，每个 Filter 持有下一环的引用。
 * 执行时从链头开始，依次经过每个 Filter，最终到达真实的 Invoker。
 * </p>
 * <p>
 * 构建方式：倒序遍历 Filter 列表，逐层包装。
 * 假设 filters = [A, B, C]，最终链为：A → B → C → realInvoker
 * </p>
 */
public class SequentialFilterChainBuilder implements FilterChainBuilder {

    @Override
    public Invoker build(Invoker invoker, List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return invoker;
        }

        // 从后往前包装，最后一个 Filter 的 next 是 realInvoker
        Invoker last = invoker;
        for (int i = filters.size() - 1; i >= 0; i--) {
            final Filter filter = filters.get(i);
            final Invoker next = last;
            last = new FilterInvoker(filter, next);
        }
        return last;
    }

    /**
     * Filter 包装成 Invoker 的内部类
     * <p>
     * 每个 FilterInvoker 持有一个 Filter 和下一环 Invoker，
     * invoke() 时调用 filter.invoke(next, invocation)
     * </p>
     */
    private static class FilterInvoker implements Invoker {

        private final Filter filter;
        private final Invoker next;

        FilterInvoker(Filter filter, Invoker next) {
            this.filter = filter;
            this.next = next;
        }

        @Override
        public CompletableFuture<Response> invoke(Invocation invocation) throws ProxyException {
            return filter.invoke(next, invocation);
        }

        @Override
        public boolean isAvailable() {
            return next.isAvailable();
        }

        @Override
        public int getActiveCount() {
            return next.getActiveCount();
        }
    }
}
