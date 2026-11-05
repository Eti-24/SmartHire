param([switch]$CleanDatabaseSmoke, [switch]$Offline)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$smartHireProject = Split-Path $PSScriptRoot -Parent
Push-Location $smartHireProject
try {
    $smartHireArguments = @('--batch-mode', '--no-transfer-progress', '-Dsmarthire.integration=true')
    if ($Offline) { $smartHireArguments += '-o' }
    if ($CleanDatabaseSmoke) {
        if (-not (Test-Path -LiteralPath '.local/mysql-admin.ini')) { throw 'Clean smoke needs .local/mysql-admin.ini, or run the documented Maven command with an alternate smoke admin config.' }
        $smartHireArguments += '-Dsmarthire.cleanSmoke=true'
    }
    $smartHireArguments += @('package', 'dependency:build-classpath', '-Dmdep.outputFile=target/runtime-classpath.txt', '-DincludeScope=runtime')
    & mvn @smartHireArguments
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed. Review target/surefire-reports.' }
} finally { Pop-Location }
