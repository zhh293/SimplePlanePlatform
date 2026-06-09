# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SimplePlanePlatform is a high-performance encrypted proxy system: a Java multi-module backend (Maven) + Rust TUN adapter + Node.js dashboard. Traffic flows: `client → proxy-local (SOCKS5/HTTP CONNECT) → HTTP/2 tunnel → proxy-remote → target`.

## Build Commands

```bash
# Build all Java modules
mvn clean package -DskipTests

# Build a single Java module (e.g., proxy-local)
mvn package -pl proxy-local -am -DskipTests

# Build the Rust TUN adapter
cd tun-adapter && cargo build --release

# Dashboard has no build step (zero npm dependencies)
```

## Running the Project

```bash
# Proxy mode (SOCKS5/HTTP on port 1080)
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar

# TUN transparent mode (macOS — requires sudo for tun device)
./start-tun.sh

# TUN mode (Windows — run as Administrator)
.\start-tun.ps1        # or double-click start-tun.bat

# Docker (proxy-remote + proxy-local together)
docker compose up -d --build

# Web dashboard (port 3000)
cd dashboard && node server.js
```

## Testing

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=SystemProxyManagerTest

# Run tests for a single module
mvn test -pl proxy-local
```

Test files live under each module's `src/test/java/`.

## Architecture

### Java Modules (Maven, Java 1.8+)

| Module | Role |
|---|---|
| `proxy-common` | SPI ExtensionLoader, `ProxyMessage` wire format, shared interfaces |
| `proxy-exchange` | Request/response correlation via `RequestId → DefaultFuture` map; timeout via HashedWheelTimer |
| `proxy-transport-netty` | HTTP/2 Netty client/server, stream lifecycle, **CompositeByteBuf cross-frame reassembly** |
| `proxy-crypto` | Pluggable AEAD encryption: `none / aes-gcm / chacha20 / aes-ctr-hmac` |
| `proxy-cluster` | Failover strategies (Failover/Failfast/Forking/Failback) + load balancing (RoundRobin/Random/LeastActive/ConsistentHash) |
| `proxy-local` | SOCKS5 + HTTP CONNECT dual-protocol entry, route-split (direct vs proxy), system proxy auto-setup |
| `proxy-remote` | Outbound dispatcher, `DATA` fast-path, response relay back over HTTP/2 streams |

### Rust Module (`tun-adapter/`)

Tokio async runtime + `tun2` for OS TUN device + `smoltcp` user-mode TCP/IP stack. Implements:
- **FakeDNS**: intercepts DNS queries, returns fake IPs to capture traffic
- **Domain-aware routing**: intranet domains → real DNS + direct; external → SOCKS5 → proxy-local

### Dashboard (`dashboard/`)

Zero-dependency Node.js (no `npm install` needed). `server.js` exposes a REST + Server-Sent Events API for config editing, starting/stopping services, and real-time log streaming. TUN start on macOS uses `sudo -n` (passwordless sudo required; configure with `setup-tun-permissions.sh`).

### Data Flow

```
Browser/App
  └─► proxy-local (SOCKS5 :1080 / HTTP CONNECT)
        ├─ RouteRule → direct (bypass)
        └─ ClusterInvoker → proxy-exchange → Netty HTTP/2 (single TCP conn, up to 1000 streams)
              └─► proxy-remote → target host
                      └─ response relayed back via same HTTP/2 stream
```

TUN mode inserts before this: `any app → utun9 → smoltcp → FakeDNS/routing → SOCKS5 → proxy-local`.

### Key Design Patterns

- **SPI via `ExtensionLoader`** (proxy-common): load implementations by name from `META-INF/extensions/`. Add new crypto/LB/cluster strategies by dropping a properties file + implementation.
- **HTTP/2 multiplexing**: a single persistent connection carries all concurrent requests. Reconnect uses exponential backoff.
- **`CompositeByteBuf` reassembly** (proxy-transport-netty): the critical performance fix — `ProxyMessage` payloads may span multiple HTTP/2 DATA frames; slices are accumulated without copying until a complete message is assembled. Avoid breaking this pattern; it produced a 433× throughput improvement.
- **`DATA` fast-path** (proxy-remote `DispatchInvoker`): once a session is established, DATA frames are forwarded without re-parsing the full message header. Don't add blocking logic in this path.

## Configuration Files

| File | Purpose |
|---|---|
| `proxy-local/src/main/resources/proxy.yml` | Local proxy: listen port, remote servers, route rules, cluster/LB strategy |
| `proxy-remote/src/main/resources/remote.yml` | Remote server: bind host/port, encryption algorithm, thread pool |
| `tun-adapter/config/tun.toml` | TUN device name, FakeDNS CIDR, bypass IPs, intranet DNS, domain routing |
| `proxy-*/src/main/resources/logback.xml` | Logging (SLF4J + Logback) |
| `docker-compose.yml` | Docker orchestration for both services |

## Dependencies

**Java (pom.xml):** Netty 4.1.108 (HTTP/2 + NIO), SnakeYAML 2.2, BouncyCastle 1.70 (ChaCha20), SLF4J 1.7.36 + Logback 1.2.11, JUnit 5.10.2.

**Rust (Cargo.toml):** `tokio`, `tun2`, `smoltcp 0.11`, `hickory-proto`, `fast-socks5`, `clap`, `tracing`. Windows also uses `winreg` for DNS backup/restore.
