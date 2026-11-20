# China IPv4 routing data

`cn_ipv4_ranges.bin` contains merged IPv4 intervals delegated to mainland China.
Each record is two big-endian `u32` values: inclusive start and inclusive end.

Source: `https://ftp.apnic.net/stats/apnic/delegated-apnic-latest`

Update from the repository root:

```powershell
.\scripts\update-cn-ip.ps1
```

The router validates the binary shape and uses a binary search at runtime. User
rules are evaluated before this built-in data, so explicit proxy/direct/reject
rules always win.

Android also embeds the desktop direct-domain provider from
`proxy-local/src/main/resources/routing/providers/direct-list.txt`. Its source,
snapshot date, checksum and GPL-3.0 license are recorded alongside that file.
Suffix and `full:` rules are indexed once at startup; regexp/keyword syntax is
not evaluated by this suffix provider. Explicit user rules still win, and
disabling `cn_direct` disables the provider. FakeDNS addresses cannot be used
as real GeoIP inputs, so domain coverage is necessary for this path.
