$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$tools = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools'
Start-Process -FilePath (Join-Path $tools 'netbeans\bin\netbeans64.exe') -ArgumentList @('--jdkhome', $env:JAVA_HOME, '--userdir', (Join-Path $tools 'netbeans-user'), '--cachedir', (Join-Path $tools 'netbeans-cache'), '-J-Xmx768m')
