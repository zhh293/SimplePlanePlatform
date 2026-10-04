# ==============================================================================
# collect-resources.ps1 — 收集所有运行时资源到 Tauri resources 目录 (Windows)
# 将 fat-jar、tun-adapter.exe、wintun.dll、web UI、配置模板 汇集到
# desktop\src-tauri\resources\
# 幂等：可重复执行
# ==============================================================================

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path "$ScriptDir\..\..").Path
$DesktopDir = "$ProjectRoot\desktop"
$ResourcesDir = "$DesktopDir\src-tauri\resources"

Write-Host "=== SimplePlane: Collect Resources (Windows) ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"
Write-Host "Resources:    $ResourcesDir"

# 创建目录
New-Item -ItemType Directory -Force -Path $ResourcesDir | Out-Null

# 1. 复制 fat-jar
$FatJar = "$ProjectRoot\proxy-local\target\proxy-local-1.0.0-SNAPSHOT.jar"
if (Test-Path $FatJar) {
    Copy-Item $FatJar "$ResourcesDir\proxy-local.jar" -Force
    $size = [math]::Round((Get-Item "$ResourcesDir\proxy-local.jar").Length / 1MB, 1)
    Write-Host "[OK] proxy-local.jar ($size MB)" -ForegroundColor Green
} else {
    Write-Host "[WARN] proxy-local.jar not found at $FatJar" -ForegroundColor Yellow
    Write-Host "       Run: mvn package -pl proxy-local -am -DskipTests"
}

# 2. 复制 tun-adapter.exe
$TunBin = "$ProjectRoot\tun-adapter\target\release\tun-adapter.exe"
if (Test-Path $TunBin) {
    Copy-Item $TunBin "$ResourcesDir\tun-adapter.exe" -Force
    Write-Host "[OK] tun-adapter.exe" -ForegroundColor Green
} else {
    Write-Host "[WARN] tun-adapter.exe not found at $TunBin" -ForegroundColor Yellow
    Write-Host "       Run: cd tun-adapter && cargo build --release"
}

# 3. 复制 wintun.dll
$WintunDll = "$ProjectRoot\tun-adapter\wintun.dll"
if (Test-Path $WintunDll) {
    Copy-Item $WintunDll "$ResourcesDir\wintun.dll" -Force
    Write-Host "[OK] wintun.dll" -ForegroundColor Green
} else {
    throw "wintun.dll not found at $WintunDll. Download the matching Wintun architecture DLL before building Windows packages."
}

# 4. 复制 Web UI
$WebSrc = "$DesktopDir\web"
$WebDest = "$ResourcesDir\web"
if (Test-Path $WebSrc) {
    if (Test-Path $WebDest) { Remove-Item $WebDest -Recurse -Force }
    Copy-Item $WebSrc $WebDest -Recurse
    Write-Host "[OK] web\ (dashboard UI)" -ForegroundColor Green
} else {
    Write-Host "[WARN] Web UI not found at $WebSrc" -ForegroundColor Yellow
}

# 5. 配置模板
$ConfigDir = "$ResourcesDir\config-templates"
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null

$ProxyYml = "$ProjectRoot\proxy-local\src\main\resources\proxy.yml"
if (Test-Path $ProxyYml) {
    Copy-Item $ProxyYml "$ConfigDir\proxy.yml" -Force
    Write-Host "[OK] config-templates\proxy.yml" -ForegroundColor Green
}

$TunToml = "$ProjectRoot\tun-adapter\config\tun.toml"
if (Test-Path $TunToml) {
    Copy-Item $TunToml "$ConfigDir\tun.toml" -Force
    (Get-Content "$ConfigDir\tun.toml" -Raw).Replace('name = "utun9"', 'name = "SimplePlane"') |
        Set-Content "$ConfigDir\tun.toml" -Encoding UTF8
    Write-Host "[OK] config-templates\tun.toml" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Resources Summary ===" -ForegroundColor Cyan
Get-ChildItem $ResourcesDir | Format-Table Name, Length, LastWriteTime -AutoSize

# 验证关键文件
$missing = 0
if (-not (Test-Path "$ResourcesDir\proxy-local.jar")) { Write-Host "MISSING: proxy-local.jar" -ForegroundColor Red; $missing++ }
if (-not (Test-Path "$ResourcesDir\jre")) { Write-Host "MISSING: jre\ (run build-jre.ps1 first)" -ForegroundColor Red; $missing++ }
if (-not (Test-Path "$ResourcesDir\tun-adapter.exe")) { Write-Host "MISSING: tun-adapter.exe" -ForegroundColor Red; $missing++ }
if (-not (Test-Path "$ResourcesDir\wintun.dll")) { Write-Host "MISSING: wintun.dll" -ForegroundColor Red; $missing++ }

if ($missing -gt 0) {
    Write-Host "`nWARNING: Some resources are missing." -ForegroundColor Yellow
} else {
    Write-Host "All critical resources present!" -ForegroundColor Green
}

Write-Host "Done!"
