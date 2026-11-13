#Requires -Version 7.0
param([switch]$SeedDemo, [switch]$Offline, [switch]$BuildRuntime)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$local = Join-Path $project '.local'
if (Test-Path -LiteralPath $local) {
    "$(Get-Date -Format o) CHECKING: readiness has not yet been established." |
        Set-Content -LiteralPath (Join-Path $local 'readiness-status.txt') -Encoding utf8NoBOM
}
. "$PSScriptRoot/dev-env.ps1"
& "$PSScriptRoot/start-mysql.ps1"
# start-mysql checks @@datadir before any repair is allowed. Neither cloud nor XAMPP is a repair target.
$admin = Join-Path $local 'mysql-admin.ini'
function Invoke-Admin([string]$Sql) {
    $answer = $Sql | & mysql "--defaults-extra-file=$admin" --connect-timeout=3 --batch --skip-column-names 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated server administration failed. Existing data retained; inspect .local/mysql-error.log and the client setup guide.' }
    return $answer
}
$serverPort = [int](Invoke-Admin 'SELECT @@port;')
$settingsFile = Join-Path $local 'database.json'
$settings = $null
if (Test-Path -LiteralPath $settingsFile) {
    try { $settings = Get-Content -LiteralPath $settingsFile -Raw | ConvertFrom-Json } catch { $settings = $null }
}
if (-not $settings) {
    $appFile = Join-Path $local 'mysql-app.ini'
    if (-not (Test-Path -LiteralPath $appFile)) {
        throw 'Both application credential copies are missing. Restore .local/database.json or .local/mysql-app.ini from your private backup. No password was guessed or reset; explicit demo reset remains available for a marked installation.'
    }
    $fields = @{}
    foreach ($line in Get-Content -LiteralPath $appFile) {
        if ($line -match '^(host|port|user|password|database)=(.*)$') { $fields[$Matches[1]] = $Matches[2] }
    }
    $settings = [pscustomobject]@{host=$fields.host; port=$serverPort; database=$fields.database;
        testDatabase='smarthire_test'; user=$fields.user; password=$fields.password}
    Write-Host 'Recovered the local application settings from the private MySQL client file.'
}
if ($settings.host -ne '127.0.0.1' -or $settings.database -ne 'smarthire' -or $settings.user -ne 'smarthire' -or
        $settings.password -notmatch '^[A-Fa-f0-9]{48}$') {
    throw 'Automatic repair requires the dedicated setup-generated local account. Custom or external configuration was retained. Use the client setup guide; do not reset an unrelated server.'
}
$settings | Add-Member -NotePropertyName port -NotePropertyValue $serverPort -Force
$settings | ConvertTo-Json | Set-Content -LiteralPath $settingsFile -Encoding utf8NoBOM
$app = Join-Path $local 'mysql-app.ini'
@"
[client]
user=smarthire
password=$($settings.password)
host=127.0.0.1
port=$serverPort
protocol=TCP
database=smarthire
"@ | Set-Content -LiteralPath $app -Encoding utf8NoBOM
& "$PSScriptRoot/write-local-config.ps1"
$missing = [int](Invoke-Admin "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='smarthire';") -eq 0
$hasUsers = -not $missing -and [int](Invoke-Admin "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='smarthire' AND table_name='users';") -gt 0
$emptyUsers = -not $hasUsers -or [long](Invoke-Admin 'SELECT COUNT(*) FROM smarthire.users;') -eq 0
$documentRoot = Join-Path $local 'documents'
if ($emptyUsers -and (Test-Path -LiteralPath $documentRoot)) {
    $retainedFiles = Get-ChildItem -LiteralPath $documentRoot -File |
        Where-Object { $_.Name -match '^[0-9a-f-]{36}\.(pdf|docx|part)$' }
    if ($retainedFiles) {
        throw 'The database is missing or empty but managed CV/circular files remain. Restore the matching SQL backup before opening SmartHire. Automatic demo recovery stopped to protect those files; use explicit reset only if you want to erase the old demo.'
    }
}
if (-not $missing -and -not $hasUsers -and
        [int](Invoke-Admin "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='smarthire';") -gt 0) {
    throw 'The existing main database contains tables but no users table. Automatic repair stopped rather than guessing its history. Restore a backup or retain the error for support; no reset performed.'
}
Invoke-Admin 'CREATE DATABASE IF NOT EXISTS smarthire CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS smarthire_test CHARACTER SET utf8mb4;' | Out-Null
if ($missing) { Write-Host 'Created the missing local SmartHire database. Existing schemas were retained.' }
& mysql "--defaults-extra-file=$app" --connect-timeout=3 --batch --execute='SELECT 1' *> $null
if ($LASTEXITCODE -ne 0) {
    # Only generated hex credentials reach this SQL. Use stdin, never a password-bearing process argument or log.
    $accountSql = "CREATE USER IF NOT EXISTS 'smarthire'@'localhost' IDENTIFIED BY '$($settings.password)'; ALTER USER 'smarthire'@'localhost' IDENTIFIED BY '$($settings.password)';"
    $accountSql += "CREATE USER IF NOT EXISTS 'smarthire'@'127.0.0.1' IDENTIFIED BY '$($settings.password)'; ALTER USER 'smarthire'@'127.0.0.1' IDENTIFIED BY '$($settings.password)';"
    Invoke-Admin $accountSql | Out-Null
    Write-Host 'Restored the dedicated generated application account.'
}
# Restore scoped privileges even when SELECT works but schema initialization/write permission was removed.
foreach ($hostName in @('localhost', '127.0.0.1')) {
    $exists = [int](Invoke-Admin "SELECT COUNT(*) FROM mysql.user WHERE User='smarthire' AND Host='$hostName';")
    if ($exists -gt 0) {
        Invoke-Admin "GRANT ALL PRIVILEGES ON smarthire.* TO 'smarthire'@'$hostName'; GRANT ALL PRIVILEGES ON smarthire_test.* TO 'smarthire'@'$hostName';" | Out-Null
    }
}
$classFile = Join-Path $project 'target/classes/smarthire/Main.class'
$classpathFile = Join-Path $project 'target/runtime-classpath.txt'
$hasRepairEntry = (Test-Path $classFile) -and
    [Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($classFile)).Contains('--prepare-local')
if ($BuildRuntime -or -not $hasRepairEntry -or -not (Test-Path $classpathFile)) {
    Write-Host 'Building the missing application runtime.'
    & "$PSScriptRoot/run.ps1" -BuildOnly -Offline:$Offline
}
$runtime = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
Push-Location $project
try {
    & java -cp "target/classes;$runtime" smarthire.Main --prepare-local
    if ($LASTEXITCODE -ne 0) { throw 'Local schema preparation failed. Data was not reset. Close SmartHire and rerun Client-Repair.cmd; retain the error if it persists.' }
    $users = & mysql "--defaults-extra-file=$app" --batch --skip-column-names --execute='SELECT COUNT(*) FROM users' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Application account could not read SmartHire. No demo seed performed.' }
    if ($SeedDemo -or [long]$users -eq 0) {
        & java -cp "target/classes;$runtime" smarthire.Main --seed-demo
        if ($LASTEXITCODE -ne 0) { throw 'Demo setup did not finish. Existing data retained; rerun Client-Repair.cmd.' }
    }
    & java -cp "target/classes;$runtime" smarthire.Main --check-database
    if ($LASTEXITCODE -ne 0) { throw 'The final application connection check failed. NetBeans was not opened.' }
    "$(Get-Date -Format o) READY: dedicated server, configuration, schema and application connection checked." |
        Set-Content -LiteralPath (Join-Path $local 'readiness-status.txt') -Encoding utf8NoBOM
    Write-Host 'SmartHire is ready. Existing accounts, passwords and uploads were retained.'
} finally { Pop-Location }
