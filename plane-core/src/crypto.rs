//! Suite selection behind the existing encrypt/decrypt API. No automatic fallback.
#[path = "crypto_legacy.rs"]
mod legacy;
#[path = "crypto_v2.rs"]
mod v2;

use crate::error::{CoreError, Result};
use std::sync::Arc;

#[derive(Clone)]
pub struct Cipher {
    inner: Implementation,
}

#[derive(Clone)]
enum Implementation {
    Legacy(legacy::Cipher),
    V2(Arc<v2::RecordCipher>),
}

impl Cipher {
    /// Legacy constructor retained for existing callers and compatibility tests.
    pub fn new(raw_key: &[u8]) -> Result<Self> {
        Self::from_name("chacha20", raw_key)
    }

    pub fn from_name(name: &str, raw_key: &[u8]) -> Result<Self> {
        let inner = match name {
            "chacha20" => Implementation::Legacy(legacy::Cipher::new(raw_key)?),
            "chacha20-poly1305-v2" => {
                Implementation::V2(Arc::new(v2::RecordCipher::new(1, raw_key, false)?))
            }
            "aes-gcm-v2" => Implementation::V2(Arc::new(v2::RecordCipher::new(2, raw_key, false)?)),
            _ => {
                return Err(CoreError::Crypto(format!(
                    "Unsupported cipher suite: {name}"
                )))
            }
        };
        Ok(Self { inner })
    }

    /// Different streams use fresh salts/state; clones inside one stream share counters.
    pub fn fresh_session(&self) -> Result<Self> {
        let inner = match &self.inner {
            Implementation::Legacy(cipher) => Implementation::Legacy(cipher.clone()),
            Implementation::V2(cipher) => Implementation::V2(Arc::new(cipher.fresh_session()?)),
        };
        Ok(Self { inner })
    }

    #[cfg(test)]
    pub(crate) fn test_server(name: &str, raw_key: &[u8]) -> Result<Self> {
        let suite = match name {
            "chacha20" => return Self::new(raw_key),
            "chacha20-poly1305-v2" => 1,
            "aes-gcm-v2" => 2,
            _ => {
                return Err(CoreError::Crypto(format!(
                    "Unsupported cipher suite: {name}"
                )))
            }
        };
        Ok(Self {
            inner: Implementation::V2(Arc::new(v2::RecordCipher::new(suite, raw_key, true)?)),
        })
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        match &self.inner {
            Implementation::Legacy(cipher) => cipher.encrypt(plaintext),
            Implementation::V2(cipher) => cipher.encrypt(plaintext),
        }
    }

    pub fn decrypt(&self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        match &self.inner {
            Implementation::Legacy(cipher) => cipher.decrypt(ciphertext),
            Implementation::V2(cipher) => cipher.decrypt(ciphertext),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn suite_selection_is_explicit() {
        let key = [0x42; 32];
        assert!(Cipher::from_name("unknown", &key).is_err());
        assert!(Cipher::from_name("aes-gcm-v2", b"short").is_err());
        let old = Cipher::new(&key).unwrap().encrypt(b"legacy").unwrap();
        assert!(Cipher::from_name("chacha20-poly1305-v2", &key)
            .unwrap()
            .decrypt(&old)
            .is_err());
    }

    #[test]
    fn clones_share_nonce_sequence_but_sessions_have_fresh_salts() {
        let cipher = Cipher::from_name("chacha20-poly1305-v2", &[0x42; 32]).unwrap();
        let a = cipher.encrypt(b"a").unwrap();
        let b = cipher.clone().encrypt(b"b").unwrap();
        assert_eq!(&a[6..38], &b[6..38]);
        assert_eq!(&a[38..46], &0u64.to_be_bytes());
        assert_eq!(&b[38..46], &1u64.to_be_bytes());
        let c = cipher.fresh_session().unwrap().encrypt(b"c").unwrap();
        assert_ne!(&a[6..38], &c[6..38]);
        assert_eq!(&c[38..46], &0u64.to_be_bytes());
    }
}
