# Windows TUN network repair — 2026-11-02

Confirmed causes:
- FakeDNS allocated the interface address 198.18.0.1 and DNS endpoint 198.18.0.2 to domains. These and the Windows peer 198.18.255.254 are now reserved, including after pool wraparound.
- Windows DNS was configured only on WLAN. Explicit FakeDNS queries succeeded but system getaddrinfo failed. Configuring DNS on the TUN interface as well restored system resolution in the live test.
- SOCKS5 re-applied the local DIRECT rules to TUN requests (confirmed for baidu.com in proxy-local.log). Those requests used intercepted system DNS/routes. TUN now binds local SOCKS connections to 127.0.0.2; proxy-local honors REJECT but sends this source through the remote proxy instead of local DIRECT. This is a routing convention, not an authentication mechanism. True local direct egress remains future work; TUN and proxy-local must be upgraded together.
- Disabled Nagle on the two TCP forwarding layers. No claim that this alone explains prior latency.

Validation: 50 Rust unit tests and 2 integration tests passed; 4 environment-dependent tests ignored. Java handler compiled with javac --release 8 against the installed shaded JAR, and replaced in that JAR while preserving its dependencies (not a full Maven rebuild).

Live HTTPS GETs on Windows, two consecutive runs, valid TLS and HTTP 200:
- TUN Baidu: 2.516 / 2.531 seconds to first response bytes.
- TUN Google: 1.141 / 1.344 seconds.
- SOCKS5 Google comparison while TUN active: 1.391 / 1.594 seconds.
These are small-sample timings, not a throughput benchmark or browser HTTP3 diagnosis.

Installed both patched artifacts in E:/SimplePlane/resources, with before-network-fix backups. Workspace desktop resources also updated. Temporary test TUN is stopped using its stop file, with DNS/routes restored. General UDP forwarding remains unsupported.
