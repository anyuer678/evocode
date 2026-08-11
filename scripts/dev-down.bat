@echo off
title EvoCode Stop
cd /d "%~dp0.."

echo Stopping EvoCode services (backend :18080 / analyzer :8081 / frontend :5173) ...
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 18080,8081,5173 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue; Write-Host ('stopped PID ' + $_.OwningProcess) }"
echo Done. Close any remaining service windows.
pause
