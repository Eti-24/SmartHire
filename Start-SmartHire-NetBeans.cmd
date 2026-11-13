@echo off
setlocal
cd /d "%~dp0"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo Install PowerShell 7 x64 first. See docs\client-local-setup.md.
  pause
  exit /b 1
)
echo Checking SmartHire and starting NetBeans...
pwsh.exe -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0scripts\start-client-netbeans.ps1"
if errorlevel 1 (
  pause
  exit /b 1
)
