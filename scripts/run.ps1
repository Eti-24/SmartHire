param([switch]$SeedDemo, [switch]$Cloud)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$smartHireProject = Split-Path $PSScriptRoot -Parent
Push-Location $smartHireProject
try {
    $smartHireJavaOptions = @()
    if ($Cloud) {
        if (-not (Test-Path -LiteralPath '.local/aiven.properties' -PathType Leaf)) {
            throw 'Cloud database configuration is missing. See docs/reproduce-demo.md.'
        }
        $smartHireJavaOptions += '-Dsmarthire.config=.local/aiven.properties', '-Dsmarthire.documents=.local/aiven-documents'
    } else {
        $smartHireDatabase = Get-Content .local/database.json -Raw | ConvertFrom-Json
        # The setup generates ASCII credentials. Keep this file private and outside source control.
        "user=$($smartHireDatabase.user)`npassword=$($smartHireDatabase.password)" |
            Set-Content .local/application.properties -Encoding ascii
    }
    & mvn --batch-mode --no-transfer-progress package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    $smartHireRuntime = (Get-Content target/runtime-classpath.txt -Raw).Trim()
    if ($SeedDemo) {
        & java @smartHireJavaOptions -cp "target/classes;$smartHireRuntime" smarthire.Main --seed-demo
    } else {
        & java @smartHireJavaOptions -cp "target/classes;$smartHireRuntime" smarthire.Main
    }
    if ($LASTEXITCODE -ne 0) { throw 'SmartHire did not complete successfully.' }
} finally {
    Pop-Location
}
