package com.proxy.exchange.header;

import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 挂在 Client Pipeline 上的消息处理器
 * <p>
 * 由 HeaderExchanger.connect() 创建，通过 transporter.connect(url, handler) 传入底层。
 * IO 线程收到远程返回的消息后，根据 requestId 找到对应的 DefaultFuture 并 complete，
 * 从而唤醒阻塞的业务线程。
 * </p>
 * <p>
 * 对于 requestId=0 的消息（服务端推送），委托给 {@link ServerPushHandler} 处理，
 * 通过 streamId 路由数据回浏览器。
 * </p>
 */
public class ExchangeHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ExchangeHandler.class);

    private final ServerPushHandler pushHandler;

    /**
     * 无推送处理器的构造（兼容旧用法，推送消息仅打日志）
     */
    public ExchangeHandler() {
        this(null);
    }

    /**
     * 带推送处理器的构造
     *
     * @param pushHandler 处理 requestId=0 的服务端推送消息，null 表示仅日志
     */
    public ExchangeHandler(ServerPushHandler pushHandler) {
        this.pushHandler = pushHandler;
    }

    @Override
    public void onMessage(ProxyMessage message) {
        if (message == null || message.getType() == null) {
            log.warn("Received null message or null type, ignoring");
            return;
        }

        long requestId = message.getRequestId();

        // 只有带 requestId 的消息才走 Future 映射
        if (requestId > 0) {
            Response response;
            if (message.getStatus() == Response.OK || message.getStatus() == 0) {
                response = Response.ok(message.getData());
            } else {
                response = Response.error(
                        message.getMessage() != null ? message.getMessage() : "Remote error");
            }
            DefaultFuture.received(requestId, response);
        } else {
            // requestId=0：服务端推送（目标网站返回的数据）
            if (pushHandler != null) {
                pushHandler.onServerPush(message);
            } else {
                log.debug("Received server push without handler: type={}, streamId={}",
                        message.getType(), message.getStreamId());
            }
        }
    }

    @Override
    public void onError(Throwable cause) {
        log.error("Connection error, failing all pending futures", cause);
        DefaultFuture.failAll(cause);
    }

    @Override
    public void onDisconnected() {
        log.warn("Connection disconnected, failing all pending futures");
        DefaultFuture.failAll(new RuntimeException("Connection disconnected"));
    }
}
