package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空加密实现（不加密）
 * <p>
 * 用于开发调试阶段，数据原样透传，不做任何加解密处理。
 * 生产环境不应使用此实现。
 * </p>
 */
public class NoneCipher implements Cipher {

    private static final Logger log = LoggerFactory.getLogger(NoneCipher.class);

    @Override
    public void init(CipherConfig config) {
        log.warn("NoneCipher initialized — data will NOT be encrypted! Do NOT use in production.");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) throws CryptoException {
        return plaintext;
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) throws CryptoException {
        return ciphertext;
    }
}
