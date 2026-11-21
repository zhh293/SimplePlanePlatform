package com.proxy.common.exchange;

/** Receives complete application UDP datagrams; zero-length payloads are valid. */
public interface DatagramReceiver {
    void onDatagram(byte[] payload);
    void onClosed(Throwable cause);
}
