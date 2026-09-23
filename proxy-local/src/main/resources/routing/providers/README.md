# Proxy domain provider

This directory contains a vendored snapshot used by the Java `proxy-local` route engine.

- Source: https://github.com/Loyalsoldier/v2ray-rules-dat/blob/release/proxy-list.txt
- Raw source: https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/proxy-list.txt
- Retrieved: 2026-09-23
- Snapshot SHA-256: `F48A01976038169870C10B317AF79F91FDA2E2CF8941CB8CD1A6E6EAED497E19`
- Upstream repository license: GPL-3.0

The provider is read locally at startup. Each domain is compiled as a suffix rule,
so both the domain and its subdomains are routed through the proxy. User
`directList` rules are compiled before this provider and keep precedence.
