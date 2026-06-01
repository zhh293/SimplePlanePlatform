package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

/**
 * ChaCha20-Poly1305 加密实现
 * <p>
 * 基于 BouncyCastle 实现，适用于没有 AES-NI 硬件加速的平台（如 ARM）。
 * ChaCha20 是流密码，Poly1305 提供消息认证（MAC），组合为 AEAD。
 * </p>
 * <p>
 * 密文格式：[nonce(12B) | ciphertext | Poly1305-tag(16B)]
 * </p>
 */
public class ChaCha20Cipher implements Cipher {

    private static final Logger log = LoggerFactory.getLogger(ChaCha20Cipher.class);

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int KEY_LENGTH = 32; // 256 bits

    private byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void init(CipherConfig config) {
        byte[] rawKey = config.getKey();
        if (rawKey == null || rawKey.length == 0) {
            throw new CryptoException("ChaCha20 key must not be null or empty");
        }

        // ChaCha20 要求 32 字节密钥
        if (rawKey.length != KEY_LENGTH) {
            this.key = sha256(rawKey);
        } else {
            this.key = rawKey.clone();
        }

        log.info("ChaCha20-Poly1305 cipher initialized");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) throws CryptoException {
        if (key == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (plaintext == null || plaintext.length == 0) {
            return plaintext;
        }

        try {
            // 生成随机 nonce
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            // ChaCha20 加密
            ChaCha7539Engine chacha = new ChaCha7539Engine();
            chacha.init(true, new ParametersWithIV(new KeyParameter(key), nonce));

            byte[] ciphertext = new byte[plaintext.length];
            chacha.processBytes(plaintext, 0, plaintext.length, ciphertext, 0);

            // Poly1305 MAC（对密文计算 tag）
            byte[] tag = computePoly1305Tag(ciphertext, nonce);

            // 输出格式：[nonce(12B) | ciphertext | tag(16B)]
            byte[] output = new byte[NONCE_LENGTH + ciphertext.length + TAG_LENGTH];
            System.arraycopy(nonce, 0, output, 0, NONCE_LENGTH);
            System.arraycopy(ciphertext, 0, output, NONCE_LENGTH, ciphertext.length);
            System.arraycopy(tag, 0, output, NONCE_LENGTH + ciphertext.length, TAG_LENGTH);

            return output;
        } catch (Exception e) {
            throw new CryptoException("ChaCha20-Poly1305 encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] data) throws CryptoException {
        if (key == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (data == null || data.length == 0) {
            return data;
        }
        if (data.length < NONCE_LENGTH + TAG_LENGTH) {
            throw new CryptoException("Ciphertext too short, expected at least "
                    + (NONCE_LENGTH + TAG_LENGTH) + " bytes");
        }

        try {
            // 解析 nonce
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(data, 0, nonce, 0, NONCE_LENGTH);

            // 解析密文
            int ciphertextLength = data.length - NONCE_LENGTH - TAG_LENGTH;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(data, NONCE_LENGTH, ciphertext, 0, ciphertextLength);

            // 解析 tag
            byte[] tag = new byte[TAG_LENGTH];
            System.arraycopy(data, NONCE_LENGTH + ciphertextLength, tag, 0, TAG_LENGTH);

            // 验证 Poly1305 tag
            byte[] expectedTag = computePoly1305Tag(ciphertext, nonce);
            if (!constantTimeEquals(tag, expectedTag)) {
                throw new CryptoException("ChaCha20-Poly1305 authentication failed (data tampered)");
            }

            // ChaCha20 解密（流密码，加密和解密操作相同）
            ChaCha7539Engine chacha = new ChaCha7539Engine();
            chacha.init(false, new ParametersWithIV(new KeyParameter(key), nonce));

            byte[] plaintext = new byte[ciphertextLength];
            chacha.processBytes(ciphertext, 0, ciphertextLength, plaintext, 0);

            return plaintext;
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("ChaCha20-Poly1305 decryption failed", e);
        }
    }

    /**
     * 计算 Poly1305 MAC
     * 使用 key 的前 32 字节 + nonce 派生 Poly1305 密钥
     */
    private byte[] computePoly1305Tag(byte[] data, byte[] nonce) {
        // 用 ChaCha20 的第 0 个 block 输出作为 Poly1305 的 key（标准做法）
        ChaCha7539Engine keyStream = new ChaCha7539Engine();
        keyStream.init(true, new ParametersWithIV(new KeyParameter(key), nonce));

        byte[] poly1305Key = new byte[32];
        byte[] zeros = new byte[32];
        keyStream.processBytes(zeros, 0, 32, poly1305Key, 0);

        Poly1305 poly1305 = new Poly1305();
        poly1305.init(new KeyParameter(poly1305Key));
        poly1305.update(data, 0, data.length);

        byte[] tag = new byte[TAG_LENGTH];
        poly1305.doFinal(tag, 0);
        return tag;
    }

    /**
     * 常量时间比较，防止时序攻击
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private byte[] sha256(byte[] input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (Exception e) {
            throw new CryptoException("SHA-256 not available", e);
        }
    }
}
