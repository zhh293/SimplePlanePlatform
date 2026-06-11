//! A5.1 —— ChaCha20-Poly1305（**对齐 Java `proxy-crypto/ChaCha20Cipher.java` 的非标准实现**）。
//!
//! ⚠️ 本模块是整个出站层与 Java proxy-remote 二进制兼容的根基。任何「我觉得标准实现
//! 就行」的假设都是 bug 源头。实现严格逐行对照 `ChaCha20Cipher.java`，与 RFC 8439 的
//! 差异（即「非标准」之处）如下，**必须 1:1 复刻**：
//!
//! 1. **密文格式** = `nonce(12) | ciphertext | tag(16)`，nonce 前置且随每条消息生成内嵌。
//! 2. **引擎**用 BouncyCastle `ChaCha7539Engine`，即 RFC 7539/8439 的 ChaCha20
//!    （96-bit nonce + 32-bit counter）。Rust 侧对应 `chacha20::ChaCha20`（IETF 变体）。
//! 3. **ciphertext** 由一个「从 counter=0 开始」的引擎对 plaintext 做 keystream 异或得到
//!    （`ChaCha7539Engine.processBytes(plaintext)`，引擎刚 init、counter=0）。
//!    注意这与 RFC 8439 不同：RFC 的密文从 counter=1 起（counter=0 留给 Poly1305 key）。
//! 4. **Poly1305 key** 由「另一个独立的、同样从 counter=0 开始」的引擎对 32 字节全 0
//!    `processBytes` 得到的前 32 字节（即 keystream block0 的前 32 字节）。
//!    —— 两个引擎相互独立、都从 counter=0 起，所以 ciphertext 用的 keystream 与
//!    Poly1305 key 用的 keystream 在 block0 上是**重叠**的。这正是非标准点。
//! 5. **Poly1305 只对 ciphertext 计算**：无 AAD、无 RFC 的 16 字节对齐 padding、无
//!    `len(AAD)||len(C)` 尾部块。
//! 6. **key.len() != 32** 时用 `SHA-256(key)` 派生 32 字节密钥。
//!
//! 验证手段：以本模块单测 + Q1「跨语言测试向量」为准。先让向量过，再谈链路。

use crate::error::{CoreError, Result};
use chacha20::cipher::{KeyIvInit, StreamCipher};
use chacha20::ChaCha20;
use poly1305::universal_hash::KeyInit as _;
use poly1305::Poly1305;
use rand::RngCore;
use sha2::{Digest, Sha256};

// 说明：Poly1305 tag 用 `compute_unpadded`，**不可**用 `update_padded`。
// `compute_unpadded` 对最后不完整块在 `chunk.len()` 处置 1、其余字段不补零对齐到 16 字节，
// 这与 BouncyCastle `org.bouncycastle.crypto.macs.Poly1305`（Java 侧 doFinal 的标准行为）一致；
// 而 `update_padded` 是 AEAD 专用「补零到 16 字节边界」语义，会算出与 Java 不同的 tag。

/// nonce 长度（字节），对齐 Java `NONCE_LENGTH`。
pub const NONCE_LENGTH: usize = 12;
/// Poly1305 tag 长度（字节），对齐 Java `TAG_LENGTH`。
pub const TAG_LENGTH: usize = 16;
/// ChaCha20 密钥长度（字节，256 bit），对齐 Java `KEY_LENGTH`。
pub const KEY_LENGTH: usize = 32;

/// ChaCha20-Poly1305 加解密器（非标准实现，与 Java 二进制兼容）。
///
/// 通过 [`Cipher::new`] 构造，内部持有规整为 32 字节的密钥；与 Java `ChaCha20Cipher.init`
/// 一致：原始 key 恰为 32 字节则直接使用，否则取其 `SHA-256` 作为密钥。
#[derive(Clone)]
pub struct Cipher {
    /// 规整后的 32 字节密钥。
    key: [u8; KEY_LENGTH],
}

impl Cipher {
    /// 以原始密钥构造。
    ///
    /// 对齐 Java `init(CipherConfig)`：
    /// - `raw_key` 为空 → 返回 [`CoreError::Crypto`]（对齐 Java 的 "key must not be null or empty"）。
    /// - `raw_key.len() == 32` → 直接克隆使用。
    /// - 否则 → 使用 `SHA-256(raw_key)` 派生。
    pub fn new(raw_key: &[u8]) -> Result<Self> {
        if raw_key.is_empty() {
            return Err(CoreError::Crypto(
                "ChaCha20 key must not be null or empty".to_string(),
            ));
        }
        let key = if raw_key.len() == KEY_LENGTH {
            let mut k = [0u8; KEY_LENGTH];
            k.copy_from_slice(raw_key);
            k
        } else {
            sha256(raw_key)
        };
        Ok(Self { key })
    }

    /// 加密：随机生成 nonce，输出 `nonce(12) | ciphertext | tag(16)`。
    ///
    /// 对齐 Java `encrypt(byte[])`：明文为空时原样返回空（Java 在 plaintext 为 null/空时
    /// 直接 `return plaintext`）。
    pub fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        if plaintext.is_empty() {
            return Ok(Vec::new());
        }
        let mut nonce = [0u8; NONCE_LENGTH];
        rand::thread_rng().fill_bytes(&mut nonce);
        Ok(self.encrypt_with_nonce(&nonce, plaintext))
    }

    /// 使用**指定 nonce** 加密（测试专用 / Q1 向量比对用）。
    ///
    /// 暴露固定 nonce 入口以便与 Java 端逐字节比对（生产路径用 [`Cipher::encrypt`] 的随机 nonce）。
    /// 流程严格对齐 Java：
    /// 1. 用 (key, nonce) 新建引擎、counter=0，对 plaintext 异或得 ciphertext；
    /// 2. 用 (key, nonce) 另起独立引擎、counter=0，对 32 字节全 0 出 keystream → Poly1305 key；
    /// 3. Poly1305 仅 update(ciphertext) → tag；
    /// 4. 拼 `nonce | ciphertext | tag`。
    pub fn encrypt_with_nonce(&self, nonce: &[u8; NONCE_LENGTH], plaintext: &[u8]) -> Vec<u8> {
        // (1) ChaCha20 加密：引擎刚 init，counter=0，apply_keystream 即按位异或。
        let mut ciphertext = plaintext.to_vec();
        let mut enc = ChaCha20::new(&self.key.into(), nonce.into());
        enc.apply_keystream(&mut ciphertext);

        // (2)(3) Poly1305 tag（仅对密文，无 AAD/padding/长度尾块）。
        let tag = self.compute_poly1305_tag(&ciphertext, nonce);

        // (4) 输出 nonce | ciphertext | tag。
        let mut out = Vec::with_capacity(NONCE_LENGTH + ciphertext.len() + TAG_LENGTH);
        out.extend_from_slice(nonce);
        out.extend_from_slice(&ciphertext);
        out.extend_from_slice(&tag);
        out
    }

    /// 解密：拆 `nonce | ciphertext | tag`，常量时间校验 tag 后异或还原明文。
    ///
    /// 对齐 Java `decrypt(byte[])`：
    /// - 空输入原样返回空；
    /// - 长度 < `NONCE+TAG` → [`CoreError::Crypto`]（"Ciphertext too short"）；
    /// - tag 不匹配 → [`CoreError::Crypto`]（"authentication failed (data tampered)"）。
    pub fn decrypt(&self, data: &[u8]) -> Result<Vec<u8>> {
        if data.is_empty() {
            return Ok(Vec::new());
        }
        if data.len() < NONCE_LENGTH + TAG_LENGTH {
            return Err(CoreError::Crypto(format!(
                "Ciphertext too short, expected at least {} bytes",
                NONCE_LENGTH + TAG_LENGTH
            )));
        }

        let mut nonce = [0u8; NONCE_LENGTH];
        nonce.copy_from_slice(&data[..NONCE_LENGTH]);

        let ct_len = data.len() - NONCE_LENGTH - TAG_LENGTH;
        let ciphertext = &data[NONCE_LENGTH..NONCE_LENGTH + ct_len];
        let tag = &data[NONCE_LENGTH + ct_len..];

        // 校验 tag（常量时间）。
        let expected = self.compute_poly1305_tag(ciphertext, &nonce);
        if !constant_time_eq(tag, &expected) {
            return Err(CoreError::Crypto(
                "ChaCha20-Poly1305 authentication failed (data tampered)".to_string(),
            ));
        }

        // 解密：流密码加解密同一操作，引擎 counter=0。
        let mut plaintext = ciphertext.to_vec();
        let mut dec = ChaCha20::new(&self.key.into(), (&nonce).into());
        dec.apply_keystream(&mut plaintext);
        Ok(plaintext)
    }

    /// 计算 Poly1305 tag —— 对齐 Java `computePoly1305Tag`。
    ///
    /// 用「独立的、counter=0」ChaCha20 引擎对 32 字节全 0 出 keystream 取前 32 字节作
    /// Poly1305 key，再仅对 `data`（密文）update。
    fn compute_poly1305_tag(&self, data: &[u8], nonce: &[u8; NONCE_LENGTH]) -> [u8; TAG_LENGTH] {
        let mut poly_key = [0u8; 32];
        let mut ks = ChaCha20::new(&self.key.into(), nonce.into());
        // 对 32 字节全 0 apply_keystream，结果即 keystream 的前 32 字节。
        ks.apply_keystream(&mut poly_key);

        let mac = Poly1305::new((&poly_key).into());
        // 标准（非补零对齐）Poly1305：等价于 BouncyCastle update(data)+doFinal。
        let result = mac.compute_unpadded(data);
        let mut tag = [0u8; TAG_LENGTH];
        tag.copy_from_slice(result.as_slice());
        tag
    }
}

/// SHA-256（对齐 Java `sha256`，用于非 32 字节 key 的派生）。
fn sha256(input: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(input);
    let digest = hasher.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    out
}

/// 常量时间比较，防止时序攻击（对齐 Java `constantTimeEquals`）。
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY32: &[u8] = b"0123456789abcdef0123456789abcdef"; // 恰 32 字节

    #[test]
    fn roundtrip_basic() {
        let c = Cipher::new(KEY32).unwrap();
        let plain = b"hello, proxy-remote!";
        let sealed = c.encrypt(plain).unwrap();
        // 格式：nonce(12) + ct(len) + tag(16)
        assert_eq!(sealed.len(), NONCE_LENGTH + plain.len() + TAG_LENGTH);
        let opened = c.decrypt(&sealed).unwrap();
        assert_eq!(opened, plain);
    }

    #[test]
    fn roundtrip_with_fixed_nonce_is_deterministic() {
        let c = Cipher::new(KEY32).unwrap();
        let nonce = [7u8; NONCE_LENGTH];
        let plain = b"deterministic payload";
        let a = c.encrypt_with_nonce(&nonce, plain);
        let b = c.encrypt_with_nonce(&nonce, plain);
        assert_eq!(a, b, "固定 nonce 下加密应确定");
        assert_eq!(c.decrypt(&a).unwrap(), plain);
    }

    #[test]
    fn empty_plaintext_returns_empty() {
        let c = Cipher::new(KEY32).unwrap();
        assert!(c.encrypt(b"").unwrap().is_empty());
        assert!(c.decrypt(&[]).unwrap().is_empty());
    }

    #[test]
    fn tamper_ciphertext_fails_auth() {
        let c = Cipher::new(KEY32).unwrap();
        let mut sealed = c.encrypt(b"important secret").unwrap();
        // 翻转密文区的一个 bit（跳过 nonce）。
        sealed[NONCE_LENGTH] ^= 0x01;
        let err = c.decrypt(&sealed).unwrap_err();
        assert!(matches!(err, CoreError::Crypto(_)));
    }

    #[test]
    fn tamper_tag_fails_auth() {
        let c = Cipher::new(KEY32).unwrap();
        let mut sealed = c.encrypt(b"important secret").unwrap();
        let last = sealed.len() - 1;
        sealed[last] ^= 0x80;
        assert!(c.decrypt(&sealed).is_err());
    }

    #[test]
    fn too_short_ciphertext_fails() {
        let c = Cipher::new(KEY32).unwrap();
        let short = vec![0u8; NONCE_LENGTH + TAG_LENGTH - 1];
        assert!(c.decrypt(&short).is_err());
    }

    #[test]
    fn non_32_byte_key_uses_sha256() {
        // 短 key 走 SHA-256 派生；与「直接用 SHA256(key) 当 32B key」结果一致。
        let raw = b"short-key";
        let c1 = Cipher::new(raw).unwrap();
        let derived = sha256(raw);
        let c2 = Cipher::new(&derived).unwrap();
        let nonce = [3u8; NONCE_LENGTH];
        let plain = b"abc";
        assert_eq!(
            c1.encrypt_with_nonce(&nonce, plain),
            c2.encrypt_with_nonce(&nonce, plain),
            "短 key 应等价于其 SHA-256 派生"
        );
    }

    #[test]
    fn empty_key_rejected() {
        assert!(Cipher::new(b"").is_err());
    }

    #[test]
    fn large_payload_roundtrip() {
        let c = Cipher::new(KEY32).unwrap();
        let plain = vec![0xABu8; 2 * 1024 * 1024]; // 2MB，跨多个 ChaCha block
        let sealed = c.encrypt(&plain).unwrap();
        assert_eq!(c.decrypt(&sealed).unwrap(), plain);
    }

    /// 锁死「非标准」关键不变量：ciphertext 的 keystream 从 counter=0 起，
    /// 即对全 0 明文加密得到的密文 == Poly1305 key 推导所用的同一段 keystream 前缀。
    /// 这条断言专门防止有人误用 RFC 8439（counter 从 1 开始）。
    #[test]
    fn ciphertext_keystream_starts_at_counter_zero() {
        let c = Cipher::new(KEY32).unwrap();
        let nonce = [0u8; NONCE_LENGTH];

        // 对 32 字节全 0 明文加密 → 取出密文区（=keystream[0..32]）。
        let zeros = [0u8; 32];
        let sealed = c.encrypt_with_nonce(&nonce, &zeros);
        let ct = &sealed[NONCE_LENGTH..NONCE_LENGTH + 32];

        // 独立用 ChaCha20 从 counter=0 出 keystream 前 32 字节。
        let mut ks = [0u8; 32];
        let mut eng = ChaCha20::new(&c.key.into(), (&nonce).into());
        eng.apply_keystream(&mut ks);

        assert_eq!(ct, &ks, "密文 keystream 必须从 counter=0 开始（非标准点）");
    }

    // ───────────────────────── Q1：跨语言测试向量 ─────────────────────────
    //
    // 向量由 Java 侧权威实现 `proxy-crypto/ChaCha20Cipher.java` 真实运行产出
    //（见 `proxy-crypto/.../CryptoVectorGenTest.java`），通过 `include_str!` 在编译期
    // 嵌入。本测试用同一组 (raw_key, nonce, plaintext) 在 Rust 侧重放，逐字节断言
    // ciphertext / tag / 完整输出与 Java 一致，并双向互解，从而闭合出站加密层的
    // Go/No-Go 关口（与 proxy-remote 二进制兼容）。

    /// 编译期嵌入 Java 生成的跨语言向量。文件缺失则编译失败（提示先跑 Java 生成器）。
    const CRYPTO_VECTORS_JSON: &str = include_str!("../../docs/design/crypto-vectors.json");

    /// 解析一段 hex 字符串为字节（仅测试用，零额外依赖）。
    fn hex_decode(s: &str) -> Vec<u8> {
        // 不用 `% 2 == 0`（clippy manual_is_multiple_of）也不用 `is_multiple_of`
        //（后者较新工具链才稳定，避免 CI 旧版报错）：用末位 bit 判偶。
        assert!((s.len() & 1) == 0, "hex 长度必须为偶数: {s}");
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("非法 hex 字符"))
            .collect()
    }

    /// 取 serde_json::Value 里的 hex 字段并解码。
    fn get_hex(v: &serde_json::Value, key: &str) -> Vec<u8> {
        hex_decode(v[key].as_str().unwrap_or_else(|| panic!("缺字段 {key}")))
    }

    #[test]
    fn cross_language_vectors_match_java() {
        let root: serde_json::Value =
            serde_json::from_str(CRYPTO_VECTORS_JSON).expect("向量 JSON 解析失败");
        let vectors = root["vectors"].as_array().expect("vectors 应为数组");
        assert!(!vectors.is_empty(), "向量集合不应为空");

        for v in vectors {
            let name = v["name"].as_str().unwrap_or("<unnamed>");
            let raw_key = get_hex(v, "raw_key_hex");
            let plaintext = get_hex(v, "plaintext_hex");
            let nonce_bytes = get_hex(v, "nonce_hex");
            let java_ct = get_hex(v, "ciphertext_hex");
            let java_tag = get_hex(v, "tag_hex");
            let java_full = get_hex(v, "full_output_hex");

            assert_eq!(nonce_bytes.len(), NONCE_LENGTH, "[{name}] nonce 长度异常");
            let mut nonce = [0u8; NONCE_LENGTH];
            nonce.copy_from_slice(&nonce_bytes);

            let cipher = Cipher::new(&raw_key).unwrap();

            // (1) Rust 用同一 nonce 加密 → 必须逐字节等于 Java 的完整输出。
            let rust_full = cipher.encrypt_with_nonce(&nonce, &plaintext);
            assert_eq!(
                rust_full, java_full,
                "[{name}] Rust 完整输出与 Java 不一致（nonce|ct|tag）"
            );

            // (2) 分区再核对 ct / tag，定位差异更精确。
            let rust_ct = &rust_full[NONCE_LENGTH..rust_full.len() - TAG_LENGTH];
            let rust_tag = &rust_full[rust_full.len() - TAG_LENGTH..];
            assert_eq!(rust_ct, java_ct.as_slice(), "[{name}] ciphertext 不一致");
            assert_eq!(
                rust_tag,
                java_tag.as_slice(),
                "[{name}] Poly1305 tag 不一致"
            );

            // (3) Rust 解 Java 的整包 → 还原明文（反向互通）。
            let decrypted = cipher.decrypt(&java_full).unwrap_or_else(|e| {
                panic!("[{name}] Rust 解 Java 密文失败: {e}");
            });
            assert_eq!(decrypted, plaintext, "[{name}] Rust 解出的明文与原文不符");
        }
    }
}
