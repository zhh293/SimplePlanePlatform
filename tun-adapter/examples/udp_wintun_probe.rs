//! Administrator-only Windows probe: four /32 routes on a dedicated adapter, no DNS/default-route changes.
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

#[cfg(not(windows))]
fn main() {
    eprintln!("This isolated Wintun probe requires Windows and administrator rights");
}

#[cfg(windows)]
fn powershell(script: &str) -> std::io::Result<String> {
    use std::os::windows::process::CommandExt;
    let output = std::process::Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .creation_flags(0x08000000)
        .output()?;
    if !output.status.success() {
        return Err(std::io::Error::other(
            String::from_utf8_lossy(&output.stderr).to_string(),
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}
#[cfg(windows)]
struct Routes {
    index: u32,
    prefixes: Vec<String>,
}
#[cfg(windows)]
impl Drop for Routes {
    fn drop(&mut self) {
        for prefix in &self.prefixes {
            let _=powershell(&format!("Get-NetRoute -InterfaceIndex {} -DestinationPrefix '{}' -ErrorAction SilentlyContinue | Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue",self.index,prefix));
        }
    }
}
#[cfg(windows)]
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::{net::Ipv4Addr, path::PathBuf, sync::Arc, time::Duration};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        sync::Mutex,
    };
    let args: Vec<String> = std::env::args().collect();
    let proxy = args.get(1).ok_or("SOCKS endpoint required")?.parse()?;
    let ready = PathBuf::from(args.get(2).ok_or("ready path required")?);
    let stop = PathBuf::from(args.get(3).ok_or("stop path required")?);
    let root = PathBuf::from(args.get(4).ok_or("workspace root required")?);
    if stop.exists() {
        return Ok(());
    }
    let name = format!("SP-UDP-Canary-{}", std::process::id());
    let mut dns = fake_dns::FakeDnsEngine::new("198.19.254.0/24", 64);
    let hosts = [
        "stun.l.google.com",
        "time.cloudflare.com",
        "cloudflare-quic.com",
        "127.0.0.1",
    ];
    let targets: Vec<_> = hosts
        .iter()
        .map(|host| ((*host).to_string(), dns.allocate_ip(host)))
        .collect();
    let egress = Arc::new(direct::DirectEgress::discover()?);
    let mut cfg = tun2::Configuration::default();
    cfg.tun_name(&name)
        // FakeDNS allocates .1 to the first target; Windows adds a /32 for the adapter itself.
        .address(Ipv4Addr::new(198, 19, 254, 250))
        .netmask(Ipv4Addr::new(255, 255, 255, 255))
        .destination(Ipv4Addr::new(198, 19, 254, 254))
        .mtu(1500)
        .up();
    cfg.platform_config(|platform| {
        platform.wintun_file(root.join("tun-adapter/wintun.dll").into_os_string())
    });
    let device = tun2::create_as_async(&cfg)?;
    let index: u32 = powershell(&format!(
        "$ErrorActionPreference='Stop';(Get-NetAdapter -Name '{}').ifIndex",
        name
    ))?
    .parse()?;
    let mut routes = Routes {
        index,
        prefixes: Vec::new(),
    };
    for (_, ip) in &targets {
        let prefix = format!("{ip}/32");
        powershell(&format!("$ErrorActionPreference='Stop';if(Get-NetRoute -DestinationPrefix '{}' -ErrorAction SilentlyContinue){{throw 'Test prefix already in use'}}; New-NetRoute -DestinationPrefix '{}' -InterfaceIndex {} -NextHop '0.0.0.0' -PolicyStore ActiveStore -Confirm:$false | Out-Null",prefix,prefix,index))?;
        routes.prefixes.push(prefix);
    }
    let (policy, _) = route_policy::RoutePolicy::new(
        &config::RoutingConfig {
            default_action: "proxy".into(),
            rules: vec![],
        },
        None,
    )?;
    let mut manager =
        udp::UdpManager::new(Arc::new(Mutex::new(dns)), Arc::new(policy), proxy, egress);
    let mapping = targets
        .iter()
        .map(|(host, ip)| format!("{host:?}:\"{ip}\""))
        .collect::<Vec<_>>()
        .join(",");
    std::fs::write(
        &ready,
        format!(
            "{{\"pid\":{},\"interface_index\":{},\"name\":{:?},\"targets\":{{{}}}}}",
            std::process::id(),
            index,
            name,
            mapping
        ),
    )?;
    let (mut reader, mut writer) = tokio::io::split(device);
    let mut bytes = vec![0; 65535];
    let until = tokio::time::sleep(Duration::from_secs(180));
    tokio::pin!(until);
    let mut timer = tokio::time::interval(Duration::from_millis(100));
    loop {
        tokio::select! {
            result=reader.read(&mut bytes)=>{let n=result?;if n==0{break;}manager.handle(&bytes[..n]).await;},
            Some(reply)=manager.responses.recv()=>writer.write_all(&reply).await?,
            _=timer.tick()=>{if stop.exists(){break;}manager.reap();},
            _=&mut until=>break,
        }
    }
    drop(manager);
    drop(routes);
    drop(reader);
    drop(writer);
    Ok(())
}
