package com.proxy.common.crypto;

import com.proxy.common.spi.SPI;

/**
 * 加解密 SPI 接口 —— 传输层加解密
 * <p>
 * 负责对传输数据进行加密和解密，确保本地到远程的通道安全。
 * 默认实现为 AES-256-GCM（AEAD 认证加密，硬件加速）。
 * </p>
 */
@SPI("aes-gcm")
public interface Cipher {

    /**
     * 初始化（传入密钥等参数）
     *
     * @param config 加密配置
     */
    void init(CipherConfig config);

    /**
     * 加密
     *
     * @param plaintext 明文数据
     * @return 密文数据
     * @throws CryptoException 加密失败时抛出
     */
    byte[] encrypt(byte[] plaintext) throws CryptoException;

    /**
     * 解密
     *
     * @param ciphertext 密文数据
     * @return 明文数据
     * @throws CryptoException 解密失败时抛出
     */
    byte[] decrypt(byte[] ciphertext) throws CryptoException;
}
