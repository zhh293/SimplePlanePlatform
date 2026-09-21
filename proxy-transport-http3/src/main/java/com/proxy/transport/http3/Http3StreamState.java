package com.proxy.transport.http3;

import com.proxy.common.model.ProxyMessage;
import io.netty.handler.codec.quic.QuicStreamChannel;

import java.util.ArrayDeque;
import java.util.Deque;

final class Http3StreamState {
    enum Phase { OPENING, WAITING_RESPONSE, READY, FAILED, CLOSED }

    final long streamId;
    final Deque<ProxyMessage> pending = new ArrayDeque<>();
    volatile QuicStreamChannel channel;
    volatile Phase phase = Phase.OPENING;
    long pendingBytes;

    Http3StreamState(long streamId) {
        this.streamId = streamId;
    }
}
