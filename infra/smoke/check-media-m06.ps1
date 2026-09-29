param()

$ErrorActionPreference = 'Stop'
$project = 'libra-media-m06-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = Join-Path $PSScriptRoot 'compose.media-m06.yaml'
$report = Join-Path $root 'target/verification/media-m06.json'
$env:LIBRA_M06_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$env:LIBRA_M06_S3_ACCESS = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
$env:LIBRA_M06_S3_SECRET = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$sensitive = @($env:LIBRA_M06_DB_PASSWORD, $env:LIBRA_M06_S3_ACCESS, $env:LIBRA_M06_S3_SECRET,
    'm06-query-canary', 'm06-header-canary', 'm06-malformed-secret-canary', 'm06-endpoint-canary')
$results = [ordered]@{}

function Assert-Sanitized([string]$value) {
    foreach ($needle in $sensitive) {
        if ($value.Contains($needle)) { throw 'M06 sensitive value found in captured output (value withheld)' }
    }
}

function Wait-Ready {
    $address = (& docker compose -p $project -f $compose port nginx 80) -join ''
    if ($LASTEXITCODE -ne 0 -or -not $address.StartsWith('127.0.0.1:')) { throw 'M06 ingress must bind loopback' }
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri "http://$address/api/media/actuator/health/readiness" -TimeoutSec 8 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'M06 packaged readiness timed out'
}

function Test-MainCompose {
    $savedAccess = $env:SEAWEEDFS_ACCESS_KEY
    $savedSecret = $env:SEAWEEDFS_SECRET_KEY
    try {
        Remove-Item Env:SEAWEEDFS_ACCESS_KEY, Env:SEAWEEDFS_SECRET_KEY -ErrorAction SilentlyContinue
        & docker compose --env-file .env.example -f infra/compose.yaml config --quiet 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { throw 'M06 main Compose accepted missing credentials' }
        $env:SEAWEEDFS_ACCESS_KEY = $env:LIBRA_M06_S3_ACCESS
        $env:SEAWEEDFS_SECRET_KEY = $env:LIBRA_M06_S3_SECRET
        $rendered = (& docker compose --env-file .env.example -f infra/compose.yaml --profile full config --format json) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'M06 main Compose rejected configured credentials' }
        $model = $rendered | ConvertFrom-Json
        if ($model.services.seaweedfs.ports -or @($model.services.seaweedfs.networks.PSObject.Properties).Count -ne 1 -or
            -not $model.networks.'storage-private'.internal -or $model.services.nginx.networks.'storage-private' -or
            $model.services.media.networks.'storage-private' -or
            $model.services.'s3-gateway'.ports[0].host_ip -ne '127.0.0.1') {
            throw 'M06 main Compose storage isolation regressed'
        }
        $results.mainComposeIsolation = 'PASS'
    } finally {
        $env:SEAWEEDFS_ACCESS_KEY = $savedAccess
        $env:SEAWEEDFS_SECRET_KEY = $savedSecret
    }
}

Push-Location $root
try {
    New-Item -ItemType Directory -Force -Path (Split-Path $report) | Out-Null
    @{ milestone = 'M06'; status = 'RUNNING' } | ConvertTo-Json | Set-Content $report
    Test-MainCompose
    & docker compose -p $project -f $compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'M06 invalid Compose configuration' }
    & docker compose -p $project -f $compose up -d --build 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M06 packaged fixture startup failed' }
    Wait-Ready
    $storageId = (& docker compose -p $project -f $compose ps -q seaweedfs) -join ''
    $inventory = (& docker inspect $storageId | ConvertFrom-Json)[0]
    if (@($inventory.NetworkSettings.Networks.PSObject.Properties).Count -ne 1 -or
        @($inventory.HostConfig.PortBindings.PSObject.Properties).Count -ne 0) {
        throw 'M06 SeaweedFS has unexpected network or host exposure'
    }
    $env:LIBRA_M06_PRIVATE_IP = $inventory.NetworkSettings.Networks.PSObject.Properties.Value.IPAddress
    $http = (& docker compose -p $project -f $compose run --rm --no-deps probe 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or -not $http.Contains('MEDIA_M06_HTTP_PASS')) {
        $safeStage = [regex]::Match($http, 'MEDIA_M06_HTTP_FAIL: [a-z-]+').Value
        throw "M06 HTTP matrix failed: $safeStage"
    }
    Assert-Sanitized $http
    $results.httpAccessMatrix = 'PASS'
    $gateway = (& docker compose -p $project -f $compose port s3-gateway 8333) -join ''
    if (-not $gateway.StartsWith('127.0.0.1:')) { throw 'M06 S3 gateway must bind loopback' }
    foreach ($bucket in @('libra-source', 'libra-hls')) {
        $response = Invoke-WebRequest -Uri "http://$gateway/$bucket`?list-type=2" -SkipHttpErrorCheck -TimeoutSec 8
        if ($response.StatusCode -ne 403) { throw 'M06 host-loopback anonymous LIST was not denied' }
    }
    $results.hostLoopback = 'PASS'

    $cases = @(
        @{ name = 'missingSecret'; setting = 'MEDIA_S3_SECRET_KEY='; code = 'secret key is required' },
        @{ name = 'malformedSecret'; setting = 'MEDIA_S3_SECRET_KEY=m06-malformed-secret-canary bad'; code = 'secret key is malformed' },
        @{ name = 'credentialInEndpoint'; setting = 'MEDIA_S3_INTERNAL_ENDPOINT=http://user:m06-endpoint-canary@s3-gateway:8333'; code = 'internal endpoint must be an allowed HTTP(S) origin' },
        @{ name = 'malformedEndpoint'; setting = 'MEDIA_S3_INTERNAL_ENDPOINT=http://user:m06-endpoint-canary bad@s3-gateway:8333'; code = 'internal endpoint must be an allowed HTTP(S) origin' },
        @{ name = 'mismatchedSecret'; setting = "MEDIA_S3_SECRET_KEY=$($env:LIBRA_M06_DB_PASSWORD)"; code = 'MEDIA_STORAGE_ACCESS_DENIED' },
        @{ name = 'unknownAccessKey'; setting = 'MEDIA_S3_ACCESS_KEY=unknown-fixture'; code = 'MEDIA_STORAGE_ACCESS_DENIED' },
        @{ name = 'wrongEndpoint'; setting = 'MEDIA_S3_INTERNAL_ENDPOINT=http://s3-gateway:8888'; code = 'MEDIA_STORAGE_UNAVAILABLE' },
        @{ name = 'missingBucket'; setting = 'MEDIA_HLS_BUCKET=absent-bucket'; code = 'MEDIA_STORAGE_UNAVAILABLE' }
    )
    foreach ($case in $cases) {
        $negative = (& docker compose -p $project -f $compose run --rm --no-deps `
            -e $case.setting -e MEDIA_STORAGE_INITIALIZE_BUCKETS=false `
            -e MEDIA_STORAGE_PROBE_ON_STARTUP=false media 2>&1) -join "`n"
        $code = $LASTEXITCODE
        Assert-Sanitized $negative
        if ($code -eq 0 -or -not $negative.Contains($case.code)) { throw "M06 negative case failed: $($case.name)" }
        $results[$case.name] = 'PASS'
        Write-Output "MEDIA_M06_CASE_PASS $($case.name)"
    }

    & docker compose -p $project -f $compose stop s3-gateway 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M06 fixture outage setup failed' }
    $down = (& docker compose -p $project -f $compose run --rm --no-deps probe down 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or -not $down.Contains('MEDIA_M06_OUTAGE_PASS')) { throw 'M06 outage readiness failed' }
    & docker compose -p $project -f $compose start s3-gateway 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M06 fixture recovery setup failed' }
    Wait-Ready
    $results.outageAndRecovery = 'PASS'
    $logs = (& docker compose -p $project -f $compose logs --no-color 2>&1) -join "`n"
    Assert-Sanitized $logs
    if (-not $logs.Contains('MEDIA_STORAGE_PROBE_PASS')) { throw 'M06 storage round-trip evidence missing' }
    $results.logRedaction = 'PASS'
    $results.storageRoundTrip = 'PASS'
    [ordered]@{ milestone = 'M06'; status = 'PASS'; results = $results;
        fixture = 'packaged Media, PostgreSQL 18.6, SeaweedFS 4.46, real Nginx configs';
        mocked = 'Core, Web, Analytics HTTP upstreams'; productionTlsVerified = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $report -Encoding utf8
    Write-Output 'MEDIA_M06_SMOKE_PASS'
} catch {
    @{ milestone = 'M06'; status = 'FAIL'; completed = $results } |
        ConvertTo-Json -Depth 5 | Set-Content $report -Encoding utf8
    throw
} finally {
    & docker compose -p $project -f $compose down --volumes --remove-orphans 2>&1 | Out-Null
    Remove-Item Env:LIBRA_M06_DB_PASSWORD, Env:LIBRA_M06_S3_ACCESS, Env:LIBRA_M06_S3_SECRET,
        Env:LIBRA_M06_PRIVATE_IP -ErrorAction SilentlyContinue
    Pop-Location
}
