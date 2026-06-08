# ============================================================
# SimplePlane Platform — Windows TUN Mode Launcher (PowerShell)
# 必须以管理员身份运行
# ============================================================
#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  SimplePlane Platform — TUN Mode (Windows)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. Check Rust/Cargo ---
if (-not (Get-Command "cargo" -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] cargo not found. Please install Rust: https://rustup.rs" -ForegroundColor Red
    exit 1
}

# --- 2. Build tun-adapter ---
$TunDir = Join-Path $ProjectRoot "tun-adapter"
Write-Host "[1/3] Building tun-adapter..." -ForegroundColor Yellow
Push-Location $TunDir
try {
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
} finally {
    Pop-Location
}
Write-Host "      Build complete." -ForegroundColor Green

# --- 3. Check proxy-local ---
$ProxyJar = Get-ChildItem -Path (Join-Path $ProjectRoot "proxy-local\target") -Filter "proxy-local*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $ProxyJar) {
    Write-Host "[2/3] Building proxy-local (mvn package)..." -ForegroundColor Yellow
    Push-Location (Join-Path $ProjectRoot "proxy-local")
    try {
        & mvn.cmd package -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
        $ProxyJar = Get-ChildItem -Path (Join-Path $ProjectRoot "proxy-local\target") -Filter "proxy-local*.jar" | Select-Object -First 1
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[2/3] proxy-local jar found, skipping build." -ForegroundColor Green
}

# --- 4. Start Dashboard ---
Write-Host "[3/3] Starting Dashboard on http://localhost:3000 ..." -ForegroundColor Yellow
$DashDir = Join-Path $ProjectRoot "dashboard"
Push-Location $DashDir
try {
    Write-Host ""
    Write-Host "Dashboard is starting. Use the web UI to start/stop services." -ForegroundColor Green
    Write-Host "Press Ctrl+C to stop the Dashboard." -ForegroundColor DarkGray
    Write-Host ""
    node server.js
} finally {
    Pop-Location
}
