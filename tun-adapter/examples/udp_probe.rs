//! Unprivileged integration probe using the production TUN UDP packet/flow path.
#![allow(dead_code)]
#[path = "../src/config.rs"]
mod config;
#[path = "../src/direct.rs"]
mod direct;
#[path = "../src/error.rs"]
mod error;
#[path = "../src/fake_dns.rs"]
mod fake_dns;
#[path = "../src/route_policy.rs"]
mod route_policy;
#[path = "../src/router.rs"]
mod router;
#[path = "../src/udp.rs"]
mod udp;
use std::{net::SocketAddrV4, sync::Arc, time::Duration};
use tokio::sync::Mutex;
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let proxy = args.get(1).ok_or("SOCKS endpoint missing")?.parse()?;
    let host = args.get(2).ok_or("UDP host missing")?;
    let port = args.get(3).ok_or("UDP port missing")?.parse()?;
    let mut dns = fake_dns::FakeDnsEngine::new("198.18.0.0/15", 100);
    let ip = dns.allocate_ip(host);
    let (router, _) = route_policy::RoutePolicy::new(
        &config::RoutingConfig {
            default_action: "proxy".into(),
            rules: vec![],
        },
        None,
    )?;
    let mut manager = udp::UdpManager::new(
        Arc::new(Mutex::new(dns)),
        Arc::new(router),
        proxy,
        Arc::new(direct::DirectEgress::discover()?),
    );
    let key = udp::FlowKey {
        src: "192.168.1.10:53000".parse::<SocketAddrV4>()?,
        dst: SocketAddrV4::new(ip, port),
    };
    for length in [0, 1, 1472, 0, 1000, 17] {
        let payload = vec![length as u8; length];
        manager.handle(&udp::packet(key, &payload)).await;
        let bytes = tokio::time::timeout(Duration::from_secs(5), manager.responses.recv())
            .await?
            .ok_or("response queue closed")?;
        let reply = udp::parse(&bytes).ok_or("invalid response packet/checksum")?;
        if reply.key
            != (udp::FlowKey {
                src: key.dst,
                dst: key.src,
            })
            || reply.payload != payload
        {
            return Err("UDP tuple/payload mismatch".into());
        }
    }
    drop(manager);
    // Allow cancelled flow tasks to drop their control TCP connection before runtime shutdown.
    tokio::task::yield_now().await;
    println!("PASS: production TUN packet -> SOCKS -> encrypted tunnel -> UDP echo; FakeIP, empty, max, checksums");
    Ok(())
}
