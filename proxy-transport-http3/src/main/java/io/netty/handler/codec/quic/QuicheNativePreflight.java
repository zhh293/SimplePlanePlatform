package io.netty.handler.codec.quic;

/**
 * Checks that the bundled quiche library can create a configuration handle
 * before Netty invokes any configuration setter on that handle.
 *
 * <p>Some Windows quiche builds return {@code -1} when configuration
 * allocation fails. Netty's normal builder path assumes allocation succeeded
 * and the following JNI setter then dereferences that sentinel in native code,
 * terminating the JVM. Keeping this check in the same package lets us use the
 * package-private Netty JNI bridge without copying or changing Netty classes.</p>
 */
public final class QuicheNativePreflight {
    private QuicheNativePreflight() {
    }

    public static void ensureConfigCanBeCreated() {
        final int version = Quiche.QUICHE_PROTOCOL_VERSION;
        final long config = Quiche.quiche_config_new(version);
        if (config <= 0) {
            throw new IllegalStateException(
                    "Native quiche failed to create a QUIC configuration (handle=" + config
                            + ", version=" + version
                            + "). The bundled Windows QUIC library is incompatible or failed to initialize.");
        }
        Quiche.quiche_config_free(config);
    }
}
