package com.proxy.transport.http3;

import io.netty.handler.codec.quic.QuicSslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Reads the short-lived TLS identity written by the native desktop credential store. */
final class DeviceCredential {
    private DeviceCredential() {}

    static void configure(QuicSslContextBuilder builder, String id) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("Invalid subscription node id");
        String directory = System.getenv("SIMPLEPLANE_LEASE_DIR");
        String secret = System.getenv("SIMPLEPLANE_LEASE_KEY");
        if (directory == null || secret == null) throw new IllegalStateException("Device credential store unavailable");
        Path file = Paths.get(directory).resolve("leases.properties");
        long size = Files.size(file);
        if (size < 32 || size > 4194304) throw new IllegalArgumentException("Invalid device credential store size");
        java.util.Properties leases = new java.util.Properties();
        try (java.io.InputStream input = Files.newInputStream(file)) { leases.load(input); }
        String record = leases.getProperty(id);
        if (record == null || record.length() > 24576) throw new IllegalStateException("Device credential unavailable");
        byte[] encrypted = Base64.getDecoder().decode(record);
        byte[] key = Base64.getDecoder().decode(secret);
        byte[] clear = null, pkcs8 = null;
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted, 0, 12));
            aes.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
            clear = aes.doFinal(encrypted, 12, encrypted.length - 12);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(clear));
            int length = input.readUnsignedShort();
            if (length == 0 || length >= input.available()) throw new IllegalArgumentException("Invalid device key length");
            pkcs8 = new byte[length];
            input.readFully(pkcs8);
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            cert.checkValidity();
            builder.keyManager(privateKey, null, cert).earlyData(false);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
            if (pkcs8 != null) Arrays.fill(pkcs8, (byte) 0);
        }
    }
}
