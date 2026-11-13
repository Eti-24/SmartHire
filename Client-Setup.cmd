@echo off
setlocal
cd /d "%~dp0"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo Install PowerShell 7 x64 first. See docs\client-local-setup.md.
  pause
  exit /b 1
)
if "%~1"=="--reset" (
  pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\client-setup.ps1" -Reset
) else (
  if not "%~1"=="" (
    echo Usage: Client-Setup.cmd [--reset]
    exit /b 1
  )
  pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\client-setup.ps1"
)
set "result=%errorlevel%"
pause
exit /b %result%
