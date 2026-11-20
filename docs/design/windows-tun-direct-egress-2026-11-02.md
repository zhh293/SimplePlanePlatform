# Windows TUN direct TCP egress — 2026-11-02

## Behavior

On Windows, DIRECT now resolves A records through the original interface's DNS servers and opens TCP sockets pinned to that interface with IP_UNICAST_IF plus its source IPv4 address. It does not call SOCKS5. PROXY and REJECT keep their route decisions even when proxy health checks fail. No Java source or JAR changes in this implementation.

The physical interface and DNS are captured before TUN setup. No per-destination route entries are added. Sockets close with their forwarding task. Reconnect TUN after switching Wi-Fi or changing the physical network; failed pinned connections do not fall back to the proxy or another interface. DNS is bounded to 3 seconds per resolver, TCP to 5 seconds per address, with a 15-second overall connection deadline; relay uses the existing bidirectional 120-second idle timeout and half-close handling.

Rules remain explicit and ordered. Added baidu.com, bdstatic.com and bdimg.com to the shipped template and the current user's TUN configuration, preserving existing rules. Other domestic sites are direct only if an existing rule matches. Domain rules require FakeDNS domain mapping; browser DoH/cached real IP connections require corresponding IP rules or a fresh system DNS lookup.

Scope: Windows IPv4 TCP. Non-Windows retains the previous SOCKS forwarding behavior for DIRECT. Generic UDP forwarding, IPv6 direct egress, automatic interface migration, and TCP fallback for truncated direct DNS answers are not implemented. A truncated/invalid/no-A DNS response tries the next configured original resolver, then fails explicitly.

## Validation

54 Rust unit tests + 2 integration tests passed (4 environment tests skipped); release compiled. Tests include pinned Windows loopback TCP forwarding without a SOCKS server, half-close response delivery, rejecting unmapped FakeIP destinations, invalid discovery metadata and DNS replies, plus existing routing/backpressure/timeout tests.

Live Windows TUN, three rounds: Baidu HTTP 200 at 0.485 / 0.344 / 0.500 s; Google HTTP 200 at 0.875 / 0.813 / 0.813 s. Direct logs confirm www.baidu.com -> 182.61.200.108/110 via physical source 172.16.56.7, interface 3. Google logs confirm PROXY. Test cleanup restored original DNS/routes.

Installed tun-adapter.exe with tun-adapter.before-direct.exe backup; user tun.toml and tun-nodes.toml have .before-direct backups. proxy-local.jar and proxy-remote were not changed for this work.

Reference: https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options (IP_UNICAST_IF).

## Follow-up: preserve automatic DNS mode

The previous backup read effective ServerAddresses, which includes DHCP-provided servers. Restoring that list wrote a static override even if the interface originally used automatic DNS. Windows backup now reads the interface registry NameServer override: an empty override is recorded as DHCP and restored with ResetServerAddresses. Static servers retain their original order; failed or invalid backups abort setup.

Validation: 56 unit tests and 2 integration tests passed, 4 environment-dependent tests skipped. A live TUN start/stop cycle preserved an empty NameServer override and DHCP DNS 202.118.66.6. This fixes DNS mode restoration; it does not establish the cause of the separately observed HTTP/3 eight-second timeouts. Temporary QUIC diagnostics and test artifacts are excluded from this change.
