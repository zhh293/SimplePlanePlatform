package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-GCM 加密实现（默认）
 * <p>
 * AEAD 认证加密，同时保证机密性和完整性，无需额外 HMAC。
 * 硬件加速（AES-NI 指令集），性能优秀。
 * </p>
 * <p>
 * 密文格式：[nonce(12B) | ciphertext | GCM-tag(16B)]
 * 解密时从头部取出 12 字节 nonce，剩余部分为密文+tag。
 * </p>
 */
public class AesGcmCipher implements Cipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmCipher.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12; // 96 bits，GCM 推荐长度
    private static final int TAG_LENGTH_BITS = 128; // 16 bytes

    private SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void init(CipherConfig config) {
        byte[] key = config.getKey();
        if (key == null || key.length == 0) {
            throw new CryptoException("AES-GCM key must not be null or empty");
        }

        // 支持 16/24/32 字节密钥（AES-128/192/256）
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            // 如果密钥长度不对，用 SHA-256 哈希到 32 字节
            key = sha256(key);
        }

        this.secretKey = new SecretKeySpec(key, ALGORITHM);
        log.info("AES-GCM cipher initialized, key length: {} bits", key.length * 8);
    }

    @Override
    public byte[] encrypt(byte[] plaintext) throws CryptoException {
        if (secretKey == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (plaintext == null || plaintext.length == 0) {
            return plaintext;
        }

        try {
            // 每次加密生成随机 nonce
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, nonce);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            // 输出格式：[nonce(12B) | ciphertext+tag]
            byte[] output = new byte[NONCE_LENGTH + ciphertext.length];
            System.arraycopy(nonce, 0, output, 0, NONCE_LENGTH);
            System.arraycopy(ciphertext, 0, output, NONCE_LENGTH, ciphertext.length);

            return output;
        } catch (Exception e) {
            throw new CryptoException("AES-GCM encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) throws CryptoException {
        if (secretKey == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            return ciphertext;
        }
        if (ciphertext.length < NONCE_LENGTH + TAG_LENGTH_BITS / 8) {
            throw new CryptoException("Ciphertext too short, expected at least "
                    + (NONCE_LENGTH + TAG_LENGTH_BITS / 8) + " bytes");
        }

        try {
            // 从头部取出 nonce
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(ciphertext, 0, nonce, 0, NONCE_LENGTH);

            // 剩余部分为密文+tag
            int encryptedLength = ciphertext.length - NONCE_LENGTH;
            byte[] encrypted = new byte[encryptedLength];
            System.arraycopy(ciphertext, NONCE_LENGTH, encrypted, 0, encryptedLength);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, nonce);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec);

            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM decryption failed (data may be tampered)", e);
        }
    }

    /**
     * SHA-256 哈希，将任意长度密钥规范化为 32 字节
     */
    private byte[] sha256(byte[] input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (Exception e) {
            throw new CryptoException("SHA-256 not available", e);
        }
    }
}
