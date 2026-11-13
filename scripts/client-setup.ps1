#Requires -Version 7.0
param([switch]$Reset, [ValidateRange(1, 65535)][int]$Port = 3307, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$local = Join-Path $project '.local'
$marker = Join-Path $local 'client-server.json'
$data = Join-Path $local 'mysql-data'

function Assert-PrivatePath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not $absolute.StartsWith([IO.Path]::GetFullPath($local) + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) { throw 'Reset target is outside this project .local directory.' }
    # Refuse junctions/symlinks at every level, including parent directories of the project.
    $ancestor = $absolute
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing reset through a linked path: $ancestor"
            }
        }
        $ancestor = Split-Path $ancestor -Parent
    }
    if (Test-Path -LiteralPath $absolute -PathType Container) {
        if (Get-ChildItem -LiteralPath $absolute -Force -Recurse |
                Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
            throw 'Refusing reset because a target contains a linked path.'
        }
    }
}

if ($Reset) {
    if (-not (Test-Path -LiteralPath $marker)) {
        throw 'Reset is available only for a dedicated server created by Client-Setup.cmd. Existing unmarked servers are retained.'
    }
    $owned = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
    if ($owned.version -ne 1 -or $owned.project -ne $project -or $owned.data -ne $data) {
        throw 'The server ownership record does not match this project. No reset performed.'
    }
    . "$PSScriptRoot/dev-env.ps1"
    $admin = Join-Path $local 'mysql-admin.ini'
    $Port = [int]$owned.port
    if (Test-Path (Join-Path $local 'database.json')) {
        $config = Get-Content (Join-Path $local 'database.json') -Raw | ConvertFrom-Json
        $Port = [int]$config.port
    }
    if ($Port -lt 1 -or $Port -gt 65535) { throw 'Invalid dedicated-server port. No reset performed.' }
    $targets = @('mysql-data', 'documents', 'my.ini', 'mysql-admin.ini', 'mysql-app.ini',
        'database.json', 'application.properties', 'mysql-first-start.sql', 'mysql-error.log', 'client-server.json') |
        ForEach-Object { Join-Path $local $_ }
    foreach ($target in $targets) { Assert-PrivatePath $target }
    # Check the server identity before stopping it. Never stop a service by a guessed name.
    $reachable = $false
    if (Test-Path -LiteralPath $admin) {
        $actualData = & mysql "--defaults-extra-file=$admin" --connect-timeout=3 --batch --skip-column-names --execute='SELECT @@datadir' 2>$null
        $reachable = $LASTEXITCODE -eq 0
    }
    if ($reachable) {
        if ([IO.Path]::GetFullPath(([string]$actualData).Trim()).TrimEnd('\', '/') -ne $data.TrimEnd('\', '/')) {
            throw 'Connected server is not this project server. No reset performed.'
        }
    } else {
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
        try { $probe.Server.ExclusiveAddressUse = $true; $probe.Start() }
        catch { throw 'The configured port is occupied but server ownership could not be verified. No reset performed.' }
        finally { $probe.Stop() }
    }
    Write-Host 'Close SmartHire and stop running it from NetBeans before continuing.'
    Write-Host 'RESET permanently erases this dedicated local server, both SmartHire schemas and local CV/circular files.'
    Write-Host 'XAMPP and cloud configuration are retained. Export anything you need before continuing.'
    if ((Read-Host 'Type RESET to restore the initial demo, or press Enter to cancel') -cne 'RESET') {
        Write-Host 'Reset cancelled.'
        return
    }
    $documentLock = Join-Path $local 'documents/store.lock'
    if (Test-Path -LiteralPath $documentLock) {
        try { $handle = [IO.File]::Open($documentLock, 'Open', 'ReadWrite', 'None'); $handle.Dispose() }
        catch { throw 'SmartHire still has its document store open. Close the application before resetting. No deletion performed.' }
    }
    if ($reachable) { & "$PSScriptRoot/stop-mysql.ps1" }
    # mysqladmin returns after shutdown; retry a short file-lock check before deleting.
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $lockFile = Join-Path $data 'ibdata1'
            if (Test-Path $lockFile) { $handle = [IO.File]::Open($lockFile, 'Open', 'ReadWrite', 'None'); $handle.Dispose() }
            break
        } catch {
            if ($attempt -eq 19) { throw 'Server files remain locked. Reset stopped before deletion.' }
            Start-Sleep -Milliseconds 500
        }
    }
    foreach ($target in $targets) {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    }
}

$fresh = -not (Test-Path -LiteralPath (Join-Path $data 'mysql'))
# Record ownership before initialization so an interrupted setup can be explicitly reset.
if ($fresh -and -not (Test-Path -LiteralPath (Join-Path $local 'mysql-admin.ini'))) {
    New-Item -ItemType Directory -Path $local -Force | Out-Null
    @{version=1; project=$project; data=$data; port=$Port; seeded=$false} | ConvertTo-Json |
        Set-Content -LiteralPath $marker -Encoding utf8NoBOM
}
$seed = $fresh
$record = $null
if (Test-Path -LiteralPath $marker) {
    $record = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
    if ($record.project -eq $project -and $record.data -eq $data -and $record.seeded -ne $true) { $seed = $true }
}
& "$PSScriptRoot/setup-local.ps1" -Port $Port -SeedDemo:$seed -Offline:$Offline
if ($record -and $record.project -eq $project -and $record.data -eq $data) {
    $record | Add-Member -NotePropertyName seeded -NotePropertyValue $true -Force
    $record | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding utf8NoBOM
}
Write-Host 'Ready. Ordinary setup reruns retain existing data. Use Client-Setup.cmd --reset only for a fresh demo.'
Write-Host 'After restarting your PC, use Start-SmartHire-NetBeans.cmd, then run SmartHire in NetBeans.'
