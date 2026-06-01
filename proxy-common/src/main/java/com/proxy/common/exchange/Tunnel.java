package com.proxy.common.exchange;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.Client;

/**
 * 隧道 —— 代表一条已建立的代理通道
 * <p>
 * 通过 Exchanger.connect() 建立后，可以在此隧道上双向传输数据。
 * 每个 Tunnel 绑定一个 Client 和一个 streamId。
 * </p>
 */
public class Tunnel {

    private final long streamId;
    private final Client client;
    private volatile boolean active = true;

    public Tunnel(long streamId, Client client) {
        this.streamId = streamId;
        this.client = client;
    }

    /**
     * 通过隧道发送数据
     *
     * @param data 要发送的数据
     */
    public void send(byte[] data) {
        if (!active) {
            throw new IllegalStateException("Tunnel is closed, streamId=" + streamId);
        }
        ProxyMessage message = ProxyMessage.builder()
                .streamId(streamId)
                .type(ProxyMessage.MessageType.DATA)
                .data(data)
                .build();
        client.send(message);
    }

    /**
     * 关闭隧道
     */
    public void close() {
        if (active) {
            active = false;
            ProxyMessage message = ProxyMessage.builder()
                    .streamId(streamId)
                    .type(ProxyMessage.MessageType.DISCONNECT)
                    .build();
            client.send(message);
        }
    }

    public long getStreamId() {
        return streamId;
    }

    public boolean isActive() {
        return active;
    }
}
