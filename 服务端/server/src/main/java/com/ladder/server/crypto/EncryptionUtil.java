package com.ladder.server.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.util.Base64;

public class EncryptionUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * 加密数据
     * 
     * @param data 原始数据
     * @param key  加密密钥
     * @return 加密后的数据（包含IV和tag）
     */
    public static byte[] encrypt(byte[] data, byte[] key) throws Exception {
        // 生成随机IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // 初始化加密器
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        // 加密数据
        byte[] encrypted = cipher.doFinal(data);

        // 组合IV和加密数据
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);

        return result;
    }

    /**
     * 解密数据
     * 
     * @param encryptedData 加密数据（包含IV和tag）
     * @param key           解密密钥
     * @return 解密后的数据
     */
    public static byte[] decrypt(byte[] encryptedData, byte[] key) throws Exception {
        // 提取IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, 0, iv, 0, iv.length);

        // 提取加密数据
        byte[] encrypted = new byte[encryptedData.length - iv.length];
        System.arraycopy(encryptedData, iv.length, encrypted, 0, encrypted.length);

        // 初始化解密器
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        // 解密数据
        return cipher.doFinal(encrypted);
    }

    /**
     * 生成AES密钥
     * 
     * @return 16字节的AES密钥
     */
    public static byte[] generateKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(128);
        return keyGenerator.generateKey().getEncoded();
    }

    /**
     * 将字节数组转换为Base64字符串
     * 
     * @param bytes 字节数组
     * @return Base64字符串
     */
    public static String bytesToBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 将Base64字符串转换为字节数组
     * 
     * @param base64 Base64字符串
     * @return 字节数组
     */
    public static byte[] base64ToBytes(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
