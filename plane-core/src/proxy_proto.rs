//! A5.2 —— ProxyMessage 编解码（**对齐 Java `proxy-common/ProxyMessage.java` +
//! `proxy-transport-netty/ProxyCodec.java` / `ProxyMessageDecoder.java`**）。
//!
//! 帧格式（固定头 28 字节，全部大端 / big-endian，见任务文档 0.4-A）：
//!
//! ```text
//! 偏移      长度      字段        说明
//! 0         1         Type        MessageType 的 ordinal
//! 1         1         Status      响应状态码（请求侧填 0）
//! 2         8         RequestId   i64
//! 10        8         StreamId    i64
//! 18        2         HostLen     u16，Host 的 UTF-8 字节数
//! 20        HostLen   Host        变长，UTF-8
//! 20+HL     4         Port        i32
//! 24+HL     4         DataLen     i32
//! 28+HL     DataLen   Data        变长
//! ```
//!
//! 固定头 = 1+1+8+8+2+4+4 = 28（不含 Host）。Port/DataLen 用 `putInt`（**各 4 字节**），
//! HostLen 是 **2 字节**。这些都不得臆测，已逐字段对照 `ProxyCodec.encode/decode` 复刻。

use crate::error::{CoreError, Result};

/// 固定头长度（字节），对齐 Java `FIXED_HEADER_SIZE`。
pub const FIXED_HEADER_SIZE: usize = 28;

/// 消息类型 —— 判别值（u8）必须与 Java `MessageType` 的 ordinal 一致。
///
/// Java `enum MessageType { CONNECT, CONNECT_RESPONSE, DATA, DISCONNECT,
/// HEARTBEAT_REQUEST, HEARTBEAT_RESPONSE }`，ordinal 从 0 递增。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MessageType {
    /// 建立隧道连接。
    Connect = 0,
    /// 连接响应。
    ConnectResponse = 1,
    /// 数据传输。
    Data = 2,
    /// 断开连接。
    Disconnect = 3,
    /// 心跳请求。
    HeartbeatRequest = 4,
    /// 心跳响应。
    HeartbeatResponse = 5,
}

impl MessageType {
    /// 由 ordinal 还原类型。越界返回 `None`（对齐 Java decode 里 `typeOrdinal < types.length` 的判断，
    /// Java 越界时 type 为 null，这里用 `None` 表达）。
    pub fn from_ordinal(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::Connect),
            1 => Some(Self::ConnectResponse),
            2 => Some(Self::Data),
            3 => Some(Self::Disconnect),
            4 => Some(Self::HeartbeatRequest),
            5 => Some(Self::HeartbeatResponse),
            _ => None,
        }
    }
}

/// 代理消息（与 Java `ProxyMessage` 线格式对齐的子集）。
///
/// 仅保留参与线格式编解码的字段；Java 的 `message`/`attachments` 不入帧，本结构不含。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyMessage {
    /// 消息类型。
    pub type_: MessageType,
    /// 响应状态码（请求侧填 0）。
    pub status: u8,
    /// 请求 ID（i64）。
    pub request_id: i64,
    /// 流 ID（i64）。
    pub stream_id: i64,
    /// 目标主机（UTF-8；无则为空串）。
    pub host: String,
    /// 目标端口（i32）。
    pub port: i32,
    /// 负载数据（无则为空）。
    pub data: Vec<u8>,
}

impl ProxyMessage {
    /// 构造 CONNECT 消息（请求侧）。
    pub fn connect(request_id: i64, host: &str, port: u16) -> Self {
        Self {
            type_: MessageType::Connect,
            status: 0,
            request_id,
            stream_id: 0,
            host: host.to_string(),
            port: port as i32,
            data: Vec::new(),
        }
    }

    /// 构造 DATA 消息（请求侧）。
    pub fn data(request_id: i64, payload: &[u8]) -> Self {
        Self {
            type_: MessageType::Data,
            status: 0,
            request_id,
            stream_id: 0,
            host: String::new(),
            port: 0,
            data: payload.to_vec(),
        }
    }

    /// 构造 DISCONNECT 消息（请求侧）。
    pub fn disconnect(request_id: i64) -> Self {
        Self {
            type_: MessageType::Disconnect,
            status: 0,
            request_id,
            stream_id: 0,
            host: String::new(),
            port: 0,
            data: Vec::new(),
        }
    }

    /// 构造 HEARTBEAT_REQUEST 消息。
    pub fn heartbeat_request(request_id: i64) -> Self {
        Self {
            type_: MessageType::HeartbeatRequest,
            status: 0,
            request_id,
            stream_id: 0,
            host: String::new(),
            port: 0,
            data: Vec::new(),
        }
    }

    /// 序列化为线字节（28B 大端头 + host + data），对齐 Java `ProxyCodec.encode`。
    pub fn encode(&self) -> Vec<u8> {
        let host_bytes = self.host.as_bytes();
        let host_len = host_bytes.len();
        let data_len = self.data.len();
        let total = FIXED_HEADER_SIZE + host_len + data_len;

        let mut buf = Vec::with_capacity(total);
        buf.push(self.type_ as u8); // Type (1)
        buf.push(self.status); // Status (1)
        buf.extend_from_slice(&self.request_id.to_be_bytes()); // RequestId (8, big-endian)
        buf.extend_from_slice(&self.stream_id.to_be_bytes()); // StreamId (8, big-endian)
        buf.extend_from_slice(&(host_len as u16).to_be_bytes()); // HostLen (2, big-endian)
        buf.extend_from_slice(host_bytes); // Host
        buf.extend_from_slice(&self.port.to_be_bytes()); // Port (4, big-endian)
        buf.extend_from_slice(&(data_len as i32).to_be_bytes()); // DataLen (4, big-endian)
        buf.extend_from_slice(&self.data); // Data
        buf
    }

    /// 从完整一条消息的字节解码，对齐 Java `ProxyCodec.decode`。
    ///
    /// 要求 `bytes` 恰为一条完整消息（长度 == 28 + HostLen + DataLen）。
    /// 长度不足或越界返回 [`CoreError::Protocol`]。跨帧累积切包请用 [`try_decode_one`]。
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let (msg, consumed) = try_decode_one(bytes)?.ok_or_else(|| {
            CoreError::Protocol(format!(
                "Data too short to decode: {} bytes, minimum {}",
                bytes.len(),
                FIXED_HEADER_SIZE
            ))
        })?;
        if consumed != bytes.len() {
            return Err(CoreError::Protocol(format!(
                "trailing bytes after one message: consumed {}, total {}",
                consumed,
                bytes.len()
            )));
        }
        Ok(msg)
    }
}

/// 尝试从缓冲区切出**一条**完整 ProxyMessage（跨帧累积切包），对齐 Java
/// `ProxyMessageDecoder.channelRead` 的切分逻辑：
///
/// 1. 不足 28 字节 → 返回 `Ok(None)`（继续等更多字节）。
/// 2. 读 offset 18 处的 HostLen（u16）→ 不足 `28 + HostLen` → `Ok(None)`。
/// 3. 读 offset `24 + HostLen` 处的 DataLen（i32）→ 不足 `28 + HostLen + DataLen` → `Ok(None)`。
/// 4. 足够 → 解析并返回 `Ok(Some((msg, consumed)))`，`consumed` = 整条消息字节数。
///
/// 返回的 `consumed` 供调用方从缓冲区移除已消费部分。DataLen 为负等非法值返回
/// [`CoreError::Protocol`]。
pub fn try_decode_one(buf: &[u8]) -> Result<Option<(ProxyMessage, usize)>> {
    if buf.len() < FIXED_HEADER_SIZE {
        return Ok(None);
    }

    // HostLen 在 offset 18（u16, big-endian）。
    let host_len = u16::from_be_bytes([buf[18], buf[19]]) as usize;
    let header_total = FIXED_HEADER_SIZE + host_len;
    if buf.len() < header_total {
        return Ok(None);
    }

    // DataLen 在 offset 24 + host_len（i32, big-endian）。
    let data_len_off = 24 + host_len;
    let data_len_i32 = i32::from_be_bytes([
        buf[data_len_off],
        buf[data_len_off + 1],
        buf[data_len_off + 2],
        buf[data_len_off + 3],
    ]);
    if data_len_i32 < 0 {
        return Err(CoreError::Protocol(format!(
            "negative DataLen: {data_len_i32}"
        )));
    }
    let data_len = data_len_i32 as usize;
    let total = header_total + data_len;
    if buf.len() < total {
        return Ok(None);
    }

    // 解析各字段。
    let type_ordinal = buf[0];
    let type_ = MessageType::from_ordinal(type_ordinal).ok_or_else(|| {
        CoreError::Protocol(format!("unknown MessageType ordinal: {type_ordinal}"))
    })?;
    let status = buf[1];
    let request_id = i64::from_be_bytes(buf[2..10].try_into().expect("8 bytes"));
    let stream_id = i64::from_be_bytes(buf[10..18].try_into().expect("8 bytes"));

    let host = if host_len > 0 {
        String::from_utf8(buf[20..20 + host_len].to_vec())
            .map_err(|e| CoreError::Protocol(format!("invalid UTF-8 host: {e}")))?
    } else {
        String::new()
    };

    let port_off = 20 + host_len;
    let port = i32::from_be_bytes(
        buf[port_off..port_off + 4]
            .try_into()
            .expect("4 bytes for port"),
    );

    let data = if data_len > 0 {
        buf[header_total..total].to_vec()
    } else {
        Vec::new()
    };

    Ok(Some((
        ProxyMessage {
            type_,
            status,
            request_id,
            stream_id,
            host,
            port,
            data,
        },
        total,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_decode_roundtrip_connect() {
        let msg = ProxyMessage::connect(42, "example.com", 443);
        let bytes = msg.encode();
        // 头 28 + host 11
        assert_eq!(bytes.len(), FIXED_HEADER_SIZE + "example.com".len());
        let decoded = ProxyMessage::decode(&bytes).unwrap();
        assert_eq!(decoded, msg);
        assert_eq!(decoded.type_, MessageType::Connect);
        assert_eq!(decoded.port, 443);
        assert_eq!(decoded.request_id, 42);
    }

    #[test]
    fn encode_decode_roundtrip_data() {
        let payload = vec![1u8, 2, 3, 4, 5];
        let msg = ProxyMessage::data(7, &payload);
        let bytes = msg.encode();
        let decoded = ProxyMessage::decode(&bytes).unwrap();
        assert_eq!(decoded, msg);
        assert_eq!(decoded.data, payload);
        assert!(decoded.host.is_empty());
    }

    #[test]
    fn header_offsets_are_big_endian() {
        let msg = ProxyMessage {
            type_: MessageType::Data,
            status: 0,
            request_id: 0x0102_0304_0506_0708,
            stream_id: 0,
            host: String::new(),
            port: 0x1122_3344,
            data: vec![0xAA],
        };
        let b = msg.encode();
        assert_eq!(b[0], MessageType::Data as u8);
        // RequestId 大端，最高字节在前。
        assert_eq!(&b[2..10], &0x0102_0304_0506_0708i64.to_be_bytes());
        // HostLen 在 offset 18-19 = 0。
        assert_eq!(u16::from_be_bytes([b[18], b[19]]), 0);
        // Port 在 offset 20（host_len=0）。
        assert_eq!(&b[20..24], &0x1122_3344i32.to_be_bytes());
        // DataLen 在 offset 24 = 1。
        assert_eq!(i32::from_be_bytes([b[24], b[25], b[26], b[27]]), 1);
    }

    #[test]
    fn try_decode_one_partial_returns_none() {
        let msg = ProxyMessage::connect(1, "host", 80);
        let full = msg.encode();
        // 喂入不足 28 字节。
        assert!(try_decode_one(&full[..10]).unwrap().is_none());
        // 喂入足头但不足 host。
        assert!(try_decode_one(&full[..FIXED_HEADER_SIZE])
            .unwrap()
            .is_none());
    }

    #[test]
    fn try_decode_one_streamed_in_two_parts() {
        let m1 = ProxyMessage::data(1, b"first");
        let m2 = ProxyMessage::data(2, b"second-message");
        let mut stream = m1.encode();
        stream.extend_from_slice(&m2.encode());

        // 分两段喂入，逐条切出。
        let mut buf: Vec<u8> = Vec::new();
        let split = m1.encode().len() + 3; // 故意切在第二条中间
        buf.extend_from_slice(&stream[..split]);

        // 第一次：能切出 m1，剩余不足 m2。
        let (got1, consumed1) = try_decode_one(&buf).unwrap().unwrap();
        assert_eq!(got1, m1);
        buf.drain(..consumed1);
        assert!(try_decode_one(&buf).unwrap().is_none());

        // 补齐剩余字节，切出 m2。
        buf.extend_from_slice(&stream[split..]);
        let (got2, consumed2) = try_decode_one(&buf).unwrap().unwrap();
        assert_eq!(got2, m2);
        buf.drain(..consumed2);
        assert!(buf.is_empty());
    }

    #[test]
    fn try_decode_one_concatenated_messages() {
        let msgs = [
            ProxyMessage::connect(1, "a.com", 443),
            ProxyMessage::data(1, b"payload"),
            ProxyMessage::disconnect(1),
            ProxyMessage::heartbeat_request(99),
        ];
        let mut stream = Vec::new();
        for m in &msgs {
            stream.extend_from_slice(&m.encode());
        }
        let mut off = 0;
        for expected in &msgs {
            let (got, consumed) = try_decode_one(&stream[off..]).unwrap().unwrap();
            assert_eq!(&got, expected);
            off += consumed;
        }
        assert_eq!(off, stream.len());
    }

    #[test]
    fn decode_rejects_trailing_bytes() {
        let mut bytes = ProxyMessage::data(1, b"x").encode();
        bytes.push(0xFF); // 多一个字节
        assert!(ProxyMessage::decode(&bytes).is_err());
    }

    #[test]
    fn message_type_ordinals_match_java() {
        assert_eq!(MessageType::Connect as u8, 0);
        assert_eq!(MessageType::ConnectResponse as u8, 1);
        assert_eq!(MessageType::Data as u8, 2);
        assert_eq!(MessageType::Disconnect as u8, 3);
        assert_eq!(MessageType::HeartbeatRequest as u8, 4);
        assert_eq!(MessageType::HeartbeatResponse as u8, 5);
    }
}
