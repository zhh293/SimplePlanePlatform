//! Select the wire transport without changing routing, accounting or TUN handling.
use std::sync::Arc;

use tokio::io::{AsyncRead, AsyncWrite};

use crate::error::Result;
use crate::outbound;
use crate::outbound_h3;
use crate::proxy_proto::ProxyMessage;
use crate::stats::CoreStats;

#[derive(Clone)]
pub enum ProxyConnection {
    Http2(outbound::OutboundConnection),
    Http3(outbound_h3::OutboundConnection),
}

impl ProxyConnection {
    pub async fn open_proxy_stream(&mut self, host: &str, port: u16) -> Result<ProxyStream> {
        match self {
            Self::Http2(connection) => connection
                .open_proxy_stream(host, port)
                .await
                .map(ProxyStream::Http2),
            Self::Http3(connection) => connection
                .open_proxy_stream(host, port)
                .await
                .map(|stream| ProxyStream::Http3(Box::new(stream))),
        }
    }
}

pub enum ProxyStream {
    Http2(outbound::OutboundStream),
    Http3(Box<outbound_h3::OutboundStream>),
}

impl ProxyStream {
    pub async fn send_payload(&mut self, payload: &[u8]) -> Result<()> {
        match self {
            Self::Http2(stream) => stream.send_payload(payload),
            Self::Http3(stream) => stream.send_payload(payload).await,
        }
    }

    pub async fn recv_messages(&mut self) -> Result<Option<Vec<ProxyMessage>>> {
        match self {
            Self::Http2(stream) => stream.recv_messages().await,
            Self::Http3(stream) => stream.recv_messages().await,
        }
    }

    pub async fn send_disconnect(&mut self) -> Result<()> {
        match self {
            Self::Http2(stream) => stream.send_disconnect(),
            Self::Http3(stream) => stream.send_disconnect().await,
        }
    }
}

pub async fn proxy_via_remote_counted<L>(
    stream: ProxyStream,
    local: L,
    read_buf_size: usize,
    stats: Option<Arc<CoreStats>>,
) -> Result<()>
where
    L: AsyncRead + AsyncWrite + Unpin,
{
    match stream {
        ProxyStream::Http2(stream) => {
            outbound::proxy_via_remote_counted(stream, local, read_buf_size, stats).await
        }
        ProxyStream::Http3(stream) => {
            outbound_h3::proxy_via_remote_counted(*stream, local, read_buf_size, stats).await
        }
    }
}
