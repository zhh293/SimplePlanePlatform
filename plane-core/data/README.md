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
