//! A5.3/A5.4 —— 加密 HTTP/2 出站客户端（**等价替换 Java `proxy-local` +
//! `proxy-transport-netty` 的客户端侧**，与 `proxy-remote` 二进制兼容）。
//!
//! ## 与 Java 对齐的链路（逐段对照，不得臆测）
//!
//! 出站方向（client → remote），对照 `ProxyMessageEncoder` + `CipherEncodeHandler`：
//!
//! ```text
//! ProxyMessage
//!   → proxy_proto::encode      (28B 明文头 + host + data)
//!   → crypto::Cipher::encrypt  (对「整段明文」加密 → nonce|ct|tag)
//!   → 一个 HTTP/2 DATA 帧       (end_stream=false)
//! ```
//!
//! 每条 Stream 的**第一帧是 HEADERS**（`:method POST`、`:path /proxy`、`:scheme http`、
//! `content-type: application/octet-stream`，end_stream=false），HEADERS **不加密**
//! （Java `CipherEncodeHandler` 只处理 `Http2DataFrame`）。见 `ProxyMessageEncoder.encode`。
//!
//! 入站方向（remote → client），对照 `CipherDecodeHandler` + `ProxyMessageDecoder`：
//!
//! ```text
//! HTTP/2 DATA 帧
//!   → crypto::Cipher::decrypt  (每个 DATA 帧独立解密)
//!   → 累积进读缓冲，proxy_proto::try_decode_one 反复切包（处理跨帧/粘包）
//!   → ProxyMessage
//! ```
//!
//! ## 设计铁律
//!
//! - **protect 后再 connect**：所有出站 socket 必须先经 [`SocketProtector::protect`]
//!   保护（避免被 VPN 自身路由回环），再 `connect`。见任务文档 0.3。
//! - HTTP/2 窗口对齐 Java `Http2Connection`：stream initial window 1MB、
//!   connection window 增至 ~16MB（[`H2_STREAM_WINDOW`] / [`H2_CONNECTION_WINDOW`]）。
//! - SSL 可选：Java 用 `InsecureTrustManager + ALPN h2`；MVP 可走 h2c 明文（见
//!   [`OutboundConfig::tls`]）。本模块先落地 h2c 链路，TLS 留待接 rustls（记技术债）。
//! - **cipher 兼容性提醒**：Java `Http2Connection` 的 `cipher` 参数默认 `aes-gcm`，
//!   仅当 url 带 `cipher=chacha20`（且 `cipherKey=...`）时才与本模块的 ChaCha20-Poly1305
//!   二进制兼容。联调时 proxy-remote 必须显式配 ChaCha20。

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use bytes::Bytes;
use tokio::io::{AsyncRead, AsyncWrite};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::proxy_proto::{self, MessageType, ProxyMessage};

/// HTTP/2 单条 stream 初始流控窗口（字节），对齐 Java `initialWindowSize(1024*1024)`。
pub const H2_STREAM_WINDOW: u32 = 1024 * 1024;
/// HTTP/2 连接级流控窗口目标（字节，~16MB），对齐 Java「increment 16*1024*1024-65535 后总量」。
pub const H2_CONNECTION_WINDOW: u32 = 16 * 1024 * 1024;

/// socket 保护回调抽象 —— 对应 Java/Kotlin 的 `VpnService.protect(fd)`。
///
/// 所有出站 socket 在 `connect` **之前**必须调用 [`protect`](SocketProtector::protect)；
/// 否则发往代理服务器的流量会被 VPN 自身路由捕获，形成回环。
///
/// 单测可注入一个 no-op 实现；生产由 JNI 桥接调用 Kotlin `protect`。
pub trait SocketProtector: Send + Sync {
    /// 保护给定 fd 的 socket，使其流量绕过 VPN 隧道。返回 `false` 表示保护失败。
    fn protect(&self, fd: i32) -> bool;
}

/// 一个永远成功的 no-op 保护器（仅用于单测/本机直连场景）。
#[derive(Debug, Clone, Copy, Default)]
pub struct NoopProtector;

impl SocketProtector for NoopProtector {
    fn protect(&self, _fd: i32) -> bool {
        true
    }
}

/// 出站连接配置。
#[derive(Debug, Clone)]
pub struct OutboundConfig {
    /// 代理服务器主机（proxy-remote）。
    pub server_host: String,
    /// 代理服务器端口。
    pub server_port: u16,
    /// 是否启用 TLS（对齐 Java `ssl` 开关）。MVP 暂仅支持 `false`（h2c 明文）。
    pub tls: bool,
}

/// 密文长度前缀字节数（4 字节大端），对齐 Java `CipherEncodeHandler` / `CipherDecodeHandler`。
pub const CIPHER_LENGTH_PREFIX: usize = 4;

/// 出站方向的「ProxyMessage → 一个加密 DATA 帧负载」编码器（对照 Java
/// `ProxyMessageEncoder` + `CipherEncodeHandler` 的 DATA 帧部分）。
///
/// 注意：HEADERS 帧由 [`OutboundConnection::open_proxy_stream`] 在 stream 首帧单独发送且
/// **不加密**，不在此函数内。
///
/// ## 长度前缀分帧（与 Java 端对称，2026-06 修复）
///
/// 输出格式：`[4字节密文总长度(大端)][nonce|ciphertext|tag]`。
///
/// 起因：HTTP/2 不保证 DATA 帧边界与发送侧一一对应（受 maxFrameSize=16KB、流控、合帧
/// 影响）。大流量传输时一个加密块会被 h2 重新切分到多帧，接收侧若按「单帧 == 单密文块」
/// 解密就会因密文不完整导致 Poly1305 认证失败、连接被关，表现为吞吐近乎为 0。
/// 加 4 字节长度前缀后，接收侧（[`InboundReassembler`]）可在密文层累积字节并按长度精确
/// 切出完整密文块，彻底摆脱对帧边界的依赖。Java 端 `CipherEncodeHandler` 已做同样改造。
pub fn encode_encrypted_frame(cipher: &Cipher, msg: &ProxyMessage) -> Result<Vec<u8>> {
    let plaintext = msg.encode(); // 28B 明文头 + host + data
    let ciphertext = cipher.encrypt(&plaintext)?; // 对整段明文加密 → nonce|ct|tag

    // 长度前缀分帧：[4字节密文总长度(大端)][nonce|ct|tag]
    let mut framed = Vec::with_capacity(CIPHER_LENGTH_PREFIX + ciphertext.len());
    framed.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
    framed.extend_from_slice(&ciphertext);
    Ok(framed)
}

/// 入站方向的解密 + 切包累积器（对照 Java `CipherDecodeHandler` + `ProxyMessageDecoder`）。
///
/// ## 两级累积（与 Java 端对称，2026-06 修复）
///
/// 收到的 HTTP/2 DATA 帧字节先进**密文层累积缓冲** [`cipher_buffer`](Self::cipher_buffer)，
/// 因为 HTTP/2 不保证帧边界与发送侧一一对应（受 maxFrameSize/流控/合帧影响），一个加密块
/// 可能被拆到多帧、或多个加密块被合到一帧。发送侧（[`encode_encrypted_frame`]）在每个密文块
/// 前写入 4 字节大端长度，本结构据此循环「读 4 字节长度 → 够长就切出一个完整密文块 decrypt
/// → 不够就等下一帧」，得到的明文再进**明文层累积缓冲** [`plain_buffer`](Self::plain_buffer)，
/// 最后用 [`proxy_proto::try_decode_one`] 反复切出完整 [`ProxyMessage`]（处理 ProxyMessage
/// 级别的粘包/拆包）。
///
/// 抽离为独立结构体便于单测「不依赖真实网络」地验证 累积→解密→切包 的完整语义。
pub struct InboundReassembler {
    cipher: Cipher,
    /// 尚未凑成完整密文块（[4字节长度][密文]）的剩余字节累积缓冲。
    cipher_buffer: Vec<u8>,
    /// 已解密但尚未切出完整消息的明文累积缓冲。
    plain_buffer: Vec<u8>,
}

impl InboundReassembler {
    /// 以加解密器构造。
    pub fn new(cipher: Cipher) -> Self {
        Self {
            cipher,
            cipher_buffer: Vec::new(),
            plain_buffer: Vec::new(),
        }
    }

    /// 处理一个收到的（已加密的）DATA 帧负载：
    ///
    /// 1. 帧字节追加进密文层累积缓冲；
    /// 2. 循环按 4 字节大端长度切出完整密文块，逐块 `decrypt` 还原明文追加进明文缓冲；
    /// 3. 反复 `try_decode_one` 切出所有当前可解析的完整 [`ProxyMessage`]。
    ///
    /// 返回本次切出的全部消息（可能为 0 条——半个密文块或半条消息会留在缓冲等下一帧）。
    pub fn push_encrypted_frame(&mut self, frame: &[u8]) -> Result<Vec<ProxyMessage>> {
        // (1) 帧字节进密文层累积缓冲。
        self.cipher_buffer.extend_from_slice(frame);

        // (2) 按长度前缀循环切出完整密文块并解密。
        let mut offset = 0usize;
        while self.cipher_buffer.len() - offset >= CIPHER_LENGTH_PREFIX {
            // 读 4 字节大端长度（不前移，长度不够时回退等待）。
            let len_bytes = &self.cipher_buffer[offset..offset + CIPHER_LENGTH_PREFIX];
            let ct_len =
                u32::from_be_bytes([len_bytes[0], len_bytes[1], len_bytes[2], len_bytes[3]])
                    as usize;

            if self.cipher_buffer.len() - offset < CIPHER_LENGTH_PREFIX + ct_len {
                // 本密文块还没收齐，等下一帧。
                break;
            }

            // 切出完整密文块并解密。
            let start = offset + CIPHER_LENGTH_PREFIX;
            let end = start + ct_len;
            let plaintext = self.cipher.decrypt(&self.cipher_buffer[start..end])?;
            self.plain_buffer.extend_from_slice(&plaintext);
            offset = end;
        }

        // 丢弃已消费的密文字节。
        if offset > 0 {
            self.cipher_buffer.drain(..offset);
        }

        // (3) 明文层切包。
        self.drain_messages()
    }

    /// 从明文累积缓冲反复切包，移除已消费字节。
    fn drain_messages(&mut self) -> Result<Vec<ProxyMessage>> {
        let mut out = Vec::new();
        // 反复切出完整消息；不足一条完整消息（try_decode_one 返回 None）时停止，等更多字节。
        while let Some((msg, consumed)) = proxy_proto::try_decode_one(&self.plain_buffer)? {
            out.push(msg);
            self.plain_buffer.drain(..consumed);
        }
        Ok(out)
    }

    /// 当前明文缓冲中尚未切出的字节数（用于测试 / 诊断）。
    pub fn buffered_len(&self) -> usize {
        self.plain_buffer.len()
    }

    /// 当前密文缓冲中尚未凑成完整块的字节数（用于测试 / 诊断）。
    pub fn cipher_buffered_len(&self) -> usize {
        self.cipher_buffer.len()
    }
}

/// 单调递增的 requestId 生成器（对齐 Java 侧用全局自增 id 标识一次代理请求）。
#[derive(Debug, Default)]
pub struct RequestIdGen {
    next: AtomicI64,
}

impl RequestIdGen {
    /// 构造，从 1 开始（0 通常保留作无效/默认值）。
    pub fn new() -> Self {
        Self {
            next: AtomicI64::new(1),
        }
    }

    /// 取下一个 requestId。
    pub fn next_id(&self) -> i64 {
        self.next.fetch_add(1, Ordering::Relaxed)
    }
}

/// 出站连接 —— 一条到 proxy-remote 的 HTTP/2 连接，承载多路 stream。
///
/// 通过 [`OutboundConnection::handshake`] 在「已 protect 并 connect」的 IO 流上完成 h2 握手。
/// `cipher` 用于该连接上所有 stream 的加解密。
pub struct OutboundConnection {
    send_request: h2::client::SendRequest<Bytes>,
    cipher: Cipher,
    req_id_gen: Arc<RequestIdGen>,
    config: OutboundConfig,
}

impl OutboundConnection {
    /// 在已建立并 protect 的 IO 流上完成 h2 客户端握手。
    ///
    /// `io` 应是**已 protect 后 connect 成功**的 TCP 流（或其上的 TLS 流）。把 TCP 建立
    /// 与握手分离，便于单测注入内存管道（`tokio::io::duplex`）而不触网。
    ///
    /// 生产侧由上层负责：调用 [`SocketProtector::protect`] → `TcpStream::connect`
    /// →（可选 TLS）→ 传入本函数。
    pub async fn handshake<T>(io: T, cipher: Cipher, config: OutboundConfig) -> Result<Self>
    where
        T: AsyncRead + AsyncWrite + Send + Unpin + 'static,
    {
        let (send_request, connection) = h2::client::Builder::new()
            .initial_window_size(H2_STREAM_WINDOW)
            .initial_connection_window_size(H2_CONNECTION_WINDOW)
            .handshake(io)
            .await
            .map_err(|e| CoreError::Protocol(format!("h2 handshake failed: {e}")))?;

        // h2 的连接驱动 future 必须被 spawn 持续 poll，否则收发都不前进。
        tokio::spawn(async move {
            if let Err(e) = connection.await {
                tracing::warn!("h2 connection driver ended: {e}");
            }
        });

        Ok(Self {
            send_request,
            cipher,
            req_id_gen: Arc::new(RequestIdGen::new()),
            config,
        })
    }

    /// 在本连接上开启一个新的代理 stream，并发送首帧 HEADERS（不加密）+ 首个
    /// CONNECT 消息（加密 DATA 帧），对齐 Java 客户端建流流程。
    ///
    /// 返回 [`OutboundStream`]，后续用它收发该 stream 上的 [`ProxyMessage`]。
    pub async fn open_proxy_stream(&mut self, host: &str, port: u16) -> Result<OutboundStream> {
        // 等待连接就绪（h2 流控 / SETTINGS）。
        let mut send_request = self
            .send_request
            .clone()
            .ready()
            .await
            .map_err(|e| CoreError::Protocol(format!("h2 not ready: {e}")))?;

        // 首帧 HEADERS（明文）：POST /proxy，content-type=application/octet-stream，end_stream=false。
        let request = http::Request::builder()
            .method(http::Method::POST)
            .uri("/proxy")
            .header("content-type", "application/octet-stream")
            .body(())
            .map_err(|e| CoreError::Protocol(format!("build h2 request failed: {e}")))?;

        let (response_fut, mut send_stream) = send_request
            .send_request(request, false)
            .map_err(|e| CoreError::Protocol(format!("send_request failed: {e}")))?;

        // 首个 CONNECT 消息（加密 DATA 帧）。
        let request_id = self.req_id_gen.next_id();
        let connect_msg = ProxyMessage::connect(request_id, host, port);
        let frame = encode_encrypted_frame(&self.cipher, &connect_msg)?;
        send_stream
            .send_data(Bytes::from(frame), false)
            .map_err(|e| CoreError::Protocol(format!("send CONNECT data failed: {e}")))?;

        // 等待响应 HEADERS，拿到入站 body 流。
        let response = response_fut
            .await
            .map_err(|e| CoreError::Protocol(format!("await h2 response failed: {e}")))?;
        let recv_stream = response.into_body();

        Ok(OutboundStream {
            request_id,
            cipher: self.cipher.clone(),
            send_stream,
            recv_stream,
            reassembler: InboundReassembler::new(self.cipher.clone()),
            pending: Vec::new(),
        })
    }

    /// 返回本连接所用配置（只读）。
    pub fn config(&self) -> &OutboundConfig {
        &self.config
    }
}

/// 一条代理 stream —— 对一个被代理的目标连接（host:port）的双向通道。
///
/// 发送：业务负载 → 包成 DATA `ProxyMessage` → 加密 → 一个 h2 DATA 帧。
/// 接收：h2 DATA 帧 → 解密 → 切包 → DATA `ProxyMessage` → 取出业务负载。
pub struct OutboundStream {
    request_id: i64,
    cipher: Cipher,
    send_stream: h2::SendStream<Bytes>,
    recv_stream: h2::RecvStream,
    reassembler: InboundReassembler,
    /// 已解密但尚未消费的消息（由 wait_for_connect_response 存入，
    /// recv_messages 先出队这里再读网络，避免 CONNECT_RESPONSE 之外的 DATA 丢失）。
    pending: Vec<ProxyMessage>,
}

impl OutboundStream {
    /// 本 stream 的 requestId。
    pub fn request_id(&self) -> i64 {
        self.request_id
    }

    /// 发送一段业务数据（包成 DATA 类型 ProxyMessage，加密为一个 h2 DATA 帧）。
    pub fn send_payload(&mut self, payload: &[u8]) -> Result<()> {
        let msg = ProxyMessage::data(self.request_id, payload);
        let frame = encode_encrypted_frame(&self.cipher, &msg)?;
        self.send_stream
            .send_data(Bytes::from(frame), false)
            .map_err(|e| CoreError::Protocol(format!("send DATA failed: {e}")))?;
        Ok(())
    }

    /// 发送 DISCONNECT 并以 end_stream 关闭发送侧。
    pub fn send_disconnect(&mut self) -> Result<()> {
        let msg = ProxyMessage::disconnect(self.request_id);
        let frame = encode_encrypted_frame(&self.cipher, &msg)?;
        self.send_stream
            .send_data(Bytes::from(frame), true)
            .map_err(|e| CoreError::Protocol(format!("send DISCONNECT failed: {e}")))?;
        Ok(())
    }

    /// 读取下一批入站 [`ProxyMessage`]（驱动一个 h2 DATA 帧 → 解密 → 切包）。
    ///
    /// 先出队 [`self.pending`] 中由 CONNECT_RESPONSE 阶段预读的积压消息，
    /// 为空时才从 h2 stream 拉新帧。返回 `Ok(None)` 表示流已关闭。
    pub async fn recv_messages(&mut self) -> Result<Option<Vec<ProxyMessage>>> {
        if !self.pending.is_empty() {
            return Ok(Some(std::mem::take(&mut self.pending)));
        }
        match self.recv_stream.data().await {
            Some(Ok(chunk)) => {
                let len = chunk.len();
                let _ = self.recv_stream.flow_control().release_capacity(len);
                let msgs = self.reassembler.push_encrypted_frame(&chunk)?;
                Ok(Some(msgs))
            }
            Some(Err(e)) => {
                // RST_STREAM NO_ERROR = 服务端优雅关闭流（如 CONNECT 失败后），当作 EOF
                let msg = e.to_string();
                if msg.contains("stream no longer needed") || msg.contains("NO_ERROR") {
                    tracing::debug!("h2 stream reset gracefully (NO_ERROR), treating as EOF");
                    Ok(None)
                } else {
                    Err(CoreError::Protocol(format!("h2 recv error: {e}")))
                }
            }
            None => Ok(None),
        }
    }

    /// 等待并接收 CONNECT_RESPONSE（加密链路的初始双向握手）。
    ///
    /// 服务端在收到 CONNECT 后会发送一个 CONNECT_RESPONSE（status=200=OK）。
    /// 同一批帧中可能已携带目标服务器的 HTTP 响应 DATA——这些消息缓存到
    /// `self.pending`，由后续 `recv_messages` 取出，确保零数据丢失。
    ///
    /// 返回 `Ok(())` 表示服务端成功连接目标，可以进入双向数据拷贝。
    /// 返回 `Err(...)` 表示 CONNECT 失败或协议异常。
    pub async fn wait_for_connect_response(&mut self) -> Result<()> {
        loop {
            // 先检查 pending（递归场景下不应出现，但安全处理）
            let pending = std::mem::take(&mut self.pending);
            let msgs = if !pending.is_empty() {
                pending
            } else {
                match self.recv_messages().await? {
                    Some(msgs) => msgs,
                    None => {
                        return Err(CoreError::Protocol(
                            "CONNECT 响应阶段流被关闭（服务端可能未连通目标）".to_string(),
                        ));
                    }
                }
            };
            let mut extra: Vec<ProxyMessage> = Vec::new();
            let mut found = false;
            for m in msgs {
                match m.type_ {
                    MessageType::ConnectResponse => {
                        found = true;
                        let ok = m.status == 200 || m.status == 0;
                        if ok {
                            tracing::info!(
                                "CONNECT_RESPONSE OK: host={}, port={}, requestId={}",
                                m.host, m.port, m.request_id
                            );
                        } else {
                            let reason = match m.status {
                                244 | 152 => "proxy-remote 无法连接目标（目标不可达/超时）",
                                _ => "未知错误",
                            };
                            return Err(CoreError::Protocol(format!(
                                "CONNECT {}:{} 失败: {} (status={})",
                                m.host, m.port, reason, m.status
                            )));
                        }
                    }
                    MessageType::Disconnect => {
                        return Err(CoreError::Protocol(format!(
                            "CONNECT 阶段收到 DISCONNECT: host={}, port={}",
                            m.host, m.port
                        )));
                    }
                    _ => {
                        extra.push(m);
                    }
                }
            }
            if found {
                // 将 CONNECT_RESPONSE 之外的消息（如目标服务器的 DATA）存入 pending，
                // 避免浏览器的 HTTP 响应被丢弃导致页面永远加载不出来。
                self.pending = extra;
                return Ok(());
            }
            // 未找到 CONNECT_RESPONSE，继续读取下一批帧
        }
    }
}

/// 在 [`OutboundStream`] 与本地业务连接间做双向拷贝（A6 完整版）。
///
/// 该函数把「本地 TCP（被代理的应用连接）」与「远端代理 stream」桥接：
/// - **首先等待 CONNECT_RESPONSE**，如果服务端连接目标失败则立即返回错误；
/// - 本地读到的字节 → `send_payload` 发往远端；
/// - 远端 `recv_messages` 解出的 DATA 负载 → 写回本地。
pub async fn proxy_via_remote<L>(
    mut stream: OutboundStream,
    mut local: L,
    read_buf_size: usize,
) -> Result<()>
where
    L: AsyncRead + AsyncWrite + Unpin,
{
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // 先等待 CONNECT_RESPONSE：服务端可能建连失败（目标不可达 / 超时等），
    // 若失败则将明确的错误原因抛给上层，由调度器决定重试或通知栈关断。
    stream.wait_for_connect_response().await?;

    let mut buf = vec![0u8; read_buf_size.max(1)];
    let mut total_up: u64 = 0;
    let mut total_down: u64 = 0;
    let mut down_pkts: u64 = 0;
    let mut pending_up: Vec<u8> = Vec::new();
    let start = std::time::Instant::now();
    let mut last_log = std::time::Instant::now();
    loop {
        tokio::select! {
            read = local.read(&mut buf) => {
                let n = read.map_err(CoreError::Io)?;
                if n == 0 {
                    // 发送残留在缓冲区的数据
                    if !pending_up.is_empty() {
                        stream.send_payload(&pending_up)?;
                    }
                    stream.send_disconnect()?;
                    tracing::info!(total_up, total_down, down_pkts, elapsed_ms = start.elapsed().as_millis(), "proxy 上行 EOF");
                    break;
                }
                total_up += n as u64;
                pending_up.extend_from_slice(&buf[..n]);

                // 若是 TLS ClientHello，可能跨越多个 TCP 段。将 TCP 段拼接成
                // 完整 TLS 记录后再一起发出，避免服务器收到不完整 ClientHello
                // 时立即返回 TLS Alert (unexpected_message)，导致后续段被丢弃。
                let ready = if pending_up.len() >= 5 && pending_up[0] == 0x16 {
                    let rec_len = u16::from_be_bytes([pending_up[3], pending_up[4]]) as usize;
                    pending_up.len() >= 5 + rec_len
                } else {
                    true // 非 TLS 或短帧，立即发送
                };

                if ready {
                    if total_up == pending_up.len() as u64 {
                        let hex = pending_up.iter().take(16)
                            .map(|b| format!("{b:02x}"))
                            .collect::<Vec<_>>().join("");
                        tracing::info!(len = pending_up.len(), first_hex = %hex, "proxy 上行(拼接后发出)");
                    }
                    stream.send_payload(&pending_up)?;
                    pending_up.clear();
                }
            }
            recv = stream.recv_messages() => {
                match recv? {
                    Some(msgs) => {
                        for m in msgs {
                            match m.type_ {
                                MessageType::Data => {
                                    if total_down == 0 {
                                        let hex = m.data.iter().take(16)
                                            .map(|b| format!("{b:02x}"))
                                            .collect::<Vec<_>>().join("");
                                        tracing::info!(data_len = m.data.len(), first_hex = %hex, "proxy 首个下行");
                                    }
                                    total_down += m.data.len() as u64;
                                    down_pkts += 1;
                                    local.write_all(&m.data).await.map_err(CoreError::Io)?;
                                }
                                MessageType::Disconnect => {
                                    tracing::info!(total_up, total_down, down_pkts, "proxy 远端 DISCONNECT，正常结束");
                                    return Ok(());
                                }
                                _ => {}
                            }
                        }
                        if last_log.elapsed() > std::time::Duration::from_secs(1) {
                            tracing::info!(total_up, total_down, down_pkts, "proxy 数据流活跃");
                            last_log = std::time::Instant::now();
                        }
                    }
                    None => {
                        tracing::info!(total_up, total_down, down_pkts, elapsed_ms = start.elapsed().as_millis(), "proxy 远端 EOF");
                        break;
                    }
                }
            }
        }
    }
    Ok(())
}

/// 便捷封装：spawn 一个驱动 [`proxy_via_remote`] 的任务（保留接口，给上层接线用）。
pub fn spawn_proxy<L>(stream: OutboundStream, local: L, read_buf_size: usize)
where
    L: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    tokio::spawn(async move {
        if let Err(e) = proxy_via_remote(stream, local, read_buf_size).await {
            tracing::warn!("proxy_via_remote ended with error: {e}");
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_cipher() -> Cipher {
        Cipher::new(b"unit-test-key-please-change-1234").unwrap()
    }

    #[test]
    fn noop_protector_always_true() {
        assert!(NoopProtector.protect(7));
    }

    #[test]
    fn request_id_gen_is_monotonic() {
        let g = RequestIdGen::new();
        let a = g.next_id();
        let b = g.next_id();
        let c = g.next_id();
        assert_eq!(a, 1);
        assert_eq!(b, 2);
        assert_eq!(c, 3);
        assert!(a < b && b < c);
    }

    #[test]
    fn encode_encrypted_frame_is_decryptable() {
        let cipher = test_cipher();
        let msg = ProxyMessage::connect(42, "example.com", 443);
        let frame = encode_encrypted_frame(&cipher, &msg).unwrap();
        // 加密帧 = 4字节长度前缀 + nonce(12) + ct + tag(16)，长度 = 明文 + 4 + 28。
        let plaintext = msg.encode();
        assert_eq!(
            frame.len(),
            CIPHER_LENGTH_PREFIX + plaintext.len() + 12 + 16
        );
        // 校验长度前缀 == 后续密文块长度。
        let ct_len = u32::from_be_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize;
        assert_eq!(ct_len, plaintext.len() + 12 + 16);
        assert_eq!(frame.len(), CIPHER_LENGTH_PREFIX + ct_len);
        // 剥掉长度前缀后解密还原应等于原明文。
        let restored = cipher.decrypt(&frame[CIPHER_LENGTH_PREFIX..]).unwrap();
        assert_eq!(restored, plaintext);
        // 再过 proxy_proto 解码应还原原消息。
        let decoded = ProxyMessage::decode(&restored).unwrap();
        assert_eq!(decoded, msg);
    }

    #[test]
    fn reassembler_single_frame_one_message() {
        let cipher = test_cipher();
        let mut re = InboundReassembler::new(cipher.clone());
        let msg = ProxyMessage::data(1, b"hello world");
        let frame = encode_encrypted_frame(&cipher, &msg).unwrap();
        let got = re.push_encrypted_frame(&frame).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0], msg);
        assert_eq!(re.buffered_len(), 0);
    }

    /// 把裸密文块包装成带 4 字节大端长度前缀的线上帧格式。
    fn frame_with_prefix(ciphertext: &[u8]) -> Vec<u8> {
        let mut framed = Vec::with_capacity(CIPHER_LENGTH_PREFIX + ciphertext.len());
        framed.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
        framed.extend_from_slice(ciphertext);
        framed
    }

    #[test]
    fn reassembler_multiple_messages_in_one_frame() {
        // 同一加密块内含多条 ProxyMessage（明文拼接后整体加密）。
        let cipher = test_cipher();
        let m1 = ProxyMessage::data(1, b"first");
        let m2 = ProxyMessage::connect(1, "a.com", 80);
        let m3 = ProxyMessage::disconnect(1);
        let mut plaintext = Vec::new();
        plaintext.extend_from_slice(&m1.encode());
        plaintext.extend_from_slice(&m2.encode());
        plaintext.extend_from_slice(&m3.encode());
        // 整体加密为一个密文块，再加 4 字节长度前缀构成线上帧。
        let frame = frame_with_prefix(&cipher.encrypt(&plaintext).unwrap());

        let mut re = InboundReassembler::new(cipher.clone());
        let got = re.push_encrypted_frame(&frame).unwrap();
        assert_eq!(got, vec![m1, m2, m3]);
        assert_eq!(re.buffered_len(), 0);
        assert_eq!(re.cipher_buffered_len(), 0);
    }

    #[test]
    fn reassembler_ciphertext_block_split_across_two_frames() {
        // 关键场景：单个密文块（含 4 字节长度前缀）在 HTTP/2 传输中被
        // 重新切分成两个 DATA 帧。接收侧必须在密文层做字节级累积，
        // 集齐整块后再解密。这正是修复前会触发 Poly1305 tag 失败的场景。
        let cipher = test_cipher();
        let msg = ProxyMessage::data(7, b"a-reasonably-long-payload-spanning-frames");
        // 一条消息整体加密成一个密文块，加 4 字节长度前缀。
        let frame = frame_with_prefix(&cipher.encrypt(&msg.encode()).unwrap());
        let split = frame.len() / 2;
        let (part1, part2) = frame.split_at(split);

        let mut re = InboundReassembler::new(cipher.clone());
        // 第一帧只到一半 → 密文块不完整，无法解密，产出 0 条，密文缓冲非空。
        let got1 = re.push_encrypted_frame(part1).unwrap();
        assert!(got1.is_empty());
        assert_eq!(re.cipher_buffered_len(), split);
        assert_eq!(re.buffered_len(), 0);
        // 第二帧补齐整块 → 解密成功，切出完整消息，两级缓冲均清空。
        let got2 = re.push_encrypted_frame(part2).unwrap();
        assert_eq!(got2, vec![msg]);
        assert_eq!(re.cipher_buffered_len(), 0);
        assert_eq!(re.buffered_len(), 0);
    }

    #[test]
    fn reassembler_two_blocks_coalesced_in_one_frame() {
        // 两个独立密文块（各带长度前缀）在一个 DATA 帧里被合并送达。
        let cipher = test_cipher();
        let m1 = ProxyMessage::data(1, b"alpha");
        let m2 = ProxyMessage::data(2, b"bravo");
        let mut frame = encode_encrypted_frame(&cipher, &m1).unwrap();
        frame.extend_from_slice(&encode_encrypted_frame(&cipher, &m2).unwrap());

        let mut re = InboundReassembler::new(cipher.clone());
        let got = re.push_encrypted_frame(&frame).unwrap();
        assert_eq!(got, vec![m1, m2]);
        assert_eq!(re.cipher_buffered_len(), 0);
        assert_eq!(re.buffered_len(), 0);
    }

    #[test]
    fn reassembler_rejects_tampered_frame() {
        let cipher = test_cipher();
        let msg = ProxyMessage::data(1, b"payload");
        let mut frame = encode_encrypted_frame(&cipher, &msg).unwrap();
        // 篡改密文区（跳过 4 字节长度前缀）→ 解密时 Poly1305 tag 校验失败。
        let mid = CIPHER_LENGTH_PREFIX + (frame.len() - CIPHER_LENGTH_PREFIX) / 2;
        frame[mid] ^= 0xFF;
        let mut re = InboundReassembler::new(cipher);
        assert!(re.push_encrypted_frame(&frame).is_err());
    }

    #[test]
    fn config_roundtrip() {
        let cfg = OutboundConfig {
            server_host: "1.2.3.4".to_string(),
            server_port: 8443,
            tls: false,
        };
        assert_eq!(cfg.server_port, 8443);
        assert!(!cfg.tls);
    }
}
