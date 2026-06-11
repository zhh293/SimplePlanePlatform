package com.proxy.crypto;

import com.proxy.common.crypto.CipherConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q1 —— 跨语言 ChaCha20-Poly1305 测试向量生成器（Java 侧权威产物）。
 * <p>
 * 本测试以一组<strong>固定 key / plaintext</strong> 调用生产实现 {@link ChaCha20Cipher}，
 * 把它实际运行的真实产物（含随机生成、再从输出前 12 字节拆出的 nonce）写入
 * {@code docs/design/crypto-vectors.json}，供 Rust 侧 {@code plane-core/src/crypto.rs}
 * 用同一 nonce 重放、逐字节比对，从而闭合 Go/No-Go 关口（出站加密层二进制兼容）。
 * </p>
 * <p>
 * 不引入任何 JSON 依赖（proxy-crypto 仅有 BouncyCastle/slf4j），手写最小 JSON 输出。
 * </p>
 */
public class CryptoVectorGenTest {

    /** 向量文件相对仓库根的路径；测试运行 CWD = proxy-crypto 模块目录，故回退一级。 */
    private static final String OUT_RELATIVE = "../docs/design/crypto-vectors.json";

    /** 一条向量记录：记录 Java 真实运行得到的全部字段（均以 hex 表达）。 */
    private static final class Vector {
        final String name;
        final byte[] rawKey;      // 传给 init 的原始 key（可能非 32 字节）
        final byte[] plaintext;   // 明文
        final byte[] nonce;       // Java encrypt 实际使用的 nonce（从输出前 12B 拆出）
        final byte[] ciphertext;  // 密文体（不含 nonce/tag）
        final byte[] tag;         // Poly1305 tag
        final byte[] full;        // encrypt 完整输出：nonce|ct|tag

        Vector(String name, byte[] rawKey, byte[] plaintext,
               byte[] nonce, byte[] ciphertext, byte[] tag, byte[] full) {
            this.name = name;
            this.rawKey = rawKey;
            this.plaintext = plaintext;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
            this.tag = tag;
            this.full = full;
        }
    }

    @Test
    public void generateCrossLanguageVectors() throws IOException {
        List<Vector> vectors = new ArrayList<>();

        // 组 1：恰 32 字节 key，ASCII 明文。
        vectors.add(makeVector(
                "key32_ascii",
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                "hello, proxy-remote!".getBytes(StandardCharsets.US_ASCII)));

        // 组 2：短 key（非 32 字节）→ 走 SHA-256 派生，验证 key 规整路径。
        vectors.add(makeVector(
                "shortkey_sha256",
                "short-key".getBytes(StandardCharsets.US_ASCII),
                "abc".getBytes(StandardCharsets.US_ASCII)));

        // 组 3：二进制载荷（含 0x00 / 0xFF），覆盖非可见字节。
        byte[] binPlain = new byte[64];
        for (int i = 0; i < binPlain.length; i++) {
            binPlain[i] = (byte) (i * 7);
        }
        vectors.add(makeVector(
                "key32_binary64",
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                binPlain));

        // 组 4：跨多个 ChaCha block 的较大载荷（200 字节 > 64B/block）。
        byte[] bigPlain = new byte[200];
        for (int i = 0; i < bigPlain.length; i++) {
            bigPlain[i] = (byte) (255 - (i % 251));
        }
        vectors.add(makeVector(
                "key32_big200",
                "an-unusual-32-byte-key-padding!!".getBytes(StandardCharsets.US_ASCII),
                bigPlain));

        // 组 5：单字节明文（最短非空边界）。
        vectors.add(makeVector(
                "key32_single_byte",
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) 0xA5}));

        // 写出 JSON。
        String json = renderJson(vectors);
        Path out = Paths.get(OUT_RELATIVE).toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("[Q1] crypto vectors written to: " + out);
    }

    /**
     * 用生产 {@link ChaCha20Cipher} 真跑一遍，拆出 nonce/ct/tag，并自检 decrypt 往返成功。
     */
    private Vector makeVector(String name, byte[] rawKey, byte[] plaintext) {
        ChaCha20Cipher cipher = new ChaCha20Cipher();
        cipher.init(new CipherConfig(rawKey));

        byte[] full = cipher.encrypt(plaintext);

        int nonceLen = 12;
        int tagLen = 16;
        assertTrue(full.length >= nonceLen + tagLen, "输出长度不足");

        byte[] nonce = new byte[nonceLen];
        System.arraycopy(full, 0, nonce, 0, nonceLen);

        int ctLen = full.length - nonceLen - tagLen;
        byte[] ct = new byte[ctLen];
        System.arraycopy(full, nonceLen, ct, 0, ctLen);

        byte[] tag = new byte[tagLen];
        System.arraycopy(full, nonceLen + ctLen, tag, 0, tagLen);

        // Java 自身往返自检：decrypt(full) == plaintext。
        byte[] roundtrip = cipher.decrypt(full);
        assertArrayEquals(plaintext, roundtrip, "Java 自身往返失败: " + name);

        return new Vector(name, rawKey, plaintext, nonce, ct, tag, full);
    }

    /** 手写最小 JSON（数组对象，字段值均为 hex 字符串）。 */
    private String renderJson(List<Vector> vectors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"algorithm\": \"chacha20-poly1305-nonstandard\",\n");
        sb.append("  \"source\": \"proxy-crypto/ChaCha20Cipher.java\",\n");
        sb.append("  \"note\": \"Generated by CryptoVectorGenTest; nonce is the real value used by Java encrypt().\",\n");
        sb.append("  \"vectors\": [\n");
        for (int i = 0; i < vectors.size(); i++) {
            Vector v = vectors.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(v.name).append("\",\n");
            sb.append("      \"raw_key_hex\": \"").append(hex(v.rawKey)).append("\",\n");
            sb.append("      \"plaintext_hex\": \"").append(hex(v.plaintext)).append("\",\n");
            sb.append("      \"nonce_hex\": \"").append(hex(v.nonce)).append("\",\n");
            sb.append("      \"ciphertext_hex\": \"").append(hex(v.ciphertext)).append("\",\n");
            sb.append("      \"tag_hex\": \"").append(hex(v.tag)).append("\",\n");
            sb.append("      \"full_output_hex\": \"").append(hex(v.full)).append("\"\n");
            sb.append("    }").append(i + 1 < vectors.size() ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
