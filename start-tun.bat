@echo off
:: SimplePlane Platform — Windows TUN Mode Launcher
:: This script requires Administrator privileges.
:: It will auto-elevate if not already running as admin.

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [*] Requesting Administrator privileges...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0start-tun.ps1"
pause
