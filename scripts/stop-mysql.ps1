$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$client = Join-Path (Split-Path $PSScriptRoot -Parent) '.local\mysql-admin.ini'
& mysqladmin "--defaults-extra-file=$client" --connect-timeout=3 shutdown
if ($LASTEXITCODE -ne 0) { throw 'MySQL shutdown did not succeed.' }
