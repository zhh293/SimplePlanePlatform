package com.proxy.subscription;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class DeviceKeys {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private DeviceKeys() {}

    static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String userCode() {
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) value.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return value.toString();
    }

    static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    static byte[] digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] parsePublicKey(String encoded) {
        try {
            if (encoded == null || encoded.length() > 256) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(encoded);
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = key.getParams();
            if (!expected.getCurve().equals(actual.getCurve()) || !expected.getOrder().equals(actual.getOrder())
                    || !expected.getGenerator().equals(actual.getGenerator()) || expected.getCofactor() != actual.getCofactor()
                    || !Arrays.equals(key.getEncoded(), bytes)) throw new IllegalArgumentException();
            return bytes;
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid P-256 public key");
        }
    }

    static void verify(byte[] spki, String message, String signature) {
        try {
            if (signature == null || signature.length() > 160) throw new IllegalArgumentException();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki)));
            verifier.update(message.getBytes(StandardCharsets.US_ASCII));
            if (verifier.verify(Base64.getDecoder().decode(signature))) return;
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            // All malformed proofs and signatures made by another device get the same response.
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid device proof");
    }
}
