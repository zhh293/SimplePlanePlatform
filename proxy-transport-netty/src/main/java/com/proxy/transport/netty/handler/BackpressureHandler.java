package com.proxy.transport.netty.handler;

import com.proxy.common.transport.FlowPermit;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用层背压处理器 —— 基于信用额度（credit）的流量控制
 * <p>
 * 每个 DATA 帧到达时消耗一个信用额度，并创建对应的 {@link FlowPermit}，
 * 将帧包装为 {@link PermittedDataFrame} 下发给 ProxyMessageDecoder。
 * 业务处理完成后，下游调用 {@code permit.release()} 即可自动通过回调
 * 触发信用归还 —— 外部不再直接调用本 Handler 的任何方法。
 * </p>
 * <p>
 * 额度耗尽时，帧暂存到无锁队列并调用 {@code setAutoRead(false)} 停止读取，
 * 从而阻止 Netty 发送 WINDOW_UPDATE，使对端发送窗口自然耗尽形成端到端背压。
 * </p>
 *
 * <pre>
 * Pipeline 位置：
 *   CipherDecodeHandler → [BackpressureHandler] → ProxyMessageDecoder → ...
 * </pre>
 */
public class BackpressureHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BackpressureHandler.class);

    private final AtomicInteger credits;
    private final int maxPermits;

    /**
     * 积压队列：额度耗尽时暂存原始 Http2DataFrame（已 retain）
     * IO 线程写入，drain 也在 IO 线程，业务线程通过 creditBack 触发 drain
     */
    private final Queue<Http2DataFrame> pendingQueue;

    private volatile ChannelHandlerContext ctx;
    private volatile boolean suspended = false;

    public BackpressureHandler(int maxPermits) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits must be positive, got: " + maxPermits);
        }
        this.maxPermits = maxPermits;
        this.credits = new AtomicInteger(maxPermits);
        this.pendingQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        log.info("BackpressureHandler added to pipeline, maxPermits={}", maxPermits);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http2DataFrame)) {
            super.channelRead(ctx, msg);
            return;
        }

        Http2DataFrame dataFrame = (Http2DataFrame) msg;
        int remaining = credits.decrementAndGet();

        if (remaining >= 0) {
            // 额度充足：创建令牌，绑定回调，包装后下发
            FlowPermit permit = new FlowPermit(this::creditBack);
            ctx.fireChannelRead(new PermittedDataFrame(dataFrame, permit));
        } else {
            // 额度耗尽：修正计数，retain 后入队
            credits.incrementAndGet();
            pendingQueue.offer(dataFrame.retain());
            if (!suspended) {
                suspended = true;
                Channel parent = ctx.channel().parent();
                if (parent != null) {
                    parent.config().setAutoRead(false);
                } else {
                    log.warn("BackpressureHandler: no parent channel, cannot setAutoRead(false)");
                }
                log.debug("Backpressure engaged: pending={}", pendingQueue.size());
            }
        }
    }

    /**
     * 信用归还回调 —— 由 {@link FlowPermit#release()} 触发，可从任意线程调用。
     * 内部确保 drain 在 EventLoop 线程执行。
     */
    private void creditBack() {
        int current = credits.incrementAndGet();
        if (suspended && current > 0) {
            ChannelHandlerContext c = this.ctx;
            if (c != null && c.channel().isActive()) {
                if (c.channel().eventLoop().inEventLoop()) {
                    drain();
                } else {
                    c.channel().eventLoop().execute(this::drain);
                }
            }
        }
    }

    /**
     * 排空积压队列 —— 必须在 EventLoop 线程执行
     */
    private void drain() {
        ChannelHandlerContext c = this.ctx;
        if (c == null || !c.channel().isActive()) {
            Http2DataFrame frame;
            while ((frame = pendingQueue.poll()) != null) {
                frame.release();
            }
            return;
        }

        Http2DataFrame frame;
        while (credits.get() > 0 && (frame = pendingQueue.poll()) != null) {
            credits.decrementAndGet();
            // 积压帧重放时同样包装成 PermittedDataFrame，信用归还路径一致
            FlowPermit permit = new FlowPermit(this::creditBack);
            c.fireChannelRead(new PermittedDataFrame(frame, permit));
            frame.release();  // 平衡入队时的 retain()，下游通过 PermittedDataFrame 持有内容引用
        }

        if (pendingQueue.isEmpty()) {
            if (suspended) {
                suspended = false;
                Channel parent = c.channel().parent();
                if (parent != null) {
                    parent.config().setAutoRead(true);
                    log.debug("Backpressure released: resuming, credits={}", credits.get());
                } else {
                    log.warn("BackpressureHandler: no parent channel, cannot setAutoRead(true)");
                }
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Http2DataFrame frame;
        while ((frame = pendingQueue.poll()) != null) {
            frame.release();
        }
        log.debug("BackpressureHandler: channel inactive, released all pending frames");
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        Http2DataFrame frame;
        while ((frame = pendingQueue.poll()) != null) {
            frame.release();
        }
    }

    // ====== Metrics ======

    public int getAvailableCredits() {
        return Math.max(0, credits.get());
    }

    public int getPendingCount() {
        return pendingQueue.size();
    }

    public boolean isSuspended() {
        return suspended;
    }

    public int getMaxPermits() {
        return maxPermits;
    }
}
