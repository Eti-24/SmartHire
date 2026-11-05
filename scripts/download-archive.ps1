param([string]$Url, [string]$Destination)
$ErrorActionPreference='Stop'
$head=Invoke-WebRequest -Uri $Url -Method Head -TimeoutSec 30
$length=[long]($head.Headers.'Content-Length' | Select-Object -First 1)
if ($length -le 0) { throw 'Missing download length.' }
$partial="$Destination.partial"
$parts="$Destination.parts"
New-Item -ItemType Directory -Path $parts -Force | Out-Null
$offset=0L
if (Test-Path -LiteralPath $partial) { $offset=[long]([Math]::Floor((Get-Item -LiteralPath $partial).Length / 1MB) * 1MB) }
$starts=@(for ($start=$offset; $start -lt $length; $start+=1MB) { $start })
$results=$starts | ForEach-Object -Parallel {
    $ErrorActionPreference='Stop'
    $start=[long]$_
    $end=[Math]::Min($start + 1MB - 1, $using:length - 1)
    $part=Join-Path $using:parts "$start.bin"
    if (-not (Test-Path -LiteralPath $part) -or (Get-Item -LiteralPath $part).Length -ne $end-$start+1) {
        $success=$false
        for ($attempt=0; $attempt -lt 3; $attempt++) {
            try {
                $r=Invoke-WebRequest -Uri $using:Url -Headers @{Range="bytes=$start-$end"} -TimeoutSec 30
                if ($r.StatusCode -ne 206 -or [string]$r.Headers.'Content-Range' -notlike "bytes $start-$end/*" -or $r.RawContentStream.Length -ne $end-$start+1) { throw 'Invalid range response.' }
                $r.RawContentStream.Position=0
                $file=[IO.File]::Create($part)
                try { $r.RawContentStream.CopyTo($file) } finally { $file.Dispose() }
                $success=$true
                break
            } catch { if ($attempt -eq 2) { throw } }
        }
        if (-not $success) { throw "Failed at $start" }
    }
    $start
} -ThrottleLimit 4
if ($results.Count -ne $starts.Count) { throw 'Not all download parts completed.' }
$output=[IO.File]::Open($partial,[IO.FileMode]::OpenOrCreate)
try {
    $output.SetLength($offset)
    $output.Position=$offset
    foreach ($start in $starts) {
        $part=Join-Path $parts "$start.bin"
        $partStream=[IO.File]::OpenRead($part)
        try { $partStream.CopyTo($output) } finally { $partStream.Dispose() }
    }
    if ($output.Length -ne $length) { throw 'Assembled file length mismatch.' }
} finally { $output.Dispose() }
# Keep chunks for recovery until the caller has verified the complete checksum.
