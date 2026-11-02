@echo off
:: PowerShell owns the UAC request and reports cancellation/failure.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-tun.ps1"
set "TUN_EXIT_CODE=%errorlevel%"
pause
exit /b %TUN_EXIT_CODE%
