package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-CTR + HMAC-SHA256 加密实现（Encrypt-then-MAC）
 * <p>
 * 纯 JDK 实现，无需第三方依赖。
 * AES-CTR 提供机密性，HMAC-SHA256 提供完整性和认证。
 * Encrypt-then-MAC 模式：先加密，再对密文计算 MAC。
 * </p>
 * <p>
 * 密文格式：[iv(16B) | ciphertext | HMAC(32B)]
 * </p>
 * <p>
 * 密钥派生：从主密钥派生两个子密钥
 * - encKey = SHA-256(masterKey + "ENC")  用于 AES-CTR 加密
 * - macKey = SHA-256(masterKey + "MAC")  用于 HMAC-SHA256
 * </p>
 */
public class AesCtrHmacCipher implements Cipher {

    private static final Logger log = LoggerFactory.getLogger(AesCtrHmacCipher.class);

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_CTR_TRANSFORMATION = "AES/CTR/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int IV_LENGTH = 16;
    private static final int HMAC_LENGTH = 32;

    private SecretKeySpec encKey;
    private SecretKeySpec macKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void init(CipherConfig config) {
        byte[] masterKey = config.getKey();
        if (masterKey == null || masterKey.length == 0) {
            throw new CryptoException("AES-CTR-HMAC key must not be null or empty");
        }

        // 从主密钥派生加密密钥和 MAC 密钥
        byte[] encKeyBytes = deriveKey(masterKey, "ENC");
        byte[] macKeyBytes = deriveKey(masterKey, "MAC");

        this.encKey = new SecretKeySpec(encKeyBytes, AES_ALGORITHM);
        this.macKey = new SecretKeySpec(macKeyBytes, HMAC_ALGORITHM);

        log.info("AES-CTR-HMAC cipher initialized");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) throws CryptoException {
        if (encKey == null || macKey == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (plaintext == null || plaintext.length == 0) {
            return plaintext;
        }

        try {
            // 生成随机 IV
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            // AES-CTR 加密
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(AES_CTR_TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Encrypt-then-MAC：对 [iv | ciphertext] 计算 HMAC
            byte[] macInput = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, macInput, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, macInput, IV_LENGTH, ciphertext.length);

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(macKey);
            byte[] hmac = mac.doFinal(macInput);

            // 输出格式：[iv(16B) | ciphertext | HMAC(32B)]
            byte[] output = new byte[IV_LENGTH + ciphertext.length + HMAC_LENGTH];
            System.arraycopy(iv, 0, output, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, output, IV_LENGTH, ciphertext.length);
            System.arraycopy(hmac, 0, output, IV_LENGTH + ciphertext.length, HMAC_LENGTH);

            return output;
        } catch (Exception e) {
            throw new CryptoException("AES-CTR-HMAC encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] data) throws CryptoException {
        if (encKey == null || macKey == null) {
            throw new CryptoException("Cipher not initialized, call init() first");
        }
        if (data == null || data.length == 0) {
            return data;
        }
        if (data.length < IV_LENGTH + HMAC_LENGTH) {
            throw new CryptoException("Ciphertext too short, expected at least "
                    + (IV_LENGTH + HMAC_LENGTH) + " bytes");
        }

        try {
            // 解析各部分
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, IV_LENGTH);

            int ciphertextLength = data.length - IV_LENGTH - HMAC_LENGTH;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(data, IV_LENGTH, ciphertext, 0, ciphertextLength);

            byte[] receivedHmac = new byte[HMAC_LENGTH];
            System.arraycopy(data, IV_LENGTH + ciphertextLength, receivedHmac, 0, HMAC_LENGTH);

            // 验证 HMAC（先验证再解密，防止 padding oracle 攻击）
            byte[] macInput = new byte[IV_LENGTH + ciphertextLength];
            System.arraycopy(iv, 0, macInput, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, macInput, IV_LENGTH, ciphertextLength);

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(macKey);
            byte[] expectedHmac = mac.doFinal(macInput);

            if (!constantTimeEquals(receivedHmac, expectedHmac)) {
                throw new CryptoException("HMAC verification failed (data tampered)");
            }

            // AES-CTR 解密
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(AES_CTR_TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, encKey, new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("AES-CTR-HMAC decryption failed", e);
        }
    }

    /**
     * 密钥派生：SHA-256(masterKey + purpose)
     */
    private byte[] deriveKey(byte[] masterKey, String purpose) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(masterKey);
            digest.update(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            throw new CryptoException("Key derivation failed", e);
        }
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
}
