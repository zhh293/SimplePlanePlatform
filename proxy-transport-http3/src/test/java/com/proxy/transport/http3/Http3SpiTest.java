package com.proxy.transport.http3;

import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.Transporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http3SpiTest {
    @Test
    void registersHttp2AndHttp3TransportNames() {
        ExtensionLoader.resetAll();
        ExtensionLoader<Transporter> loader = ExtensionLoader.getLoader(Transporter.class);
        assertTrue(loader.hasExtension("http2"));
        assertTrue(loader.hasExtension("http3"));
        assertInstanceOf(Http3Transporter.class, loader.getExtension("http3"));
    }
}
