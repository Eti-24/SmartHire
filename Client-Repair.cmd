@echo off
setlocal
cd /d "%~dp0"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo Install PowerShell 7 x64 first. See docs\client-local-setup.md.
  pause
  exit /b 1
)
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\repair-local.ps1"
set "result=%errorlevel%"
pause
exit /b %result%
