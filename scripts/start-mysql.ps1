$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$project = Split-Path $PSScriptRoot -Parent
$server = Join-Path $project '.tools\mysql-8.4.13-winx64\bin\mysqld.exe'
$config = Join-Path $project '.local\my.ini'
$client = Join-Path $project '.local\mysql-admin.ini'
if (-not (Test-Path -LiteralPath $config)) { throw 'Run scripts/configure-mysql.ps1 first.' }
if (-not (Test-Path -LiteralPath $client)) { throw 'Private server credentials are missing. Run Client-Setup.cmd; no existing data will be reset automatically.' }
function Test-ProjectServer {
    $directory = & mysql "--defaults-extra-file=$client" --connect-timeout=2 --batch --skip-column-names --execute='SELECT @@datadir' 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    $expected = Join-Path $project '.local/mysql-data'
    if ([IO.Path]::GetFullPath(([string]$directory).Trim()).TrimEnd('\', '/') -ne $expected.TrimEnd('\', '/')) {
        throw 'The configured endpoint belongs to a different MySQL server. SmartHire did not change it. Check the dedicated server configuration; leave XAMPP running.'
    }
    return $true
}
if (Test-ProjectServer) { return }
$portLine = Get-Content -LiteralPath $config | Where-Object { $_ -match '^port=(\d+)$' } | Select-Object -First 1
if (-not $portLine) { throw 'The dedicated server port is missing. Restore .local/my.ini or run Client-Setup.cmd.' }
$port = [int]($portLine -split '=', 2)[1]
if ($port -lt 1 -or $port -gt 65535) { throw 'The dedicated server port is invalid. Restore .local/my.ini.' }
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
try { $probe.Server.ExclusiveAddressUse = $true; $probe.Start() }
catch { throw "Port $port is occupied, but the private SmartHire administrator login failed. This can mean XAMPP/another server or changed credentials. No server was stopped and no passwords were reset. See docs/client-local-setup.md." }
finally { $probe.Stop() }
Start-Process -FilePath $server -ArgumentList "--defaults-file=`"$config`"" -WindowStyle Hidden
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    if (Test-ProjectServer) { Write-Output 'SmartHire MySQL is ready on the port in .local/my.ini.'; return }
}
throw 'MySQL did not become ready. Inspect .local/mysql-error.log.'
