package com.proxy.transport.netty;

import com.proxy.common.spi.SPI;
import io.netty.channel.ChannelPipeline;

/**
 * Stream Pipeline 自定义扩展点（SPI）
 * <p>
 * 由 {@link com.proxy.transport.netty.conn.Http2Connection} 在创建 HTTP/2 Stream
 * 子 Channel 时调用。允许上层模块在 Stream Pipeline 中注入自定义的 ChannelHandler，
 * 实现消息拦截、路由等需求，而不侵入 Exchange 层的核心逻辑。
 * </p>
 * <p>
 * 典型场景：proxy-local 注入 ServerPushDispatchHandler，
 * 在 ClientMessageHandler 之前拦截 requestId=0 的推送消息，
 * 通过 streamId 路由数据到浏览器。
 * </p>
 * <p>
 * 实现类通过 SPI 注册在：
 * {@code META-INF/proxy/com.proxy.transport.netty.StreamPipelineCustomizer}
 * </p>
 */
@SPI
public interface StreamPipelineCustomizer {

    /**
     * 自定义 Stream Pipeline
     * <p>
     * 在 Transport 层完成基础 Pipeline 组装
     * （cipher → codec → idle → heartbeat → clientMessageHandler）之后调用。
     * 实现方可以在任意位置插入自定义 Handler，例如在 "handler" 之前插入拦截器。
     * </p>
     *
     * @param pipeline 已组装完基础 Handler 的 ChannelPipeline
     */
    void customize(ChannelPipeline pipeline);
}
