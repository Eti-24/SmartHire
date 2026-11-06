param([switch]$Offline, [switch]$Cloud)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $version = ([xml](Get-Content -Raw pom.xml)).project.version
    $edition = if ($Cloud) { 'cloud' } else { 'local' }
    $name = "SmartHire-$version-$edition-windows"
    $staging = Join-Path (Get-Location) "target/release-$([guid]::NewGuid().ToString('N'))"
    $bundle = Join-Path $staging $name
    $libraries = Join-Path $bundle 'lib'
    $archive = Join-Path (Get-Location) "target/$name.zip"
    $mavenArgs = @('--batch-mode', '--no-transfer-progress')
    if ($Offline) { $mavenArgs += '-o' }
    $mavenArgs += @('package', 'dependency:copy-dependencies', '-DincludeScope=runtime', "-DoutputDirectory=$libraries")
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }

    Copy-Item "target/smarthire-$version.jar" (Join-Path $bundle 'smarthire.jar')
    if ($Cloud) {
        Copy-Item scripts/SmartHire-Cloud.cmd, scripts/aiven.properties.example $bundle
    } else {
        Copy-Item scripts/SmartHire.cmd $bundle
    }
    Copy-Item docs/windows-release.md (Join-Path $bundle 'README.md')
    Copy-Item -Recurse -LiteralPath 'demo data' -Destination (Join-Path $bundle 'demo data')
    Copy-Item output/pdf/demo-job-circular.pdf (Join-Path $bundle 'demo data')
    Compress-Archive -LiteralPath $bundle -DestinationPath $archive -Force
    Write-Output $archive
} finally {
    Pop-Location
}
