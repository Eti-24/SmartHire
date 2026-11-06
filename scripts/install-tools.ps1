#Requires -Version 7.0
param([switch]$RuntimeOnly)

$ErrorActionPreference = 'Stop'
$toolRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools'
$downloads = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null

$jdk = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0].binary.package
$items = @(
    @{ Name = $jdk.name; Url = $jdk.link; Algorithm = 'SHA256'; Hash = $jdk.checksum },
    @{ Name = 'apache-maven-3.10.0-bin.zip'; Url = 'https://dlcdn.apache.org/maven/maven-3/3.10.0/binaries/apache-maven-3.10.0-bin.zip'; Algorithm = 'SHA512'; HashUrl = 'https://downloads.apache.org/maven/maven-3/3.10.0/binaries/apache-maven-3.10.0-bin.zip.sha512' },
    @{ Name = 'mysql-8.4.13-winx64.zip'; Url = 'https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.13-winx64.zip'; Algorithm = 'MD5'; Hash = '7b94489d5524dea9b27097068bea25a8' }
)
if (-not $RuntimeOnly) {
    $items += @(
        @{ Name = 'netbeans-31-bin.zip'; Url = 'https://dlcdn.apache.org/netbeans/netbeans/31/netbeans-31-bin.zip'; Algorithm = 'SHA512'; HashUrl = 'https://downloads.apache.org/netbeans/netbeans/31/netbeans-31-bin.zip.sha512' },
        @{ Name = 'mysql-workbench-26.10.0-winx64.msi'; Url = 'https://cdn.mysql.com/Downloads/MySQLGUITools/mysql-workbench-26.10.0-winx64.msi'; Algorithm = 'MD5'; Hash = '1987bb5a22f37d4603d651fb290c951d' }
    )
}
foreach ($item in $items) {
    if ($item.HashUrl) {
        $response = Invoke-WebRequest $item.HashUrl
        $item.Hash = ([regex]::Match([string]$response.Content, '(?i)[a-f0-9]{128}')).Value
        if (-not $item.Hash) { throw "No checksum returned for $($item.Name)" }
    }
}
$items | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $toolRoot 'download-manifest.json')
$downloader = Join-Path $PSScriptRoot 'download-archive.ps1'
$items | ForEach-Object -Parallel {
    $ErrorActionPreference = 'Stop'
    $item = $_
    $destination = Join-Path $using:downloads $item.Name
    if (-not (Test-Path -LiteralPath $destination)) {
        Write-Output "Downloading $($item.Name)"
        & $using:downloader -Url $item.Url -Destination $destination
        $actual = (Get-FileHash -LiteralPath "$destination.partial" -Algorithm $item.Algorithm).Hash
        if ($actual -ne $item.Hash) { throw "Checksum mismatch: $($item.Name)" }
        Move-Item -LiteralPath "$destination.partial" -Destination $destination
    }
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm $item.Algorithm).Hash
    if ($actual -ne $item.Hash) { throw "Checksum mismatch: $($item.Name)" }
    Write-Output "Verified $($item.Name)"
} -ThrottleLimit 3

foreach ($item in $items | Where-Object { $_.Name -like '*.zip' }) {
    $archive = Join-Path $downloads $item.Name
    $expected = switch -Wildcard ($item.Name) {
        'OpenJDK*' { 'jdk-21*' }
        'apache-maven*' { 'apache-maven-3.10.0' }
        'netbeans*' { 'netbeans' }
        'mysql-*' { 'mysql-8.4.13-winx64' }
    }
    $binary = switch -Wildcard ($item.Name) {
        'OpenJDK*' { 'bin/javac.exe' }
        'apache-maven*' { 'bin/mvn.cmd' }
        'netbeans*' { 'bin/netbeans64.exe' }
        'mysql-*' { 'bin/mysqld.exe' }
    }
    $installed = Get-ChildItem -LiteralPath $toolRoot -Directory -Filter $expected |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName $binary) -PathType Leaf }
    if (-not $installed) {
        Write-Output "Extracting $($item.Name)"
        & tar.exe -xf $archive -C $toolRoot
        if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $($item.Name)" }
    }
}
Write-Output "Tools installed under $toolRoot."
if (-not $RuntimeOnly) { Write-Output 'The Workbench MSI is ready to install separately.' }
