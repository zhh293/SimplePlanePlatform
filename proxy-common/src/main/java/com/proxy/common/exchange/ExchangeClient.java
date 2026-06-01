package com.proxy.common.exchange;

import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.Client;

import java.util.concurrent.CompletableFuture;

/**
 * 交换层客户端 —— 包装底层 Client，提供请求-响应能力
 * <p>
 * 由 Exchanger.connect() 创建，对上层暴露 request() 方法。
 * 内部持有底层 Client（负责网络传输）和 requestId/Future 映射逻辑。
 * </p>
 * <p>
 * 上层使用方式：
 * <pre>
 * ExchangeClient client = exchanger.connect(url);
 * CompletableFuture&lt;Response&gt; future = client.request(message, 10000);
 * Response response = future.get(); // 阻塞等待响应
 * </pre>
 * </p>
 */
public interface ExchangeClient {

    /**
     * 发送请求并等待响应
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
}
