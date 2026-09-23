# Bundled route providers

This directory contains vendored snapshots used by the Java `proxy-local` route engine.

- Source: https://github.com/Loyalsoldier/v2ray-rules-dat/blob/release/proxy-list.txt
- Raw source: https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/proxy-list.txt
- Retrieved: 2026-09-23
- Snapshot SHA-256: `F48A01976038169870C10B317AF79F91FDA2E2CF8941CB8CD1A6E6EAED497E19`
- Upstream repository license: GPL-3.0

The providers are read locally at startup. Each ordinary domain is compiled as a
suffix rule, so both the domain and its subdomains match. `full:` entries match
only the complete domain. User `directList` rules and the direct provider are
evaluated before the proxy provider.

The bundled direct provider is sourced from:
https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/direct-list.txt

- Retrieved: 2026-09-23
- Snapshot SHA-256: `26684A6A0525C28F66DA2725FBA3CAE9440E16BD30429EF866460DE384565227`
- Snapshot size: 111,179 domain lines

Direct providers are evaluated before proxy providers, matching the intended
China-direct / other-traffic-proxy routing policy.
