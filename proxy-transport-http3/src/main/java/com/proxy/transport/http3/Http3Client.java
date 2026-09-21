package com.proxy.transport.http3;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.transport.http3.handler.Http3CipherDecodeHandler;
import com.proxy.transport.http3.handler.Http3CipherEncodeHandler;
import com.proxy.transport.http3.handler.Http3ProxyMessageDecoder;
import com.proxy.transport.http3.handler.Http3ProxyMessageEncoder;
import com.proxy.transport.http3.handler.Http3StreamHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Client implementation mapping each logical streamId to one HTTP/3 request stream. */
public final class Http3Client implements Client {
    private static final Logger log = LoggerFactory.getLogger(Http3Client.class);

    private final URL url;
    private final MessageHandler messageHandler;
    private final Http3Connection connection;
    private final ConcurrentHashMap<Long, Http3StreamState> streams = new ConcurrentHashMap<>();
    private final AtomicLong connectionPendingBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "http3-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final Cipher cipher;

    public Http3Client(URL url, MessageHandler handler) {
        if (!(handler instanceof ChannelHandler)) {
            throw new IllegalArgumentException("MessageHandler must also implement ChannelHandler for HTTP/3 client pipeline: "
                    + handler.getClass().getName());
        }
        this.url = url;
        this.messageHandler = handler;
        this.cipher = createCipher(url);
        this.connection = new Http3Connection(url);
        try {
            connection.connect();
            attachConnectionListener();
        } catch (Exception e) {
            connection.close();
            throw new IllegalStateException("Failed to establish HTTP/3 connection to " + url.getAddress(), e);
        }
    }

    @Override
    public void send(ProxyMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("HTTP/3 connection is not available");
        }
        long streamId = message.getStreamId();
        Http3StreamState state = streams.get(streamId);
        if (state == null) {
            Http3StreamState candidate = new Http3StreamState(streamId);
            Http3StreamState existing = streams.putIfAbsent(streamId, candidate);
            state = existing != null ? existing : openStream(candidate);
        }
        synchronized (state) {
            if (state.phase == Http3StreamState.Phase.FAILED || state.phase == Http3StreamState.Phase.CLOSED) {
                throw new IllegalStateException("HTTP/3 stream is closed: " + streamId);
            }
            QuicStreamChannel channel = state.channel;
            if (state.phase == Http3StreamState.Phase.READY && channel != null
                    && channel.isActive() && channel.isWritable() && state.pending.isEmpty()) {
                write(channel, message);
                return;
            }
            long bytes = estimatedBytes(message);
            long streamLimit = url.getParameter("http3.streamPendingHardLimit", 4194304L);
            long connectionLimit = url.getParameter("http3.connectionPendingHardLimit", 67108864L);
            if (state.pendingBytes + bytes > streamLimit
                    || connectionPendingBytes.get() + bytes > connectionLimit) {
                failStream(state, new IllegalStateException("HTTP/3 pending queue limit exceeded for stream " + streamId));
                throw new IllegalStateException("HTTP/3 pending queue limit exceeded for stream " + streamId);
            }
            state.pending.addLast(message);
            state.pendingBytes += bytes;
            connectionPendingBytes.addAndGet(bytes);
        }
    }

    private Http3StreamState openStream(Http3StreamState state) {
        ChannelInitializer<QuicStreamChannel> initializer = new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                state.channel = channel;
                state.phase = Http3StreamState.Phase.WAITING_RESPONSE;
                channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(
                                url.getParameter("http3.writeBufferLowWaterMark", 262144),
                                url.getParameter("http3.writeBufferHighWaterMark", 1048576)));
                channel.pipeline().addLast("h3-stream", new Http3StreamHandler(false, url,
                        () -> markReady(state), () -> closeState(state), () -> flushPending(state)));
                channel.pipeline().addLast("h3-cipher-decode", new Http3CipherDecodeHandler(cipher,
                        url.getParameter("http3.maxProxyMessageBytes", 8388608) + 65536));
                channel.pipeline().addLast("h3-decoder", new Http3ProxyMessageDecoder(
                        ExtensionLoader.getLoader(com.proxy.common.codec.Codec.class).getDefaultExtension(),
                        url.getParameter("http3.maxProxyMessageBytes", 8388608)));
                channel.pipeline().addLast("h3-cipher-encode", new Http3CipherEncodeHandler(cipher,
                        url.getParameter("http3.maxProxyMessageBytes", 8388608) + 65536));
                channel.pipeline().addLast("h3-encoder", new Http3ProxyMessageEncoder(
                        ExtensionLoader.getLoader(com.proxy.common.codec.Codec.class).getDefaultExtension(),
                        url.getParameter("http3.maxProxyMessageBytes", 8388608)));
                channel.pipeline().addLast("exchange-handler", (ChannelHandler) messageHandler);
            }
        };
        Future<QuicStreamChannel> future = connection.openStream(initializer);
        future.addListener(f -> {
            if (!f.isSuccess()) {
                failStream(state, f.cause());
            }
        });
        return state;
    }

    private void markReady(Http3StreamState state) {
        synchronized (state) {
            if (state.phase == Http3StreamState.Phase.FAILED || state.phase == Http3StreamState.Phase.CLOSED) {
                return;
            }
            state.phase = Http3StreamState.Phase.READY;
        }
        flushPending(state);
    }

    private void flushPending(Http3StreamState state) {
        QuicStreamChannel channel;
        List<ProxyMessage> messages = new ArrayList<>();
        synchronized (state) {
            channel = state.channel;
            if (state.phase != Http3StreamState.Phase.READY || channel == null || !channel.isActive()
                    || !channel.isWritable()) {
                return;
            }
            while (!state.pending.isEmpty()) {
                ProxyMessage message = state.pending.removeFirst();
                state.pendingBytes -= estimatedBytes(message);
                connectionPendingBytes.addAndGet(-estimatedBytes(message));
                messages.add(message);
            }
        }
        for (ProxyMessage message : messages) {
            write(channel, message);
        }
    }

    private void write(QuicStreamChannel channel, ProxyMessage message) {
        channel.writeAndFlush(message).addListener(f -> {
            if (!f.isSuccess()) {
                Http3StreamState state = streams.get(message.getStreamId());
                if (state != null) {
                    failStream(state, f.cause());
                }
            }
        });
    }

    private void closeState(Http3StreamState state) {
        synchronized (state) {
            if (state.phase != Http3StreamState.Phase.FAILED) {
                state.phase = Http3StreamState.Phase.CLOSED;
            }
            releasePending(state);
        }
        streams.remove(state.streamId, state);
    }

    private void failStream(Http3StreamState state, Throwable cause) {
        synchronized (state) {
            state.phase = Http3StreamState.Phase.FAILED;
            releasePending(state);
        }
        streams.remove(state.streamId, state);
        QuicStreamChannel channel = state.channel;
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        log.warn("HTTP/3 stream {} failed locally: {}", state.streamId,
                cause != null ? cause.getMessage() : "unknown");
    }

    private void releasePending(Http3StreamState state) {
        for (ProxyMessage ignored : state.pending) {
            connectionPendingBytes.addAndGet(-estimatedBytes(ignored));
        }
        state.pending.clear();
        state.pendingBytes = 0;
    }

    private void attachConnectionListener() {
        connection.channel().closeFuture().addListener(f -> {
            if (closed.get()) {
                return;
            }
            List<Http3StreamState> snapshot = new ArrayList<>(streams.values());
            for (Http3StreamState state : snapshot) {
                failStream(state, f.cause());
            }
            messageHandler.onDisconnected();
            scheduleReconnect();
        });
    }

    private void scheduleReconnect() {
        reconnectScheduler.schedule(() -> {
            if (closed.get() || connection.isActive()) {
                return;
            }
            try {
                connection.connect();
                attachConnectionListener();
                log.info("HTTP/3 QUIC reconnect succeeded to {}", url.getAddress());
            } catch (Exception e) {
                log.warn("HTTP/3 QUIC reconnect failed: {}", e.getMessage());
                scheduleReconnect();
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static long estimatedBytes(ProxyMessage message) {
        return 32L + (message.getHost() == null ? 0 : message.getHost().length() * 3L)
                + (message.getData() == null ? 0 : message.getData().length);
    }

    private static Cipher createCipher(URL url) {
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).getExtension(url.getParameter("cipher", "none"));
        String key = url.getParameter("cipherKey", "");
        cipher.init(key.isEmpty() ? new CipherConfig() : new CipherConfig(key.getBytes()));
        return cipher;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            reconnectScheduler.shutdownNow();
            for (Http3StreamState state : new ArrayList<>(streams.values())) {
                failStream(state, new IllegalStateException("HTTP/3 client closed"));
            }
            streams.clear();
            connection.close();
        }
    }

    @Override
    public void closeStream(long streamId) {
        Http3StreamState state = streams.remove(streamId);
        if (state != null) {
            failStream(state, new IllegalStateException("HTTP/3 stream closed by caller"));
        }
    }

    @Override
    public boolean isAvailable() {
        return !closed.get() && connection.isActive();
    }

    @Override
    public int getActiveStreamCount() {
        int count = 0;
        for (Http3StreamState state : streams.values()) {
            QuicStreamChannel channel = state.channel;
            if (state.phase == Http3StreamState.Phase.READY && channel != null && channel.isActive()) {
                count++;
            }
        }
        return count;
    }
}
