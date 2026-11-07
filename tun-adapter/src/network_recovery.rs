//! Network changes restart the session inside the elevated process, without UAC.
use std::time::Duration;

#[derive(Default)]
struct Changes {
    applied: Option<String>,
    candidate: Option<String>,
    count: u8,
    offline: u8,
}
impl Changes {
    fn observe(&mut self, line: &str) -> bool {
        if line == "offline" {
            self.offline = self.offline.saturating_add(1);
            self.candidate = None;
            self.count = 0;
            return false;
        }
        // Establish the baseline immediately: a change between the first two
        // polls must not silently become the initial network.
        if self.applied.is_none() {
            self.applied = Some(line.to_owned());
        }
        // Require two matching observations. A brief DHCP gap does not restart
        // a working session; a sustained outage also recovers on the same lease.
        if self.candidate.as_deref() == Some(line) {
            self.count = self.count.saturating_add(1);
        } else {
            self.candidate = Some(line.to_owned());
            self.count = 1;
        }
        if self.count < 2 {
            return false;
        }
        let changed = self.applied.as_deref().is_some_and(|old| old != line) || self.offline >= 2;
        self.applied = Some(line.to_owned());
        self.offline = 0;
        changed
    }
}

#[cfg(windows)]
pub async fn wait_for_change() {
    use std::process::Stdio;
    use tokio::io::{AsyncBufReadExt, BufReader};
    let mut changes = Changes::default();
    loop {
        // One persistent worker, no per-request or per-poll process creation.
        let script = format!(
            "& {{\n{}\n}} -Watch",
            include_str!("windows/discover-egress.ps1")
        );
        let worker = tokio::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .creation_flags(0x08000000)
            .kill_on_drop(true)
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn();
        match worker {
            Ok(mut child) => {
                let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
                loop {
                    match tokio::time::timeout(Duration::from_secs(20), lines.next_line()).await {
                        Ok(Ok(Some(line))) if valid_observation(&line) => {
                            if changes.observe(&line) {
                                tracing::info!(
                                    "Physical network recovered/changed; rebuilding TUN session"
                                );
                                return;
                            }
                        }
                        _ => break,
                    }
                }
            }
            Err(error) => tracing::warn!("Cannot start network monitor: {error}"),
        }
        tracing::warn!("Network monitor interrupted; retrying");
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
}

fn valid_observation(line: &str) -> bool {
    if line == "offline" {
        return true;
    }
    let fields: Vec<_> = line.split('|').collect();
    fields.len() == 5
        && fields[0].parse::<u32>().is_ok()
        && fields[1].parse::<std::net::Ipv4Addr>().is_ok()
        && fields[2].parse::<std::net::Ipv4Addr>().is_ok()
        && !fields[3].is_empty()
        && matches!(fields[4], "True" | "False")
}

#[cfg(not(windows))]
pub async fn wait_for_change() {
    std::future::pending::<()>().await;
}

#[cfg(test)]
mod tests {
    use super::*;
    const A: &str = "3|172.16.40.47|172.16.40.1|202.118.66.6|True";
    fn baseline() -> Changes {
        let mut state = Changes::default();
        assert!(!state.observe(A));
        assert!(!state.observe(A));
        state
    }
    #[test]
    fn reconnect_on_same_lease_and_repeated_outages() {
        let mut state = baseline();
        for _ in 0..3 {
            assert!(!state.observe("offline"));
            assert!(!state.observe("offline"));
            assert!(!state.observe(A));
            assert!(state.observe(A));
            assert!(!state.observe(A));
        }
    }
    #[test]
    fn address_gateway_dns_interface_and_dns_reset_each_recover() {
        for next in [
            "3|172.16.47.199|172.16.40.1|202.118.66.6|True",
            "3|172.16.40.47|172.16.40.2|202.118.66.6|True",
            "3|172.16.40.47|172.16.40.1|1.1.1.1|True",
            "5|172.16.40.47|172.16.40.1|202.118.66.6|True",
            "3|172.16.40.47|172.16.40.1|202.118.66.6|False",
        ] {
            let mut state = baseline();
            assert!(!state.observe(next));
            assert!(state.observe(next));
            assert!(!state.observe(next));
        }
    }
    #[test]
    fn transient_gap_and_flapping_do_not_restart() {
        let mut state = baseline();
        assert!(!state.observe("offline"));
        assert!(!state.observe(A));
        assert!(!state.observe(A));
        assert!(!state.observe("3|172.16.47.199|172.16.40.1|202.118.66.6|True"));
        assert!(!state.observe(A));
        assert!(!state.observe(A));
    }
    #[test]
    fn offline_at_start_still_triggers_repair_when_online() {
        let mut state = Changes::default();
        state.observe("offline");
        state.observe("offline");
        assert!(!state.observe(A));
        assert!(state.observe(A));
    }

    #[test]
    fn change_during_initial_sampling_is_not_lost() {
        let mut state = Changes::default();
        assert!(!state.observe(A));
        let next = "5|192.168.43.5|192.168.43.1|192.168.43.1|True";
        assert!(!state.observe(next));
        assert!(state.observe(next));
    }

    #[cfg(windows)]
    #[test]
    fn powershell_worker_observes_dhcp_gap_then_new_address_gateway_and_dns() {
        // Execute the production worker with OS query fixtures. No adapter or
        // system route is modified; the stream protocol and debounce are real.
        let mocks = r#"
$script:poll=0
function Get-NetRoute {
 param($AddressFamily,$DestinationPrefix,$ErrorAction)
 $script:poll++
 if ($script:poll -in 3,4) { return }
 $gateway=if($script:poll -le 2){'172.16.40.1'}else{'192.168.43.1'}
 [pscustomobject]@{InterfaceIndex=3;InterfaceAlias='fixture';RouteMetric=1;InterfaceMetric=1;NextHop=$gateway}
}
function Get-NetIPAddress {
 param($InterfaceIndex,$AddressFamily,$ErrorAction)
 $ip=if($script:poll -le 2){'172.16.40.47'}else{'192.168.43.5'}
 [pscustomobject]@{IPAddress=$ip;AddressState='Preferred'}
}
function Get-NetIPInterface {param($InterfaceIndex,$AddressFamily,$ErrorAction) [pscustomobject]@{ConnectionState='Connected'}}
function Get-DnsClientServerAddress {
 param($InterfaceIndex,$AddressFamily,$ErrorAction)
 $dns=if($script:poll -le 2){'202.118.66.6'}else{'192.168.43.1'}
 [pscustomobject]@{ServerAddresses=@('198.18.0.2',$dns)}
}
function Start-Sleep {param($Milliseconds) if($script:poll -ge 6){exit 0}}
"#;
        let output = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!(
                    "{mocks}\n& {{\n{}\n}} -Watch",
                    include_str!("windows/discover-egress.ps1")
                ),
            ])
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let stdout = String::from_utf8_lossy(&output.stdout);
        let lines: Vec<_> = stdout.lines().collect();
        assert_eq!(lines.len(), 6, "{stdout}");
        assert_eq!(&lines[2..4], ["offline", "offline"]);
        assert_eq!(lines[5], "3|192.168.43.5|192.168.43.1|192.168.43.1|True");
        let mut state = Changes::default();
        assert_eq!(
            lines
                .iter()
                .filter(|line| {
                    assert!(valid_observation(line));
                    state.observe(line)
                })
                .count(),
            1
        );
    }
}
