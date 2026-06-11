//! plane-core 错误类型。
//!
//! 遵循任务文档 0.2：模块级 `thiserror` 枚举，FFI 边界把 `Result` 转成错误码/异常，
//! 不让 panic 跨 FFI（见 [`crate::jni_bridge`] 的 `catch_unwind` 包裹）。
//!
//! A2 阶段只需覆盖 JNI 桥接最小闭环（参数解析、JNI 调用、配置反序列化）所需的变体；
//! A3/A5 等后续任务会按需扩充（如 TUN IO、加密、协议编解码错误）。

use thiserror::Error;

/// plane-core 统一错误类型。
///
/// 所有公开 API 返回 `Result<T, CoreError>`；在 JNI 边界由
/// [`crate::jni_bridge`] 统一转换为错误码（handle=0 / false）并记录日志，
/// 绝不让错误以 panic 形式跨越 FFI。
#[derive(Debug, Error)]
pub enum CoreError {
    /// 调用 JNI API（attach 线程、call_method、new_string 等）失败。
    #[error("JNI 调用失败: {0}")]
    Jni(#[from] jni::errors::Error),

    /// `configJson` 入参反序列化失败（字段缺失/类型不符/非法 JSON）。
    #[error("配置解析失败: {0}")]
    Config(#[from] serde_json::Error),

    /// 入参非法（如 fd < 0、handle 为空指针），由调用方校验后抛出。
    #[error("非法参数: {0}")]
    InvalidArgument(String),

    /// tokio 运行时构建失败等 IO 层错误。
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),

    /// A4 数据面：DNS 报文解析/编码失败。
    #[error("DNS 处理失败: {0}")]
    Dns(String),

    /// A5 出站层：ChaCha20-Poly1305 加解密失败（鉴权失败、密文过短等）。
    ///
    /// 对齐 Java `ChaCha20Cipher` 的 `CryptoException`：解密时 tag 不匹配报
    /// "authentication failed (data tampered)"。
    #[error("加密处理失败: {0}")]
    Crypto(String),

    /// A5 出站层：ProxyMessage 编解码失败（长度越界、字段非法等）。
    ///
    /// 对齐 Java `ProxyCodec` 的 `CodecException`。
    #[error("协议编解码失败: {0}")]
    Protocol(String),

    /// A6 数据面：内部通道/状态异常（如 TCP 事件通道意外关闭、栈内部不变量被破坏）。
    #[error("内部错误: {0}")]
    Internal(String),
}

/// 模块内统一的 `Result` 别名。
pub type Result<T> = std::result::Result<T, CoreError>;
