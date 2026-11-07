param([int]$TimeoutSeconds = 8, [int]$PollMilliseconds = 500, [switch]$Watch)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $found = $false
    # DHCP may temporarily remove the default route while the WLAN changes subnet.
    $routes = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object @{Expression={$_.RouteMetric + $_.InterfaceMetric}})
    foreach ($route in $routes) {
        $address = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.AddressState -eq 'Preferred' -and $_.IPAddress -notmatch '^(198\.(18|19)\.|127\.|169\.254\.|0\.)' } |
            Select-Object -First 1
        if (!$address -or $route.NextHop -match '^198\.(18|19)\.') { continue }
        if ($Watch) {
            $link = Get-NetIPInterface -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
            if (!$link -or $link.ConnectionState -ne 'Connected') { continue }
        }
        $effective = @((Get-DnsClientServerAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses)
        $servers = @($effective |
            Where-Object { $_ -and $_ -notmatch '^198\.(18|19)\.' -and $_ -match '^\d+\.\d+\.\d+\.\d+$' })
        if (!$servers.Count) {
            # During TUN operation the effective DNS is our own FakeDNS. Recover
            # manual DNS from the guard backup, or the current DHCP lease's DNS.
            $backup = @(Get-Content -LiteralPath (Join-Path $env:TEMP 'tun-adapter-dns-backup.conf') -ErrorAction SilentlyContinue)
            if ($backup -contains ('service:' + $route.InterfaceAlias)) {
                $servers = @($backup | Where-Object { $_ -match '^dns:\d+\.\d+\.\d+\.\d+$' } | ForEach-Object { $_.Substring(4) })
            }
            if (!$servers.Count) {
                $adapter = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object ifIndex -eq $route.InterfaceIndex | Select-Object -First 1
                if ($adapter) {
                    $guid = ([guid]$adapter.InterfaceGuid).ToString('B')
                    $settings = Get-ItemProperty -LiteralPath ('HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $guid) -ErrorAction SilentlyContinue
                    $servers = @($settings.DhcpNameServer -split '[,\s]+' | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' })
                }
            }
            $servers = @($servers | Where-Object { $_ -notmatch '^198\.(18|19)\.' })
        }
        if (!$servers.Count) { continue }
        if ($Watch) {
            $found = $true
            # Include effective DNS interception: DHCP may reset it on reconnect.
            '{0}|{1}|{2}|{3}|{4}' -f $route.InterfaceIndex,$address.IPAddress,$route.NextHop,($servers -join ','),($effective -contains '198.18.0.2')
            break
        } else {
            $route.InterfaceIndex
            $address.IPAddress
            $servers
            exit 0
        }
    }
    if ($Watch) {
        if (!$found) { 'offline' }
        Start-Sleep -Milliseconds 3000
        $address = $null; $servers = @(); $route = $null
        continue
    }
    if ([DateTime]::UtcNow -ge $deadline) { break }
    Start-Sleep -Milliseconds $PollMilliseconds
} while ($Watch -or [DateTime]::UtcNow -lt $deadline)
Write-Error 'No usable physical IPv4 default route and DNS after waiting for DHCP. Check the network connection and retry.'
exit 1
