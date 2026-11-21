package com.proxy.common.exchange;

import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.Client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交换层客户端 —— 包装底层 Client，提供请求-响应能力
 * <p>
 * 由 Exchanger.connect() 创建，对上层暴露 request() 和 send() 方法。
 * 内部持有底层 Client（负责网络传输）和 requestId/Future 映射逻辑。
 * </p>
 * <p>
 * 统一设计原则：
 * <ul>
 *   <li>控制面（CONNECT/DISCONNECT）：{@link #request} —— 生成 requestId，创建 DefaultFuture，等待响应</li>
 *   <li>数据面（DATA）：{@link #send} —— 不生成 requestId，发后即忘，回包由 ExchangeHandler 按 streamId 路由</li>
 * </ul>
 * </p>
 */
public interface ExchangeClient {
    default void setDatagramRegistry(ConcurrentHashMap<Long, DatagramReceiver> receivers) { }
    default void setStreamClosedListener(java.util.function.LongConsumer listener) { }

    /**
     * 发送请求并等待响应（控制面：CONNECT/DISCONNECT）
     * <p>
     * 内部流程：
     * 1. 生成唯一 requestId，设置到 message
     * 2. 创建 DefaultFuture 并注册到全局映射
     * 3. 通过底层 Client.send() 发送消息
     * 4. 返回 Future，业务线程可阻塞等待或异步回调
     * </p>
     *
     * @param message   要发送的消息
     * @param timeoutMs 超时时间（毫秒）
     * @return 异步响应结果
     */
    CompletableFuture<Response> request(ProxyMessage message, long timeoutMs);

    /**
     * 发送流式数据（数据面：DATA，发后即忘）
     * <p>
     * 不生成 requestId、不创建 Future，仅依赖 message 自带的 streamId 寻址。
     * 服务端的回包由 ExchangeHandler 按 streamId 路由写回浏览器，
     * 不经过本方法的返回值。
     * </p>
     *
     * @param message 要发送的流式消息（必须携带 streamId）
     */
    void send(ProxyMessage message);

    /**
     * 关闭（释放底层 Client 和所有资源）
     */
    void close();

    /**
     * 是否可用
     *
     * @return true 表示底层连接存活
     */
    boolean isAvailable();

    /**
     * 获取底层 Client（用于负载均衡等场景获取 activeStreamCount）
     */
    Client getClient();

    /**
     * 注入数据面 streamId 路由表
     * <p>
     * 由 proxy-local 在启动时调用，将 StreamChannelRegistry 的内部 map 传入，
     * 使得 ExchangeHandler 能按 streamId 将服务端推送数据路由写回浏览器。
     * </p>
     * <p>
     * 非必须——服务端（proxy-remote）不需要调用此方法。
     * </p>
     *
     * @param streamRegistry streamId 路由映射表（ConcurrentHashMap）
     */
    @SuppressWarnings("rawtypes")
    default void setStreamRegistry(ConcurrentHashMap streamRegistry) {
        // 默认空实现：服务端或不需要推送的场景可忽略
    }
}
