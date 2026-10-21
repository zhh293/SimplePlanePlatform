param(
    [string]$SourceUrl = "https://ftp.apnic.net/stats/apnic/delegated-apnic-latest",
    [string]$InputFile = "",
    [string]$OutputPath = "$PSScriptRoot\..\plane-core\data\cn_ipv4_ranges.bin"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$downloadedFile = $null
try {
    $sourcePath = $InputFile
    if ([string]::IsNullOrWhiteSpace($sourcePath)) {
        $downloadedFile = [System.IO.Path]::GetTempFileName()
        Invoke-WebRequest -Uri $SourceUrl -OutFile $downloadedFile
        $sourcePath = $downloadedFile
    }

    $intervals = foreach ($line in Get-Content -LiteralPath $sourcePath) {
        if ($line -notmatch '^apnic\|CN\|ipv4\|') {
            continue
        }

        $parts = $line.Split('|')
        $addressBytes = [Net.IPAddress]::Parse($parts[3]).GetAddressBytes()
        [Array]::Reverse($addressBytes)
        $start = [uint64][BitConverter]::ToUInt32($addressBytes, 0)
        $count = [uint64]$parts[4]
        [PSCustomObject]@{ Start = $start; End = $start + $count - 1 }
    }

    $merged = [System.Collections.Generic.List[object]]::new()
    foreach ($interval in ($intervals | Sort-Object Start)) {
        if ($merged.Count -eq 0 -or $interval.Start -gt ([uint64]$merged[$merged.Count - 1].End + 1)) {
            $merged.Add([PSCustomObject]@{ Start = $interval.Start; End = $interval.End })
        } elseif ($interval.End -gt $merged[$merged.Count - 1].End) {
            $merged[$merged.Count - 1].End = $interval.End
        }
    }

    $bytes = [byte[]]::new($merged.Count * 8)
    $offset = 0
    foreach ($interval in $merged) {
        foreach ($value in @([uint64]$interval.Start, [uint64]$interval.End)) {
            $bytes[$offset] = [byte](($value -shr 24) -band 0xff)
            $bytes[$offset + 1] = [byte](($value -shr 16) -band 0xff)
            $bytes[$offset + 2] = [byte](($value -shr 8) -band 0xff)
            $bytes[$offset + 3] = [byte]($value -band 0xff)
            $offset += 4
        }
    }

    $absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($absoluteOutput)) | Out-Null
    [System.IO.File]::WriteAllBytes($absoluteOutput, $bytes)
    $sha256 = (Get-FileHash -LiteralPath $absoluteOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Generated $($merged.Count) merged CN IPv4 intervals ($($bytes.Length) bytes)"
    Write-Host "Output: $absoluteOutput"
    Write-Host "SHA256: $sha256"
} finally {
    if ($downloadedFile -and (Test-Path -LiteralPath $downloadedFile)) {
        Remove-Item -LiteralPath $downloadedFile -Force
    }
}
