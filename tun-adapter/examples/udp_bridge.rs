//! Routes a real application's UDP through production TUN IP/FakeIP handling without changing OS routes.
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
use std::{
    net::{SocketAddr, SocketAddrV4},
    sync::Arc,
    time::Duration,
};
use tokio::sync::Mutex;
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let proxy = args.get(1).ok_or("SOCKS endpoint required")?.parse()?;
    let host = args.get(2).ok_or("target host required")?;
    let port = args.get(3).ok_or("target port required")?.parse()?;
    let mut dns = fake_dns::FakeDnsEngine::new("198.18.0.0/15", 100);
    let fake_ip = dns.allocate_ip(host);
    let (policy, _) = route_policy::RoutePolicy::new(
        &config::RoutingConfig {
            default_action: "proxy".into(),
            rules: vec![],
        },
        None,
    )?;
    let mut manager = udp::UdpManager::new(
        Arc::new(Mutex::new(dns)),
        Arc::new(policy),
        proxy,
        Arc::new(direct::DirectEgress::discover()?),
    );
    let app = tokio::net::UdpSocket::bind("127.0.0.1:0").await?;
    println!("READY {} {}", app.local_addr()?, fake_ip);
    let mut buf = vec![0u8; 65535];
    let lifetime = tokio::time::sleep(Duration::from_secs(600));
    tokio::pin!(lifetime);
    loop {
        tokio::select! {
            request=app.recv_from(&mut buf)=> {
                let (n,peer)=request?;
                if n>udp::MAX_PAYLOAD {continue;}
                let SocketAddr::V4(src)=peer else {continue;};
                manager.handle(&udp::packet(udp::FlowKey{src,dst:SocketAddrV4::new(fake_ip,port)},&buf[..n])).await;
            },
            Some(response)=manager.responses.recv()=> {
                let response=udp::parse(&response).ok_or("invalid production IP response")?;
                if *response.key.src.ip()!=fake_ip || response.key.src.port()!=port || !response.key.dst.ip().is_loopback() {return Err("response tuple mismatch".into());}
                app.send_to(&response.payload,response.key.dst).await?;
            },
            _=&mut lifetime=>break,
            _=tokio::signal::ctrl_c()=>break,
        }
        manager.reap();
    }
    Ok(())
}
