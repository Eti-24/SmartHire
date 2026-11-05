$ErrorActionPreference = 'Stop'
$tools = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools'
$workbench = Get-ChildItem -LiteralPath (Join-Path $tools 'workbench') -Filter 'MySQL Workbench.exe' -Recurse | Select-Object -First 1
if (-not $workbench) { throw 'The E:-drive Workbench copy has not been installed.' }
$env:MYSQLSH_USER_CONFIG_HOME = Join-Path $tools 'workbench-shell-user'
New-Item -ItemType Directory -Path $env:MYSQLSH_USER_CONFIG_HOME, (Join-Path $tools 'workbench-user') -Force | Out-Null
Start-Process -FilePath $workbench.FullName -WorkingDirectory $workbench.DirectoryName -ArgumentList @("--electron-user-data-dir=$(Join-Path $tools 'workbench-user')")
