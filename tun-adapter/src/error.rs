//! 全局错误类型定义
//!
//! 使用 `thiserror` 定义模块级错误类型，覆盖 TUN 适配器的所有错误场景。

use std::net::IpAddr;

/// TUN 适配器全局错误类型
#[derive(Debug, thiserror::Error)]
pub enum TunError {
    /// 配置文件加载或解析错误
    #[error("configuration error: {0}")]
    Config(String),

    /// TUN 设备创建或操作错误
    #[error("TUN device error: {0}")]
    TunDevice(String),

    /// 网络操作错误
    #[error("network error: {0}")]
    Network(String),

    /// DNS 处理错误
    #[error("DNS error: {0}")]
    Dns(#[from] DnsError),

    /// SOCKS5 代理连接错误
    #[error("SOCKS5 error: {0}")]
    Socks5(#[from] Socks5Error),

    /// 路由决策错误
    #[error("routing error: {0}")]
    Route(String),

    /// 系统路由表操作错误
    #[error("system route error: {0}")]
    SystemRoute(String),

    /// IO 错误
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    /// 协议栈错误
    #[error("stack error: {0}")]
    Stack(String),
}

/// DNS 处理专用错误类型
#[derive(Debug, thiserror::Error)]
pub enum DnsError {
    /// DNS 消息解析失败
    #[error("failed to parse DNS message: {0}")]
    ParseError(String),

    /// DNS 消息编码失败
    #[error("failed to encode DNS message: {0}")]
    EncodeError(String),

    /// 不支持的查询类型
    #[error("unsupported query type: {0}")]
    UnsupportedQueryType(String),

    /// FakeIP 池耗尽（理论上不会发生，因为是循环分配）
    #[error("FakeIP pool exhausted")]
    PoolExhausted,
}

/// SOCKS5 代理专用错误类型
#[derive(Debug, thiserror::Error)]
pub enum Socks5Error {
    /// SOCKS5 服务端不可达
    #[error("SOCKS5 proxy unreachable at {0}")]
    Unreachable(std::net::SocketAddr),

    /// SOCKS5 认证失败
    #[error("SOCKS5 authentication failed")]
    AuthFailed,

    /// SOCKS5 CONNECT 请求被拒绝: {0}
    #[error("SOCKS5 CONNECT rejected: {0}")]
    ConnectRejected(String),

    /// SOCKS5 协议错误
    #[error("SOCKS5 protocol error: {0}")]
    ProtocolError(String),

    /// 超时
    #[error("SOCKS5 operation timed out")]
    Timeout,

    /// IO 错误
    #[error("SOCKS5 IO error: {0}")]
    Io(#[from] std::io::Error),
}

/// 路由规则错误类型
#[derive(Debug, thiserror::Error)]
#[allow(clippy::enum_variant_names)]
pub enum RouterError {
    #[error("invalid route provider: {0}")]
    InvalidProvider(String),
    /// 无效的规则类型
    #[error("invalid rule type: {0}")]
    InvalidRuleType(String),

    /// 无效的 CIDR 格式
    #[error("invalid CIDR format: {0}")]
    InvalidCidr(String),

    /// 无效的路由动作
    #[error("invalid route action: {0}")]
    InvalidAction(String),

    /// 无效的 IP 地址
    #[error("invalid IP address: {0}")]
    InvalidIp(IpAddr),
}
