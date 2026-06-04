package com.proxy.local.handler;

import com.proxy.common.model.ProxyMessage;
import com.proxy.exchange.header.ServerPushHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地端服务端推送处理器 —— 将远程推送的数据路由回浏览器
 * <p>
 * 当远程服务端将目标网站返回的数据通过 ProxyMessage(type=DATA, requestId=0, streamId=X) 推回时，
 * 本类通过 StreamChannelRegistry 查找 streamId 对应的浏览器 ChannelHandlerContext，
 * 将数据写回浏览器。
 * </p>
 */
public class LocalServerPushHandler implements ServerPushHandler {

    private static final Logger log = LoggerFactory.getLogger(LocalServerPushHandler.class);

    private final StreamChannelRegistry registry = StreamChannelRegistry.getInstance();

    @Override
    public void onServerPush(ProxyMessage message) {
        if (message.getType() != ProxyMessage.MessageType.DATA) {
            log.debug("Ignoring non-DATA server push: type={}, streamId={}",
                    message.getType(), message.getStreamId());
            return;
        }

        long streamId = message.getStreamId();
        byte[] data = message.getData();

        if (data == null || data.length == 0) {
            log.debug("Ignoring empty DATA push: streamId={}", streamId);
            return;
        }

        ChannelHandlerContext browserCtx = registry.get(streamId);
        if (browserCtx == null) {
            log.warn("No browser channel found for streamId={}, data discarded ({} bytes)",
                    streamId, data.length);
            return;
        }

        if (!browserCtx.channel().isActive()) {
            log.debug("Browser channel inactive for streamId={}, unregistering", streamId);
            registry.unregister(streamId);
            return;
        }

        // 将数据写回浏览器
        ByteBuf buf = browserCtx.alloc().buffer(data.length);
        buf.writeBytes(data);
        browserCtx.writeAndFlush(buf);

        log.debug("Routed {} bytes to browser: streamId={}", data.length, streamId);
    }
}
