#Requires -Version 7.0
param([ValidateRange(1, 65535)][int]$Port = 3307)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/dev-env.ps1"
$sandbox = Join-Path $project ('target/client setup check ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
New-Item -ItemType Directory -Path (Join-Path $project 'target/java-tmp') -Force | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$($project.Replace('\', '/'))/target/java-tmp"
Copy-Item -LiteralPath (Join-Path $project 'scripts'), (Join-Path $project 'src'),
    (Join-Path $project 'demo data'), (Join-Path $project 'pom.xml') -Destination $sandbox -Recurse
New-Item -ItemType Junction -Path (Join-Path $sandbox '.tools') -Target (Join-Path $project '.tools') | Out-Null
$clientScripts = Join-Path $sandbox 'scripts'
$local = Join-Path $sandbox '.local'
function Assert-Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Invoke-Query([string]$Sql) {
    $answer = & mysql "--defaults-extra-file=$local/mysql-app.ini" --batch --skip-column-names "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated SQL check failed.' }
    return $answer
}
try {
    & "$clientScripts/client-setup.ps1" -Port $Port -Offline
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM users') -ge 8) 'Initial demo accounts missing.'
    Invoke-Query "CREATE TABLE client_reset_probe (id INT); INSERT INTO client_reset_probe VALUES (1);" | Out-Null
    New-Item -ItemType Directory -Path "$local/documents", "$local/aiven-documents" -Force | Out-Null
    'local demo upload' | Set-Content "$local/documents/reset-probe.txt"
    'cloud sentinel' | Set-Content "$local/aiven.properties"
    'cloud sentinel' | Set-Content "$local/aiven-documents/preserve.txt"
    $oldConfigHash = (Get-FileHash "$local/database.json").Hash
    & "$clientScripts/client-setup.ps1" -Port ($Port + 1) -Offline
    Assert-Check ((Get-FileHash "$local/database.json").Hash -eq $oldConfigHash) 'Ordinary setup changed credentials/port.'
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM client_reset_probe') -eq 1) 'Ordinary setup removed data.'
    # Recovery checks operate only on this disposable server, never the working installation.
    $adminFile = Join-Path $local 'mysql-admin.ini'
    $serverData = & mysql "--defaults-extra-file=$adminFile" --batch --skip-column-names --execute='SELECT @@datadir'
    Assert-Check ([IO.Path]::GetFullPath(([string]$serverData).Trim()).TrimEnd('\', '/') -eq (Join-Path $local 'mysql-data')) 'Rehearsal server identity mismatch.'
    $accountsBefore = Invoke-Query 'SELECT GROUP_CONCAT(password_hash ORDER BY id) FROM users'
    Remove-Item -LiteralPath "$local/database.json", "$local/application.properties"
    & "$clientScripts/repair-local.ps1" -Offline
    Assert-Check (Test-Path "$local/database.json") 'Private app settings were not recovered.'
    Assert-Check ((Invoke-Query 'SELECT GROUP_CONCAT(password_hash ORDER BY id) FROM users') -eq $accountsBefore) 'Repair changed application login passwords.'
    $accountDamage = "ALTER USER 'smarthire'@'localhost' IDENTIFIED BY 'Disposable-test-only!'; REVOKE ALL PRIVILEGES ON smarthire.* FROM 'smarthire'@'localhost';"
    $accountDamage | & mysql "--defaults-extra-file=$adminFile" --batch *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not simulate lost app access.' }
    & "$clientScripts/repair-local.ps1" -Offline
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM client_reset_probe') -eq 1) 'Access repair lost custom data.'
    $privateSettings = Get-Content "$local/database.json" -Raw
    $privateApp = Get-Content "$local/mysql-app.ini" -Raw
    Remove-Item -LiteralPath "$local/database.json", "$local/mysql-app.ini"
    $refused = $false
    try { & "$clientScripts/repair-local.ps1" -Offline } catch { $refused = $_.Exception.Message -like '*Both application credential copies are missing*' }
    finally { $privateSettings | Set-Content "$local/database.json"; $privateApp | Set-Content "$local/mysql-app.ini" }
    Assert-Check $refused 'Repair guessed missing credentials.'
    $privateAdmin = Get-Content -LiteralPath $adminFile -Raw
    Copy-Item -LiteralPath (Join-Path $project '.local/mysql-admin.ini') -Destination $adminFile
    $refused = $false
    try { & "$clientScripts/repair-local.ps1" -Offline } catch { $refused = $_.Exception.Message -like '*different MySQL server*' }
    finally { $privateAdmin | Set-Content -LiteralPath $adminFile }
    Assert-Check $refused 'Repair accepted another server as the disposable instance.'
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM client_reset_probe') -eq 1) 'Refused recovery changed custom data.'
    function Read-Host { return '' }
    & "$clientScripts/client-setup.ps1" -Reset -Offline
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM client_reset_probe') -eq 1) 'Cancelled reset changed data.'
    function Read-Host { return 'RESET' }
    $ownershipFile = Join-Path $local 'client-server.json'
    $ownership = Get-Content -LiteralPath $ownershipFile -Raw
    $wrongOwner = $ownership | ConvertFrom-Json
    $wrongOwner.project = 'C:\unrelated-project'
    $wrongOwner | ConvertTo-Json | Set-Content -LiteralPath $ownershipFile
    $refused = $false
    try { & "$clientScripts/client-setup.ps1" -Reset -Offline } catch {
        $refused = $_.Exception.Message -like '*ownership record*'
    } finally { $ownership | Set-Content -LiteralPath $ownershipFile }
    Assert-Check $refused 'Reset accepted a different project ownership record.'
    $lock = [IO.File]::Open("$local/documents/store.lock", 'OpenOrCreate', 'ReadWrite', 'None')
    $refused = $false
    try { & "$clientScripts/client-setup.ps1" -Reset -Offline } catch {
        $refused = $_.Exception.Message -like '*document store open*'
    } finally { $lock.Dispose() }
    Assert-Check $refused 'Reset accepted an open application document store.'
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM client_reset_probe') -eq 1) 'Refused reset changed database data.'
    & "$clientScripts/client-setup.ps1" -Reset -Offline
    Assert-Check ((Invoke-Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='smarthire' AND table_name='client_reset_probe'") -eq 0) 'Reset retained custom database data.'
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM users') -ge 8) 'Reset failed to restore demo.'
    Assert-Check (-not (Test-Path "$local/documents/reset-probe.txt")) 'Reset retained uploaded local file.'
    Assert-Check ((Get-Content "$local/aiven.properties") -eq 'cloud sentinel') 'Reset changed cloud configuration.'
    Assert-Check (Test-Path "$local/aiven-documents/preserve.txt") 'Reset changed cloud documents.'
    # Reproduce the exact desktop failure: initialized server but missing main database.
    'DROP DATABASE smarthire;' | & mysql "--defaults-extra-file=$adminFile" --batch *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not simulate a missing disposable main schema.' }
    $retainedCv = Join-Path $local 'documents/12345678-1234-1234-1234-123456789abc.pdf'
    New-Item -ItemType Directory -Force (Split-Path $retainedCv -Parent) | Out-Null
    'fictional retained bytes' | Set-Content -LiteralPath $retainedCv
    $refused = $false
    try { & "$clientScripts/repair-local.ps1" -Offline } catch { $refused = $_.Exception.Message -like '*managed CV/circular files remain*' }
    Assert-Check ($refused -and (Test-Path -LiteralPath $retainedCv)) 'Missing-schema repair risked retained CV files.'
    Remove-Item -LiteralPath $retainedCv
    & "$clientScripts/start-client-netbeans.ps1" -CheckOnly -NetBeansPath "$project/.tools/netbeans/bin/netbeans64.exe"
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM users') -ge 8) 'Startup did not recover and seed the missing main database.'
    & "$clientScripts/stop-mysql.ps1"
    $blocker = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $blocker.Start()
        $refused = $false
        try { & "$clientScripts/start-mysql.ps1" } catch { $refused = $_.Exception.Message -like '*occupied*' }
        Assert-Check $refused 'Startup did not report a conflicting port.'
    } finally { $blocker.Stop() }
    & "$clientScripts/start-client-netbeans.ps1" -CheckOnly -NetBeansPath "$project/.tools/netbeans/bin/netbeans64.exe"
    Assert-Check (Test-Path "$local/application.properties") 'Restart launcher did not write app configuration.'
    Assert-Check ((Invoke-Query 'SELECT COUNT(*) FROM users') -ge 8) 'Restart launcher could not restart server.'
    Write-Host 'PASS: setup/reset/restart, missing database recovery, credential-copy recovery, app account/grant repair, data/password retention, lost-credential/foreign-server/occupied-port refusal and cloud retention.'
    Write-Host "Isolated rehearsal retained at $sandbox (contains private credentials; do not share)."
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Remove-Item Function:\Read-Host -ErrorAction SilentlyContinue
    if (Test-Path "$local/mysql-admin.ini") {
        try { & "$clientScripts/stop-mysql.ps1" }
        catch { Write-Warning 'Disposable server was already stopped or cleanup needs attention; the original verification outcome is retained.' }
    }
}
