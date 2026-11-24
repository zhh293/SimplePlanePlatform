param([switch]$CopyPassword)
$ErrorActionPreference = 'Stop'
$operatorDir = Join-Path $env:LOCALAPPDATA 'SimplePlane/SubscriptionAdmin'
$profileDir = Join-Path $operatorDir 'firefox'
if (-not (Test-Path -LiteralPath (Join-Path $profileDir 'cert9.db'))) {
    throw 'The private CA profile for this deployment is missing.'
}
if ($CopyPassword) {
    $credential = Import-Clixml -LiteralPath (Join-Path $operatorDir 'zhh.credential.xml')
    Set-Clipboard -Value $credential.GetNetworkCredential().Password
}
$runtimeFile = Join-Path $operatorDir 'runtime.json'
if (-not $CopyPassword -and (Test-Path -LiteralPath $runtimeFile)) {
    $runtime = Get-Content -Raw -Encoding UTF8 -LiteralPath $runtimeFile | ConvertFrom-Json
    $loginScript = Join-Path $PSScriptRoot 'sign-in.py'
    Start-Process -FilePath $runtime.python -ArgumentList ('"' + $loginScript + '"') -WindowStyle Hidden
    return
}
$installed = Join-Path $env:ProgramFiles 'Mozilla Firefox/firefox.exe'
$cached = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'ms-playwright/firefox-*/firefox/firefox.exe') -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$firefox = if (Test-Path -LiteralPath $installed) { $installed } elseif ($cached) { $cached.FullName } else { throw 'Firefox was not found.' }
Start-Process -FilePath $firefox -ArgumentList @('-no-remote', '-profile', ('"' + $profileDir + '"'), 'https://212.64.14.155/admin/grants')
