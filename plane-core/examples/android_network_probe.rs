//! Run as the Android shell UID, which is included in the VPN routes. Running
//! this inside the VPN app itself would bypass its own TUN and give a false pass.
//! Prints timings and HTTP status only, never response bodies or credentials.
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn probe(host: &str, port: u16) -> Result<serde_json::Value, Box<dyn std::error::Error>> {
    let start = Instant::now();
    let address: SocketAddr = (host, port)
        .to_socket_addrs()?
        .find(|address| address.is_ipv4())
        .ok_or("no IPv4 DNS answer")?;
    let dns_ms = start.elapsed().as_millis();
    let mut tcp = TcpStream::connect_timeout(&address, Duration::from_secs(15))?;
    tcp.set_read_timeout(Some(Duration::from_secs(20)))?;
    tcp.set_write_timeout(Some(Duration::from_secs(20)))?;
    let tcp_ms = start.elapsed().as_millis();
    let request = format!(
        "GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n"
    );

    fn exchange(
        io: &mut impl ReadWrite,
        request: &[u8],
        start: Instant,
    ) -> std::io::Result<(u128, usize, String)> {
        io.write_all(request)?;
        io.flush()?;
        let mut buf = [0u8; 16384];
        let mut bytes = 0;
        let mut ttfb = None;
        let mut header = Vec::new();
        loop {
            let n = io.read(&mut buf)?;
            if n == 0 {
                break;
            }
            ttfb.get_or_insert_with(|| start.elapsed().as_millis());
            bytes += n;
            if !header.windows(2).any(|pair| pair == b"\r\n") && header.len() < 8192 {
                header.extend_from_slice(&buf[..n]);
            }
            if bytes >= 2 * 1024 * 1024 {
                break;
            }
        }
        Ok((
            ttfb.unwrap_or(0),
            bytes,
            String::from_utf8_lossy(&header)
                .lines()
                .next()
                .unwrap_or("<empty>")
                .to_string(),
        ))
    }
    let (ttfb_ms, bytes, status, tls_ms) = if port == 443 {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let mut connection =
            rustls::ClientConnection::new(Arc::new(config), host.to_string().try_into()?)?;
        while connection.is_handshaking() {
            connection.complete_io(&mut tcp)?;
        }
        let tls_ms = start.elapsed().as_millis();
        let mut tls = rustls::StreamOwned::new(connection, tcp);
        let (ttfb, bytes, status) = exchange(&mut tls, request.as_bytes(), start)?;
        (ttfb, bytes, status, Some(tls_ms))
    } else {
        let (ttfb, bytes, status) = exchange(&mut tcp, request.as_bytes(), start)?;
        (ttfb, bytes, status, None)
    };
    if !status.starts_with("HTTP/1.") {
        return Err(format!("invalid web response: {status}").into());
    }
    Ok(serde_json::json!({
        "host": host, "port": port, "resolved_ip": address.ip().to_string(), "status": status,
        "dns_ms": dns_ms, "tcp_ms": tcp_ms, "tls_ms": tls_ms, "ttfb_ms": ttfb_ms,
        "total_ms": start.elapsed().as_millis(), "bytes": bytes,
    }))
}

trait ReadWrite: Read + Write {}
impl<T: Read + Write> ReadWrite for T {}

fn main() {
    let host = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "example.com".into());
    let jobs: Vec<_> = [80, 443, 443, 443]
        .into_iter()
        .map(|port| {
            let host = host.clone();
            std::thread::spawn(move || match probe(&host, port) {
                Ok(result) => {
                    println!("{result}");
                    true
                }
                Err(error) => {
                    println!(
                        "{}",
                        serde_json::json!({"host": host, "port": port, "error": error.to_string()})
                    );
                    false
                }
            })
        })
        .collect();
    let success = jobs
        .into_iter()
        .map(|job| job.join().unwrap_or(false))
        .fold(true, |all, ok| all & ok);
    if !success {
        std::process::exit(1);
    }
}
