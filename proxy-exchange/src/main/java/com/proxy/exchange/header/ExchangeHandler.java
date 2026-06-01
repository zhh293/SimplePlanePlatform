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
 */
public class ExchangeHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ExchangeHandler.class);

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
            log.debug("Received message without requestId: type={}", message.getType());
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
