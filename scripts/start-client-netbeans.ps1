#Requires -Version 7.0
param([string]$NetBeansPath, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$savedPath = Join-Path $project '.local/netbeans-path.txt'
if (-not (Test-Path (Join-Path $project '.local/my.ini'))) { throw 'Run Client-Setup.cmd first.' }
if (-not $NetBeansPath -and (Test-Path -LiteralPath $savedPath)) {
    $NetBeansPath = (Get-Content -LiteralPath $savedPath -Raw).Trim()
}
if (-not $NetBeansPath) {
    $candidates = @()
    if ($env:NETBEANS_HOME) { $candidates += Join-Path $env:NETBEANS_HOME 'bin/netbeans64.exe' }
    $command = Get-Command netbeans64.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($folder in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($folder -and (Test-Path -LiteralPath $folder)) {
            $candidates += Get-ChildItem -LiteralPath $folder -Directory |
                Where-Object { $_.Name -match 'NetBeans' } |
                ForEach-Object { Join-Path $_.FullName 'netbeans/bin/netbeans64.exe'; Join-Path $_.FullName 'bin/netbeans64.exe' }
        }
    }
    $NetBeansPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if (-not $NetBeansPath -or -not (Test-Path -LiteralPath $NetBeansPath -PathType Leaf)) {
    if ($CheckOnly) { throw 'NetBeans was not found. Pass -NetBeansPath with the existing netbeans64.exe path.' }
    Add-Type -AssemblyName System.Windows.Forms
    $picker = [Windows.Forms.OpenFileDialog]::new()
    $picker.Title = 'Select your installed NetBeans executable (inside its bin folder)'
    $picker.Filter = 'NetBeans (netbeans64.exe;netbeans.exe)|netbeans64.exe;netbeans.exe'
    try {
        if ($picker.ShowDialog() -ne 'OK') { throw 'NetBeans selection cancelled. No IDE was opened.' }
        $NetBeansPath = $picker.FileName
    } finally { $picker.Dispose() }
}
if ([IO.Path]::GetFileName($NetBeansPath) -notin @('netbeans64.exe', 'netbeans.exe')) {
    throw 'Select netbeans64.exe or netbeans.exe from your existing NetBeans installation.'
}
$NetBeansPath = (Resolve-Path -LiteralPath $NetBeansPath).Path
$NetBeansPath | Set-Content -LiteralPath $savedPath -Encoding utf8NoBOM
. "$PSScriptRoot/dev-env.ps1"
& "$PSScriptRoot/repair-local.ps1"
if ($CheckOnly) { Write-Host 'Database ready; local configuration and existing NetBeans path verified.'; return }
# Use the installation's existing profile so its normal visible instance can receive the launch.
# A separate --userdir can route every retry to an earlier background IDE holding that profile lock.
$arguments = @('--jdkhome', "`"$env:JAVA_HOME`"", '-J-Xmx768m')
$ide = Start-Process -FilePath $NetBeansPath -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Normal -PassThru
# NetBeans CLIHandler.Status.CONNECTED is -252: a successful handoff to an existing IDE.
# https://github.com/apache/netbeans/blob/master/platform/o.n.bootstrap/src/org/netbeans/CLIHandler.java
if ($ide.WaitForExit(2000) -and $ide.ExitCode -notin @(0, -252)) {
    throw "NetBeans exited with code $($ide.ExitCode). Open the selected NetBeans executable directly to check its Java requirements, or remove .local/netbeans-path.txt to select the correct installation."
}
Write-Host "MySQL is ready. NetBeans launch requested using its existing profile. If it is already open, check its taskbar window."
Write-Host "In NetBeans use File > Open Project > $project (first launch), then Run Project."
