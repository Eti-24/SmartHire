$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$project = Split-Path $PSScriptRoot -Parent
$server = Join-Path $project '.tools\mysql-8.4.13-winx64\bin\mysqld.exe'
$config = Join-Path $project '.local\my.ini'
$client = Join-Path $project '.local\mysql-admin.ini'
if (-not (Test-Path -LiteralPath $config)) { throw 'Run scripts/configure-mysql.ps1 first.' }
& mysql "--defaults-extra-file=$client" --connect-timeout=2 --batch --execute='SELECT 1' *> $null
if ($LASTEXITCODE -eq 0) { return }
Start-Process -FilePath $server -ArgumentList "--defaults-file=`"$config`"" -WindowStyle Hidden
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    & mysql "--defaults-extra-file=$client" --connect-timeout=2 --batch --execute='SELECT 1' *> $null
    if ($LASTEXITCODE -eq 0) { Write-Output 'SmartHire MySQL is ready on 127.0.0.1:3306.'; return }
}
throw 'MySQL did not become ready. Inspect .local/mysql-error.log.'
