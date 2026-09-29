param()

$ErrorActionPreference = 'Stop'
$project = 'libra-media-m05-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = Join-Path $PSScriptRoot 'compose.media-m05.yaml'
$report = Join-Path $root 'target/verification/media-m05.json'
$env:LIBRA_M05_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$env:LIBRA_M05_S3_ACCESS = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
$env:LIBRA_M05_S3_SECRET = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()

Push-Location $root
try {
    & docker compose -p $project -f $compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Media M05 Compose configuration failed' }

    & docker compose -p $project -f $compose up -d --build 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Media M05 packaged stack failed to start' }

    $passed = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $logs = (& docker compose -p $project -f $compose logs --no-color media 2>&1) -join "`n"
        if ($logs.Contains('MEDIA_STORAGE_INITIALIZATION_PASS') -and
                $logs.Contains('MEDIA_STORAGE_PROBE_PASS')) {
            $passed = $true
            break
        }
        $running = (& docker compose -p $project -f $compose ps --status running --services) -join "`n"
        if (-not $running.Contains('media')) { throw 'Packaged Media exited before storage probe passed' }
        Start-Sleep -Seconds 2
    }
    if (-not $passed) { throw 'Packaged Media storage probe timed out' }

    $negative = (& docker compose -p $project -f $compose run --rm --no-deps `
        -e MEDIA_S3_SECRET_KEY= -e MEDIA_STORAGE_INITIALIZE_BUCKETS=false `
        -e MEDIA_STORAGE_PROBE_ON_STARTUP=false media 2>&1) -join "`n"
    if ($LASTEXITCODE -eq 0 -or -not $negative.Contains('Media storage configuration: secret key is required')) {
        throw 'Packaged Media did not reject missing storage credentials as expected'
    }

    $directory = Split-Path -Parent $report
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    [pscustomobject]@{
        milestone = 'M05'
        fixture = 'packaged Media + PostgreSQL 18.6 + SeaweedFS 4.46'
        storageRoundTrip = 'pass'
        bucketCorsInitialization = 'pass'
        missingCredentialRejection = 'pass'
        exposedHostPorts = 0
    } | ConvertTo-Json | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output 'MEDIA_M05_SMOKE_PASS'
} finally {
    & docker compose -p $project -f $compose down --remove-orphans 2>&1 | Out-Null
    Remove-Item Env:LIBRA_M05_DB_PASSWORD, Env:LIBRA_M05_S3_ACCESS, Env:LIBRA_M05_S3_SECRET -ErrorAction SilentlyContinue
    Pop-Location
}
