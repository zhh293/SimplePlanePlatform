package com.proxy.local.handler;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.model.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 普通代理中继处理器。
 * <p>
 * 与 CONNECT 隧道的 {@link RelayHandler} 的唯一区别：
 * 加入 pipeline 时立即发送首条 HTTP 请求数据（已由 {@link HttpRequestParser} 完成 URL 重写）。
 * 后续的 channelRead、channelInactive、exceptionCaught 逻辑与 RelayHandler 完全一致。
 * </p>
 *
 * <h3>为什么不复用 RelayHandler</h3>
 * <p>
 * RelayHandler 加入 pipeline 时不发送任何数据，它假定隧道已建好、浏览器会主动发数据。
 * 但 HTTP 普通代理模式下，触发建连的那条 HTTP 请求已经被 HttpConnectHandler 读取并消费，
 * 如果不在此处主动发送，这条请求就会丢失，目标服务器不会返回任何响应。
 * 单独新建一个 Handler 比给 RelayHandler 加可选参数更清晰，职责更明确。
 * </p>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li><b>构造</b>：接收 invoker、目标地址、streamId 和重写后的首条请求字节</li>
 *   <li><b>handlerAdded</b>：通过 invoker 将 initialRequest 作为第一个 DATA 帧发送到远程，
 *       发送完毕后将 initialRequest 置 null（释放引用，帮助 GC）</li>
 *   <li><b>channelRead</b>：后续浏览器发来的数据直接作为 DATA 帧透传
 *       （与 RelayHandler 逻辑一致）</li>
 *   <li><b>channelInactive</b>：发送 DISCONNECT 通知，注销 streamId
 *       （与 RelayHandler 逻辑一致）</li>
 *   <li><b>exceptionCaught</b>：日志记录 + 关闭连接</li>
 * </ol>
 *
 * @see RelayHandler
 * @see HttpRequestParser
 */
public class HttpProxyRelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyRelayHandler.class);

    private final Invoker invoker;
    private final String targetHost;
    private final int targetPort;
    private final long streamId;
    private final StreamChannelRegistry streamRegistry = StreamChannelRegistry.getInstance();

    /** 重写后的首条 HTTP 请求，发送后置 null 以帮助 GC 回收 */
    private byte[] initialRequest;

    /**
     * 构造 HTTP 普通代理中继处理器。
     *
     * @param invoker        用于发送 DATA/DISCONNECT 帧的 Invoker
     * @param targetHost     目标主机名
     * @param targetPort     目标端口
     * @param streamId       本次连接的 stream ID
     * @param initialRequest 经 {@link HttpRequestParser} 重写后的首条 HTTP 请求字节，
     *                       建连成功后必须首先发送此数据，否则目标服务器不会返回响应
     */
    public HttpProxyRelayHandler(Invoker invoker, String targetHost, int targetPort,
                                  long streamId, byte[] initialRequest) {
        this.invoker = invoker;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.streamId = streamId;
        this.initialRequest = initialRequest;
    }

    /**
     * Handler 被添加到 pipeline 时立即发送首条请求。
     * <p>
     * 选择 handlerAdded 而不是 channelActive 的原因：
     * channelActive 在 channel 变为 active 时触发，但此时本 handler 可能还没被加入 pipeline。
     * 而 handlerAdded 在 handler 被添加到 pipeline 的那一刻立即触发，且此时 channel 已经是
     * active 的（因为浏览器的 TCP 连接早已建立）。
     * </p>
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (initialRequest != null && initialRequest.length > 0) {
            log.debug("Sending initial HTTP request for {}:{}, streamId={}, {} bytes",
                    targetHost, targetPort, streamId, initialRequest.length);
            sendData(initialRequest);
            initialRequest = null; // 释放引用，帮助 GC
        }
    }

    /**
     * 透传后续浏览器发来的数据。
     * <p>
     * 逻辑与 {@link RelayHandler#channelRead(ChannelHandlerContext, Object)} 完全一致：
     * 提取 ByteBuf 中的字节，构建 DATA Invocation 通过 invoker 发送。
     * </p>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() == 0) {
                return;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            sendData(data);
        } finally {
            buf.release();
        }
    }

    /**
     * 浏览器断开连接时，注销 streamId 并通知远端释放资源。
     * <p>
     * 逻辑与 {@link RelayHandler#channelInactive(ChannelHandlerContext)} 完全一致。
     * </p>
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Client disconnected, sending DISCONNECT for {}:{}, streamId={}",
                targetHost, targetPort, streamId);
        streamRegistry.unregister(streamId);

        Invocation invocation = new Invocation(targetHost, targetPort, null, ProxyMessage.MessageType.DISCONNECT);
        invocation.setAttachment("streamId", streamId);
        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.debug("DISCONNECT notification failed for {}:{}, streamId={}",
                        targetHost, targetPort, streamId);
            }
        });

        super.channelInactive(ctx);
    }

    /**
     * 通过 invoker 发送 DATA 帧（发后即忘）。
     *
     * @param data 要发送的数据字节
     */
    private void sendData(byte[] data) {
        Invocation invocation = new Invocation(targetHost, targetPort, data, ProxyMessage.MessageType.DATA);
        invocation.setAttachment("streamId", streamId);
        invoker.invoke(invocation).whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.error("HTTP proxy relay DATA failed for {}:{}, streamId={}",
                        targetHost, targetPort, streamId, throwable);
            }
        });
    }

    /**
     * 异常处理：记录日志并关闭连接。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP proxy relay error for {}:{}, streamId={}",
                targetHost, targetPort, streamId, cause);
        ctx.close();
    }
}
