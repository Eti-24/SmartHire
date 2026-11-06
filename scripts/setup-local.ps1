#Requires -Version 7.0
param([switch]$SeedDemo)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'The portable setup requires Windows x64.'
}

Push-Location $project
try {
    Write-Host '[1/3] Install Java 21, Maven, and MySQL'
    $jdk = Get-ChildItem .tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/javac.exe') } | Select-Object -First 1
    if (-not $jdk -or -not (Test-Path '.tools/apache-maven-3.10.0/bin/mvn.cmd') -or
            -not (Test-Path '.tools/mysql-8.4.13-winx64/bin/mysqld.exe')) {
        & "$PSScriptRoot/install-tools.ps1" -RuntimeOnly
    }
    . "$PSScriptRoot/dev-env.ps1"
    & mysqld --version
    if ($LASTEXITCODE -ne 0) { throw 'MySQL could not run. Check the x64 Visual C++ Redistributable listed in README.md.' }

    Write-Host '[2/3] Configure and start local MySQL'
    & "$PSScriptRoot/configure-mysql.ps1"
    & "$PSScriptRoot/start-mysql.ps1"

    Write-Host '[3/3] Build SmartHire'
    if ($SeedDemo) {
        & "$PSScriptRoot/run.ps1" -SeedDemo
    } else {
        & "$PSScriptRoot/run.ps1" -BuildOnly
    }
    Write-Host 'Setup complete. Open SmartHire with .\scripts\run.ps1'
} finally {
    Pop-Location
}
