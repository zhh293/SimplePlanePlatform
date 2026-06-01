package com.proxy.cluster.loadbalance;

import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 一致性哈希负载均衡
 * <p>
 * 相同目标地址的请求总是路由到同一个 Invoker。
 * 适用于有状态的场景（如长连接复用）。
 * 使用虚拟节点保证哈希环的均匀分布。
 * </p>
 */
public class ConsistentHashLoadBalance implements LoadBalance {

    private static final int VIRTUAL_NODES = 160;

    /**
     * invoker 列表的 identityHashCode → 哈希环 的缓存
     * 当 invoker 列表变化时重建
     */
    private final ConcurrentHashMap<Integer, TreeMap<Long, Invoker>> hashRingCache =
            new ConcurrentHashMap<>();

    @Override
    public Invoker select(List<Invoker> invokers, Invocation invocation, Set<Invoker> excluded) {
        // 用目标 host:port 作为 hash key
        String key = invocation.getTargetHost() + ":" + invocation.getTargetPort();

        // 获取或构建哈希环
        int identityHashCode = System.identityHashCode(invokers);
        TreeMap<Long, Invoker> hashRing = hashRingCache.computeIfAbsent(
                identityHashCode, k -> buildHashRing(invokers));

        long hash = hash(key);

        // 顺时针找到第一个节点
        Map.Entry<Long, Invoker> entry = hashRing.ceilingEntry(hash);
        if (entry == null) {
            entry = hashRing.firstEntry();
        }

        Invoker selected = entry.getValue();

        // 如果被排除了，顺时针找下一个
        if (excluded.contains(selected)) {
            List<Invoker> candidates = invokers.stream()
                    .filter(i -> !excluded.contains(i))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                return selected; // 所有都被排除了，还是返回原来的
            }

            // 从当前位置顺时针找下一个不在 excluded 中的
            Long currentKey = entry.getKey();
            for (int i = 0; i < hashRing.size(); i++) {
                Long nextKey = hashRing.higherKey(currentKey);
                if (nextKey == null) {
                    nextKey = hashRing.firstKey();
                }
                Invoker next = hashRing.get(nextKey);
                if (!excluded.contains(next)) {
                    return next;
                }
                currentKey = nextKey;
            }
        }

        return selected;
    }

    private TreeMap<Long, Invoker> buildHashRing(List<Invoker> invokers) {
        TreeMap<Long, Invoker> ring = new TreeMap<>();
        for (int i = 0; i < invokers.size(); i++) {
            for (int j = 0; j < VIRTUAL_NODES; j++) {
                long h = hash("invoker-" + i + "-vnode-" + j);
                ring.put(h, invokers.get(i));
            }
        }
        return ring;
    }

    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // 取前 8 字节作为 long
            return ((long) (digest[0] & 0xFF))
                    | ((long) (digest[1] & 0xFF) << 8)
                    | ((long) (digest[2] & 0xFF) << 16)
                    | ((long) (digest[3] & 0xFF) << 24)
                    | ((long) (digest[4] & 0xFF) << 32)
                    | ((long) (digest[5] & 0xFF) << 40)
                    | ((long) (digest[6] & 0xFF) << 48)
                    | ((long) (digest[7] & 0xFF) << 56);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
