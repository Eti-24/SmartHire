$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$settings = Get-Content -LiteralPath (Join-Path $project '.local/database.json') -Raw | ConvertFrom-Json
$port = if ($settings.port) { $settings.port } else { 3306 }
"port=$port`nuser=$($settings.user)`npassword=$($settings.password)" |
    Set-Content -LiteralPath (Join-Path $project '.local/application.properties') -Encoding ascii
