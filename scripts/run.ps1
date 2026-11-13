param([switch]$SeedDemo, [switch]$Cloud, [switch]$BuildOnly, [switch]$UpgradeDatabase, [switch]$Offline)
$ErrorActionPreference = 'Stop'
if ($UpgradeDatabase -and (!$Cloud -or $SeedDemo -or $BuildOnly)) {
    throw 'Use -Cloud -UpgradeDatabase by itself to prepare the cloud database.'
}
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$smartHireProject = Split-Path $PSScriptRoot -Parent
Push-Location $smartHireProject
try {
    $smartHireJavaOptions = @()
    if ($Cloud) {
        if (-not (Test-Path -LiteralPath '.local/aiven.properties' -PathType Leaf)) {
            throw 'Cloud database configuration is missing. See docs/reproduce-demo.md.'
        }
        $smartHireJavaOptions += '-Dsmarthire.config=.local/aiven.properties', '-Dsmarthire.documents=.local/aiven-documents', '-Dsmarthire.requireCloud=true'
    } else {
        if (-not $BuildOnly) {
            & "$PSScriptRoot/repair-local.ps1" -BuildRuntime -SeedDemo:$SeedDemo -Offline:$Offline
            if (-not $SeedDemo) {
                $localRuntime = (Get-Content target/runtime-classpath.txt -Raw).Trim()
                & java -cp "target/classes;$localRuntime" smarthire.Main
                if ($LASTEXITCODE -ne 0) { throw 'SmartHire did not complete successfully.' }
            }
            return
        }
        & "$PSScriptRoot/write-local-config.ps1"
    }
    $buildArguments = @('--batch-mode', '--no-transfer-progress')
    if ($Offline) { $buildArguments += '-o' }
    $buildArguments += @('package', 'dependency:build-classpath', '-Dmdep.outputFile=target/runtime-classpath.txt', '-DincludeScope=runtime')
    & mvn @buildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    if ($BuildOnly) { return }
    $smartHireRuntime = (Get-Content target/runtime-classpath.txt -Raw).Trim()
    if ($UpgradeDatabase) {
        & java @smartHireJavaOptions -cp "target/classes;$smartHireRuntime" smarthire.Main --upgrade-database
    } elseif ($SeedDemo) {
        & java @smartHireJavaOptions -cp "target/classes;$smartHireRuntime" smarthire.Main --seed-demo
    } else {
        & java @smartHireJavaOptions -cp "target/classes;$smartHireRuntime" smarthire.Main
    }
    if ($LASTEXITCODE -ne 0) { throw 'SmartHire did not complete successfully.' }
} finally {
    Pop-Location
}
