# Runs a local Redis for development WITHOUT Docker, by reusing the native
# redis-server binary bundled in the embedded-redis test dependency. Useful when
# Docker Desktop is unavailable. Ctrl+C to stop.
#
#   ./infra/scripts/run-local-redis.ps1 [-Port 6379]

param([int]$Port = 6379)

$ErrorActionPreference = "Stop"

$jar = Get-ChildItem "$HOME\.m2\repository\com\github\codemonstur\embedded-redis" -Recurse -Filter "embedded-redis-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Select-Object -First 1

if (-not $jar) {
    Write-Error "embedded-redis jar not found. Run 'mvn -q -f backend/pom.xml test-compile' once to download it."
}

$dest = Join-Path $env:TEMP "tessera-redis"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$exe = Join-Path $dest "redis-server.exe"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq "redis-server-5.0.14.1-windows-amd64.exe" }
    if (-not $entry) { Write-Error "Windows redis binary not found in $($jar.Name)" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $exe, $true)
} finally {
    $zip.Dispose()
}

Write-Host "Starting redis-server on port $Port (Ctrl+C to stop)..."
& $exe --port $Port --save "" --appendonly no
