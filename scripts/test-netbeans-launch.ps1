#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$sandbox = Join-Path $project ('target/ide launch check ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path "$sandbox/scripts", "$sandbox/.local", "$sandbox/Existing IDE/bin" | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/start-client-netbeans.ps1" -Destination "$sandbox/scripts"
New-Item -ItemType File -Path "$sandbox/.local/my.ini", "$sandbox/Existing IDE/bin/netbeans64.exe" | Out-Null
'$env:JAVA_HOME = ''C:\Java Tools\jdk-21''' | Set-Content "$sandbox/scripts/dev-env.ps1"
'$global:smartHireReadinessCalled = $true' | Set-Content "$sandbox/scripts/repair-local.ps1"
$global:smartHireLaunchCalls = @()
$global:smartHireLaunchMode = 'running'
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle, [switch]$PassThru)
    if (-not $global:smartHireReadinessCalled) { throw 'IDE started before readiness.' }
    $global:smartHireLaunchCalls += [pscustomobject]@{FilePath=$FilePath; Arguments=$ArgumentList; Directory=$WorkingDirectory; Style=$WindowStyle}
    $exitCode = switch ($global:smartHireLaunchMode) { 'failure' { 2 }; 'handoff' { -252 }; default { 0 } }
    $process = [pscustomobject]@{ExitCode=$exitCode}
    $process | Add-Member ScriptMethod WaitForExit { param($milliseconds) return $global:smartHireLaunchMode -ne 'running' }
    return $process
}
$previousJava = $env:JAVA_HOME
try {
    & "$sandbox/scripts/start-client-netbeans.ps1" -NetBeansPath "$sandbox/Existing IDE/bin/netbeans64.exe"
    $call = $global:smartHireLaunchCalls[-1]
    if ($call.Arguments -contains '--userdir' -or $call.Arguments -contains '--cachedir') {
        throw 'FAIL: launcher forces a separate NetBeans profile instead of the existing IDE profile.'
    }
    if ($call.Style -ne 'Normal' -or $call.Directory -ne $sandbox -or
            $call.Arguments[1] -ne '"C:\Java Tools\jdk-21"') { throw 'IDE launch lost visible window, working directory or quoted Java path.' }
    $global:smartHireLaunchMode = 'handoff'
    & "$sandbox/scripts/start-client-netbeans.ps1" # Exercises privately saved IDE path and a successful handoff.
    $global:smartHireLaunchMode = 'exited'
    & "$sandbox/scripts/start-client-netbeans.ps1" # Some installed launchers report a handoff as zero.
    $global:smartHireLaunchMode = 'failure'
    $refused = $false
    try { & "$sandbox/scripts/start-client-netbeans.ps1" } catch { $refused = $_.Exception.Message -like '*NetBeans exited*' }
    if (-not $refused) { throw 'FAIL: immediate NetBeans startup failure was reported as success.' }
    $count = $global:smartHireLaunchCalls.Count
    & "$sandbox/scripts/start-client-netbeans.ps1" -CheckOnly
    if ($global:smartHireLaunchCalls.Count -ne $count) { throw 'CheckOnly launched an IDE.' }
    'PASS: existing IDE profile, visible launch, spaces, saved path, readiness ordering, running/handoff/error outcomes and CheckOnly.'
} finally {
    $env:JAVA_HOME = $previousJava
    Remove-Item Function:\Start-Process
    Remove-Variable smartHireLaunchCalls, smartHireLaunchMode, smartHireReadinessCalled -Scope Global -ErrorAction SilentlyContinue
}
