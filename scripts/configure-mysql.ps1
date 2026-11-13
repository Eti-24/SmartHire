param([ValidateRange(1, 65535)][int]$Port = 3307)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$project = Split-Path $PSScriptRoot -Parent
$local = Join-Path $project '.local'
$data = Join-Path $local 'mysql-data'
$config = Join-Path $local 'my.ini'
$adminConfig = Join-Path $local 'mysql-admin.ini'
$appConfig = Join-Path $local 'mysql-app.ini'
$server = Join-Path $project '.tools\mysql-8.4.13-winx64\bin\mysqld.exe'
New-Item -ItemType Directory -Path $local -Force | Out-Null
if (Test-Path -LiteralPath (Join-Path $data 'mysql')) {
    Write-Output 'Existing initialized data retained. No password or database reset performed.'
    return
}
if (Test-Path -LiteralPath $adminConfig) { throw 'Credentials already exist without initialized data. Inspect .local before retrying.' }
$portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try {
    $portProbe.Server.ExclusiveAddressUse = $true
    $portProbe.Start()
} catch {
    throw "Port $Port is unavailable. Choose another port with -Port. No existing server will be changed."
} finally { $portProbe.Stop() }
$rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$base = (Join-Path $project '.tools\mysql-8.4.13-winx64').Replace('\', '/')
$dataPath = $data.Replace('\', '/')
$logPath = (Join-Path $local 'mysql-error.log').Replace('\', '/')
@"
[mysqld]
basedir="$base"
datadir="$dataPath"
port=$Port
bind-address=127.0.0.1
mysqlx=0
innodb-buffer-pool-size=128M
max-connections=20
performance-schema=OFF
character-set-server=utf8mb4
log-error="$logPath"

[client]
host=127.0.0.1
port=$Port
protocol=TCP
"@ | Set-Content -LiteralPath $config -Encoding utf8NoBOM
@"
[client]
user=root
password=$rootPassword
host=127.0.0.1
port=$Port
protocol=TCP
"@ | Set-Content -LiteralPath $adminConfig -Encoding utf8NoBOM
@"
[client]
user=smarthire
password=$appPassword
host=127.0.0.1
port=$Port
protocol=TCP
database=smarthire
"@ | Set-Content -LiteralPath $appConfig -Encoding utf8NoBOM
@{host='127.0.0.1';port=$Port;database='smarthire';testDatabase='smarthire_test';user='smarthire';password=$appPassword} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $local 'database.json') -Encoding utf8NoBOM
$initFile = Join-Path $local 'mysql-first-start.sql'
@"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
CREATE DATABASE IF NOT EXISTS smarthire CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS smarthire_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'smarthire'@'localhost' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON smarthire.* TO 'smarthire'@'localhost';
GRANT ALL PRIVILEGES ON smarthire_test.* TO 'smarthire'@'localhost';
"@ | Set-Content -LiteralPath $initFile -Encoding utf8NoBOM
& $server "--defaults-file=$config" --initialize-insecure
if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed. Inspect .local/mysql-error.log; existing files were retained.' }
Start-Process -FilePath $server -ArgumentList @("--defaults-file=`"$config`"", "--init-file=`"$($initFile.Replace('\', '/'))`"") -WindowStyle Hidden
$ready = $false
for ($attempt=0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    & mysql "--defaults-extra-file=$adminConfig" --connect-timeout=2 --batch --execute='SELECT 1' *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
}
if (-not $ready) { throw 'First startup failed; inspect .local/mysql-error.log. Initialization files retained for recovery.' }
Remove-Item -LiteralPath $initFile
& mysql "--defaults-extra-file=$appConfig" --batch --execute='SELECT VERSION() AS server_version, CURRENT_USER() AS connected_user;'
if ($LASTEXITCODE -ne 0) { throw 'Application-user connection failed.' }
Write-Output 'MySQL configured. Credentials are in ignored .local files; passwords were not printed.'
