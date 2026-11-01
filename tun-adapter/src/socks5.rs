//! SOCKS5 客户端模块
//!
//! 实现轻量级 SOCKS5 客户端，将 TCP 字节流通过 SOCKS5 协议转发给 proxy-local。

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::error::Socks5Error;

/// SOCKS5 认证握手超时时间
const AUTH_TIMEOUT: Duration = Duration::from_secs(5);
/// SOCKS5 CONNECT 响应超时时间
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// SOCKS5 版本号
const SOCKS5_VERSION: u8 = 0x05;
/// 无认证方式
const AUTH_NO_AUTH: u8 = 0x00;
/// CONNECT 命令
const CMD_CONNECT: u8 = 0x01;
/// 地址类型：IPv4
const ATYP_IPV4: u8 = 0x01;
/// 地址类型：域名
const ATYP_DOMAIN: u8 = 0x03;

/// 通过 SOCKS5 代理转发 TCP 流量
///
/// # Arguments
/// * `target` - 目标地址（域名或 IP 字符串）
/// * `port` - 目标端口
/// * `app_stream` - 应用层的 TCP 字节流（来自 smoltcp）
/// * `socks5_addr` - SOCKS5 代理地址（通常是 127.0.0.1:1080）
pub async fn proxy_tcp_stream<S>(
    target: &str,
    port: u16,
    app_stream: S,
    socks5_addr: SocketAddr,
) -> Result<(), Socks5Error>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    tracing::info!(
        "SOCKS5 connecting to {}:{} via {}",
        target,
        port,
        socks5_addr
    );

    // 1. TCP connect 到 SOCKS5 代理
    let mut proxy_stream = tokio::time::timeout(AUTH_TIMEOUT, TcpStream::connect(socks5_addr))
        .await
        .map_err(|_| Socks5Error::Timeout)?
        .map_err(|_| Socks5Error::Unreachable(socks5_addr))?;

    // 2. SOCKS5 认证握手
    socks5_auth(&mut proxy_stream).await?;

    // 3. SOCKS5 CONNECT 请求
    socks5_connect_request(&mut proxy_stream, target, port).await?;

    // 4. 等待 SOCKS5 CONNECT 成功响应
    socks5_read_response(&mut proxy_stream).await?;

    tracing::info!("SOCKS5 tunnel established to {}:{}", target, port);

    let (sent, recv) =
        relay_with_idle_timeout(app_stream, proxy_stream, Duration::from_secs(120)).await?;
    tracing::info!(
        "SOCKS5 tunnel closed: {}:{} (sent: {}, recv: {})",
        target,
        port,
        sent,
        recv
    );
    Ok(())
}

/// Both directions share one idle deadline; cancelling this future drops both streams.
async fn relay_with_idle_timeout<A, B>(
    mut app: A,
    proxy: B,
    idle: Duration,
) -> Result<(u64, u64), Socks5Error>
where
    A: AsyncRead + AsyncWrite + Unpin,
    B: AsyncRead + AsyncWrite + Unpin,
{
    let activity = std::sync::Arc::new(std::sync::Mutex::new(tokio::time::Instant::now()));
    let mut proxy = ActivityStream {
        inner: proxy,
        activity: activity.clone(),
    };
    let copy = tokio::io::copy_bidirectional(&mut app, &mut proxy);
    tokio::pin!(copy);
    loop {
        let deadline = *activity.lock().unwrap() + idle;
        tokio::select! {
            // Process available I/O at the deadline before deciding the stream is idle.
            biased;
            result = &mut copy => return result.map_err(Socks5Error::Io),
            _ = tokio::time::sleep_until(deadline) => {
                if activity.lock().unwrap().elapsed() >= idle {
                    return Err(Socks5Error::Timeout);
                }
            }
        }
    }
}

struct ActivityStream<S> {
    inner: S,
    activity: std::sync::Arc<std::sync::Mutex<tokio::time::Instant>>,
}

impl<S: AsyncRead + Unpin> AsyncRead for ActivityStream<S> {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        let before = buf.filled().len();
        let result = std::pin::Pin::new(&mut self.inner).poll_read(cx, buf);
        if matches!(result, std::task::Poll::Ready(Ok(()))) && buf.filled().len() > before {
            *self.activity.lock().unwrap() = tokio::time::Instant::now();
        }
        result
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for ActivityStream<S> {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        let result = std::pin::Pin::new(&mut self.inner).poll_write(cx, buf);
        if matches!(result, std::task::Poll::Ready(Ok(n)) if n > 0) {
            *self.activity.lock().unwrap() = tokio::time::Instant::now();
        }
        result
    }
    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.inner).poll_flush(cx)
    }
    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// SOCKS5 认证握手（NO AUTH）
async fn socks5_auth(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    // 发送: VER(0x05) NMETHODS(0x01) METHODS(0x00=NO_AUTH)
    let auth_request = [SOCKS5_VERSION, 0x01, AUTH_NO_AUTH];
    stream.write_all(&auth_request).await?;
    stream.flush().await?;

    // 读取响应: VER(0x05) METHOD(0x00)
    let mut response = [0u8; 2];
    tokio::time::timeout(AUTH_TIMEOUT, stream.read_exact(&mut response))
        .await
        .map_err(|_| Socks5Error::Timeout)??;

    if response[0] != SOCKS5_VERSION {
        return Err(Socks5Error::ProtocolError(format!(
            "invalid SOCKS5 version in auth response: {}",
            response[0]
        )));
    }

    if response[1] != AUTH_NO_AUTH {
        return Err(Socks5Error::AuthFailed);
    }

    Ok(())
}

/// 发送 SOCKS5 CONNECT 请求
async fn socks5_connect_request(
    stream: &mut TcpStream,
    target: &str,
    port: u16,
) -> Result<(), Socks5Error> {
    let mut request = Vec::new();
    request.push(SOCKS5_VERSION); // VER
    request.push(CMD_CONNECT); // CMD
    request.push(0x00); // RSV

    // 尝试解析为 IPv4 地址，否则作为域名发送
    if let Ok(ipv4) = target.parse::<std::net::Ipv4Addr>() {
        request.push(ATYP_IPV4);
        request.extend_from_slice(&ipv4.octets());
    } else {
        // 域名方式（推荐：让远端做 DNS 解析）
        request.push(ATYP_DOMAIN);
        let domain_bytes = target.as_bytes();
        if domain_bytes.len() > 255 {
            return Err(Socks5Error::ProtocolError(
                "domain name too long".to_string(),
            ));
        }
        request.push(domain_bytes.len() as u8);
        request.extend_from_slice(domain_bytes);
    }

    // 端口（网络字节序，大端）
    request.extend_from_slice(&port.to_be_bytes());

    stream.write_all(&request).await?;
    stream.flush().await?;

    Ok(())
}

/// 读取 SOCKS5 CONNECT 响应
async fn socks5_read_response(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    // 最小响应：VER(1) + REP(1) + RSV(1) + ATYP(1) + ADDR(variable) + PORT(2)
    let mut header = [0u8; 4];
    tokio::time::timeout(CONNECT_TIMEOUT, stream.read_exact(&mut header))
        .await
        .map_err(|_| Socks5Error::Timeout)??;

    if header[0] != SOCKS5_VERSION {
        return Err(Socks5Error::ProtocolError(format!(
            "invalid SOCKS5 version in connect response: {}",
            header[0]
        )));
    }

    // 检查响应码
    if header[1] != 0x00 {
        let reason = match header[1] {
            0x01 => "general SOCKS server failure",
            0x02 => "connection not allowed by ruleset",
            0x03 => "network unreachable",
            0x04 => "host unreachable",
            0x05 => "connection refused",
            0x06 => "TTL expired",
            0x07 => "command not supported",
            0x08 => "address type not supported",
            _ => "unknown error",
        };
        return Err(Socks5Error::ConnectRejected(format!(
            "reply code 0x{:02x}: {}",
            header[1], reason
        )));
    }

    // 读取并丢弃绑定地址信息
    match header[3] {
        ATYP_IPV4 => {
            // IPv4: 4 bytes addr + 2 bytes port
            let mut buf = [0u8; 6];
            stream.read_exact(&mut buf).await?;
        }
        ATYP_DOMAIN => {
            // Domain: 1 byte len + domain + 2 bytes port
            let mut len_buf = [0u8; 1];
            stream.read_exact(&mut len_buf).await?;
            let mut buf = vec![0u8; len_buf[0] as usize + 2];
            stream.read_exact(&mut buf).await?;
        }
        0x04 => {
            // IPv6: 16 bytes addr + 2 bytes port
            let mut buf = [0u8; 18];
            stream.read_exact(&mut buf).await?;
        }
        atyp => {
            return Err(Socks5Error::ProtocolError(format!(
                "unknown ATYP in response: 0x{:02x}",
                atyp
            )));
        }
    }

    Ok(())
}

/// 仅执行 SOCKS5 认证握手（用于健康检查）
pub async fn socks5_auth_handshake(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    socks5_auth(stream).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;
    use tokio::net::TcpListener;

    #[tokio::test(start_paused = true)]
    async fn one_way_traffic_survives_for_five_minutes_in_both_directions() {
        for download in [false, true] {
            let (mut app, app_side) = duplex(64);
            let (mut remote, remote_side) = duplex(64);
            let relay = tokio::spawn(relay_with_idle_timeout(
                app_side,
                remote_side,
                Duration::from_secs(120),
            ));
            for n in 0..10 {
                tokio::time::advance(Duration::from_secs(30)).await;
                let (sender, receiver) = if download {
                    (&mut remote, &mut app)
                } else {
                    (&mut app, &mut remote)
                };
                sender.write_all(&[n]).await.unwrap();
                let mut byte = [0];
                receiver.read_exact(&mut byte).await.unwrap();
                assert_eq!(byte, [n]);
                assert!(!relay.is_finished());
            }
            drop(app);
            drop(remote);
            relay.await.unwrap().unwrap();
        }
    }

    #[tokio::test(start_paused = true)]
    async fn idle_timeout_releases_both_streams() {
        let (mut app, app_side) = duplex(64);
        let (mut remote, remote_side) = duplex(64);
        let relay = tokio::spawn(relay_with_idle_timeout(
            app_side,
            remote_side,
            Duration::from_secs(120),
        ));
        tokio::task::yield_now().await;
        tokio::time::advance(Duration::from_secs(121)).await;
        assert!(matches!(relay.await.unwrap(), Err(Socks5Error::Timeout)));
        assert_eq!(app.read(&mut [0]).await.unwrap(), 0);
        assert_eq!(remote.read(&mut [0]).await.unwrap(), 0);
    }

    #[tokio::test]
    async fn half_close_preserves_delayed_response() {
        let (mut app, app_side) = duplex(64);
        let (mut remote, remote_side) = duplex(64);
        let relay = tokio::spawn(relay_with_idle_timeout(
            app_side,
            remote_side,
            Duration::from_secs(120),
        ));
        app.write_all(b"request").await.unwrap();
        app.shutdown().await.unwrap();
        let mut request = vec![];
        remote.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"request");
        remote.write_all(b"response").await.unwrap();
        remote.shutdown().await.unwrap();
        let mut response = vec![];
        app.read_to_end(&mut response).await.unwrap();
        assert_eq!(response, b"response");
        assert_eq!(relay.await.unwrap().unwrap(), (7, 8));
    }

    #[tokio::test]
    async fn cancellation_releases_both_directions_without_detached_tasks() {
        let (mut app, app_side) = duplex(1);
        let (mut remote, remote_side) = duplex(1);
        let relay = tokio::spawn(relay_with_idle_timeout(
            app_side,
            remote_side,
            Duration::from_secs(120),
        ));
        tokio::task::yield_now().await;
        relay.abort();
        let _ = relay.await;
        assert_eq!(app.read(&mut [0]).await.unwrap(), 0);
        assert_eq!(remote.read(&mut [0]).await.unwrap(), 0);
    }

    /// 简单的 mock SOCKS5 server，接受连接后做认证和 CONNECT 响应
    async fn mock_socks5_server(listener: TcpListener) {
        let (mut stream, _) = listener.accept().await.unwrap();

        // 读取认证请求
        let mut buf = [0u8; 3];
        stream.read_exact(&mut buf).await.unwrap();
        assert_eq!(buf[0], SOCKS5_VERSION);
        assert_eq!(buf[1], 0x01);
        assert_eq!(buf[2], AUTH_NO_AUTH);

        // 响应认证成功
        stream
            .write_all(&[SOCKS5_VERSION, AUTH_NO_AUTH])
            .await
            .unwrap();

        // 读取 CONNECT 请求头
        let mut header = [0u8; 4];
        stream.read_exact(&mut header).await.unwrap();
        assert_eq!(header[0], SOCKS5_VERSION);
        assert_eq!(header[1], CMD_CONNECT);

        // 读取地址
        match header[3] {
            ATYP_DOMAIN => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len).await.unwrap();
                let mut domain = vec![0u8; len[0] as usize];
                stream.read_exact(&mut domain).await.unwrap();
                let mut port = [0u8; 2];
                stream.read_exact(&mut port).await.unwrap();
            }
            ATYP_IPV4 => {
                let mut addr = [0u8; 6]; // 4 + 2
                stream.read_exact(&mut addr).await.unwrap();
            }
            _ => panic!("unexpected ATYP"),
        }

        // 发送 CONNECT 成功响应
        let response = [
            SOCKS5_VERSION,
            0x00,
            0x00,
            ATYP_IPV4,
            0x00,
            0x00,
            0x00,
            0x00, // 绑定地址
            0x00,
            0x00, // 绑定端口
        ];
        stream.write_all(&response).await.unwrap();

        // Echo 模式：将收到的数据原样返回
        let mut buf = vec![0u8; 4096];
        loop {
            match stream.read(&mut buf).await {
                Ok(0) => break,
                Ok(n) => {
                    stream.write_all(&buf[..n]).await.unwrap();
                }
                Err(_) => break,
            }
        }
    }

    #[tokio::test]
    async fn test_socks5_auth_handshake() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 3];
            stream.read_exact(&mut buf).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();
        });

        let mut stream = TcpStream::connect(addr).await.unwrap();
        let result = socks5_auth_handshake(&mut stream).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_socks5_proxy_flow() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        // 启动 mock server
        tokio::spawn(mock_socks5_server(listener));

        // 创建一个 duplex stream 模拟应用层
        let (mut client, server_side) = duplex(4096);

        // 在后台通过 SOCKS5 转发
        let proxy_task = tokio::spawn(async move {
            proxy_tcp_stream("www.example.com", 80, server_side, addr).await
        });

        // 发送数据
        client.write_all(b"Hello, World!").await.unwrap();
        client.flush().await.unwrap();

        // 读取回显
        let mut buf = vec![0u8; 13];
        client.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"Hello, World!");

        // 关闭
        drop(client);
        let _ = proxy_task.await;
    }

    #[tokio::test]
    async fn test_socks5_unreachable() {
        // 连接一个不存在的端口
        let (_client, server_side) = duplex(4096);
        let addr: SocketAddr = "127.0.0.1:19999".parse().unwrap();

        let result = proxy_tcp_stream("www.example.com", 80, server_side, addr).await;
        assert!(result.is_err());
    }

    // =========================================================================
    // TC-TUN-004 [P1]：SOCKS5 CONNECT 请求编码正确性
    // =========================================================================

    /// 验证域名类型的 CONNECT 请求编码格式符合 RFC 1928
    #[tokio::test]
    async fn test_tc_tun004_connect_request_domain_encoding() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server_task = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            // 读取认证请求并回复
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // 读取 CONNECT 请求并验证编码
            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await.unwrap();
            assert_eq!(header[0], 0x05, "VER must be 0x05");
            assert_eq!(header[1], 0x01, "CMD must be CONNECT (0x01)");
            assert_eq!(header[2], 0x00, "RSV must be 0x00");
            assert_eq!(header[3], 0x03, "ATYP must be DOMAIN (0x03)");

            // 读取域名长度
            let mut len_buf = [0u8; 1];
            stream.read_exact(&mut len_buf).await.unwrap();
            assert_eq!(len_buf[0], 15, "domain 'www.example.com' is 15 bytes");

            // 读取域名
            let mut domain = vec![0u8; 15];
            stream.read_exact(&mut domain).await.unwrap();
            assert_eq!(&domain, b"www.example.com");

            // 读取端口（大端）
            let mut port_buf = [0u8; 2];
            stream.read_exact(&mut port_buf).await.unwrap();
            let port = u16::from_be_bytes(port_buf);
            assert_eq!(port, 443, "port must be 443");

            // 回复成功
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await
                .unwrap();
        });

        let (mut client, server_side) = duplex(4096);
        client.shutdown().await.unwrap();
        let _ = proxy_tcp_stream("www.example.com", 443, server_side, addr).await;
        server_task.await.unwrap();
    }

    /// 验证 IPv4 类型的 CONNECT 请求编码格式
    #[tokio::test]
    async fn test_tc_tun004_connect_request_ipv4_encoding() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server_task = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            // 认证
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // 读取 CONNECT 请求
            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await.unwrap();
            assert_eq!(header[3], 0x01, "ATYP must be IPv4 (0x01)");

            // 读取 IPv4 地址
            let mut ip = [0u8; 4];
            stream.read_exact(&mut ip).await.unwrap();
            assert_eq!(ip, [93, 184, 216, 34], "IP must be 93.184.216.34");

            // 读取端口
            let mut port_buf = [0u8; 2];
            stream.read_exact(&mut port_buf).await.unwrap();
            assert_eq!(u16::from_be_bytes(port_buf), 8080);

            // 回复成功
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await
                .unwrap();
        });

        let (mut client, server_side) = duplex(4096);
        client.shutdown().await.unwrap();
        let _ = proxy_tcp_stream("93.184.216.34", 8080, server_side, addr).await;
        server_task.await.unwrap();
    }

    // =========================================================================
    // TC-TUN-005 [P1]：SOCKS5 错误响应码正确映射
    // =========================================================================

    /// 验证 CONNECT 被拒绝时（REP != 0x00）返回 ConnectRejected 错误
    #[tokio::test]
    async fn test_tc_tun005_connect_rejected_host_unreachable() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            // 认证
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // 读取 CONNECT 请求（跳过）
            let mut buf = [0u8; 256];
            let _ = stream.read(&mut buf).await;

            // 回复 host unreachable (0x04)
            stream
                .write_all(&[0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await
                .unwrap();
        });

        let (_client, server_side) = duplex(4096);
        let result = proxy_tcp_stream("unreachable.host", 80, server_side, addr).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        match err {
            Socks5Error::ConnectRejected(msg) => {
                assert!(msg.contains("0x04"), "should contain reply code 0x04");
                assert!(
                    msg.contains("host unreachable"),
                    "should contain reason text"
                );
            }
            other => panic!("expected ConnectRejected, got: {:?}", other),
        }
    }

    /// 验证认证失败（服务端选择了非 NO_AUTH 方法）返回 AuthFailed
    #[tokio::test]
    async fn test_tc_tun005_auth_failed() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            // 回复需要用户名/密码认证 (0x02)
            stream.write_all(&[0x05, 0x02]).await.unwrap();
        });

        let mut stream = TcpStream::connect(addr).await.unwrap();
        let result = socks5_auth_handshake(&mut stream).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            Socks5Error::AuthFailed => {} // expected
            other => panic!("expected AuthFailed, got: {:?}", other),
        }
    }

    /// 验证服务端返回非法版本号时返回 ProtocolError
    #[tokio::test]
    async fn test_tc_tun005_invalid_version_in_auth_response() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            // 回复错误的版本号
            stream.write_all(&[0x04, 0x00]).await.unwrap();
        });

        let mut stream = TcpStream::connect(addr).await.unwrap();
        let result = socks5_auth_handshake(&mut stream).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            Socks5Error::ProtocolError(msg) => {
                assert!(msg.contains("version"), "should mention version: {}", msg);
            }
            other => panic!("expected ProtocolError, got: {:?}", other),
        }
    }

    /// 验证 CONNECT 响应中非法 ATYP 返回 ProtocolError
    #[tokio::test]
    async fn test_tc_tun005_unknown_atyp_in_response() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            // 认证
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // 读取 CONNECT 请求
            let mut buf = [0u8; 256];
            let _ = stream.read(&mut buf).await;

            // 回复成功但使用非法 ATYP (0xFF)
            stream
                .write_all(&[0x05, 0x00, 0x00, 0xFF, 0, 0, 0, 0, 0, 0])
                .await
                .unwrap();
        });

        let (_client, server_side) = duplex(4096);
        let result = proxy_tcp_stream("example.com", 80, server_side, addr).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            Socks5Error::ProtocolError(msg) => {
                assert!(msg.contains("ATYP"), "should mention ATYP: {}", msg);
            }
            other => panic!("expected ProtocolError, got: {:?}", other),
        }
    }
}
