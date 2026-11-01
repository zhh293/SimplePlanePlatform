//! Run as adb shell (a UID included in the VPN), against a controlled UDP echo target.
use std::net::{ToSocketAddrs, UdpSocket};
use std::time::Duration;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let target = std::env::args()
        .nth(1)
        .ok_or("usage: android_udp_probe <echo-host:port>")?;
    let addr = target
        .to_socket_addrs()?
        .find(|a| a.is_ipv4())
        .ok_or("no IPv4 target")?;
    println!("UDP_PROBE_TARGET resolved={addr}; verify this address and route in VPN logs");
    let mut threads = Vec::new();
    for association in 0..8 {
        threads.push(std::thread::spawn(move || -> Result<usize, String> {
            let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
            socket
                .set_read_timeout(Some(Duration::from_secs(15)))
                .map_err(|e| e.to_string())?;
            socket.connect(addr).map_err(|e| e.to_string())?;
            let mut count = 0;
            for size in [1, 0, 127, 1472] {
                let payload: Vec<u8> = (0..size)
                    .map(|i| ((i + association * 17) % 251) as u8)
                    .collect();
                socket.send(&payload).map_err(|e| e.to_string())?;
                let mut buffer = [0u8; 65535];
                let n = socket.recv(&mut buffer).map_err(|e| e.to_string())?;
                if buffer[..n] != payload {
                    return Err(format!(
                        "association={association} size={size} reply mismatch"
                    ));
                }
                count += 1;
            }
            Ok(count)
        }));
    }
    let mut count = 0;
    for thread in threads {
        count += thread
            .join()
            .map_err(|_| "probe thread panicked")?
            .map_err(std::io::Error::other)?;
    }
    println!(
        "ANDROID_UDP_DEVICE_PROBE_OK associations=8 datagrams={count} empty=true max_payload=1472"
    );
    Ok(())
}
