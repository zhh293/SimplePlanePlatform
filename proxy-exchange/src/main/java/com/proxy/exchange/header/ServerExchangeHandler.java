package com.proxy.exchange.header;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.InvokerAware;
import com.proxy.common.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端交换层消息处理器
 * <p>
 * 实现 {@link MessageHandler} 和 {@link InvokerAware} 接口。
 * 持有已封装 Filter 链的 {@link Invoker}，供 NettyTransporter 提取后注入 ServerChannelHandler。
 * </p>
 * <p>
 * 采用"内聚一层"方案：实际的 invoke + 回写逻辑在 ServerChannelHandler 中完成（需要 ctx），
 * 此类主要作为 Invoker 的载体和 {@link #toInvocation(ProxyMessage)} 工具方法的提供者。
 * 为后续 Map 路由表方案升级做架构预留。
 * </p>
 */
public class ServerExchangeHandler implements MessageHandler, InvokerAware {

    private static final Logger log = LoggerFactory.getLogger(ServerExchangeHandler.class);

    private final Invoker invoker;

    public ServerExchangeHandler(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public Invoker getInvoker() {
        return invoker;
    }

    @Override
    public void onMessage(ProxyMessage message) {
        // "内聚一层"方案下此方法不会被调用（ServerChannelHandler 直接处理）
        // 保留为架构预留
        log.debug("ServerExchangeHandler.onMessage() called (not expected in current architecture): type={}",
                message.getType());
    }

    @Override
    public void onError(Throwable cause) {
        log.error("ServerExchangeHandler received error", cause);
    }

    @Override
    public void onDisconnected() {
        log.debug("ServerExchangeHandler: client disconnected");
    }

    /**
     * 将 ProxyMessage 转换为 Invocation（工具方法，供 ServerChannelHandler 复用）
     *
     * @param message 原始代理消息
     * @return 调用上下文
     */
    public static Invocation toInvocation(ProxyMessage message) {
        Invocation invocation = new Invocation(
                message.getHost(),
                message.getPort(),
                message.getData(),
                message.getType()
        );
        invocation.setAttachment("requestId", message.getRequestId());
        invocation.setAttachment("streamId", String.valueOf(message.getStreamId()));
        return invocation;
    }
}
