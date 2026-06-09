package com.proxy.transport.netty.handler;

import com.proxy.common.transport.FlowPermit;
import io.netty.handler.codec.http2.Http2DataFrame;

/**
 * Http2DataFrame 的轻量包装 —— 携带对应的 {@link FlowPermit}
 * <p>
 * 由 BackpressureHandler 创建，传递给 ProxyMessageDecoder。
 * 两者之间的内部协议，不对外暴露。
 * </p>
 */
final class PermittedDataFrame {

    private final Http2DataFrame frame;
    private final FlowPermit permit;

    PermittedDataFrame(Http2DataFrame frame, FlowPermit permit) {
        this.frame = frame;
        this.permit = permit;
    }

    Http2DataFrame frame() {
        return frame;
    }

    FlowPermit permit() {
        return permit;
    }
}
