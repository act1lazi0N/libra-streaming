param()

$ErrorActionPreference = 'Stop'
$project = 'libra-media-m07-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = Join-Path $PSScriptRoot 'compose.media-m07.yaml'
$report = Join-Path $root 'target/verification/media-m07-ingress.json'
$results = [ordered]@{}
$names = @('SEAWEEDFS_ACCESS_KEY', 'SEAWEEDFS_SECRET_KEY', 'CORE_MEDIA_CONTROL_ENABLED',
    'MEDIA_CORE_SERVICE_AUTH_ENABLED', 'CORE_MEDIA_CONTROL_PRIVATE_KEY', 'CORE_MEDIA_CONTROL_PUBLIC_KEY',
    'CORE_MEDIA_CONTROL_KEY_ID')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

Push-Location $root
try {
    # A failed rerun must not retain a previous PASS artifact.
    if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report }
    # Synthetic configuration canaries, not keys used to authenticate a running service.
    $env:SEAWEEDFS_ACCESS_KEY = 'm07-storage-access-canary'
    $env:SEAWEEDFS_SECRET_KEY = 'm07-storage-secret-canary'
    $env:CORE_MEDIA_CONTROL_ENABLED = 'true'
    $env:MEDIA_CORE_SERVICE_AUTH_ENABLED = 'true'
    $env:CORE_MEDIA_CONTROL_PRIVATE_KEY = 'm07-private-key-canary'
    $env:CORE_MEDIA_CONTROL_PUBLIC_KEY = 'm07-public-key-canary'
    $env:CORE_MEDIA_CONTROL_KEY_ID = 'm07-key'
    $rendered = (& docker compose --env-file .env.example -f infra/compose.yaml --profile full config --format json) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'M07 main Compose render failed' }
    $model = $rendered | ConvertFrom-Json
    $mediaEnvironment = $model.services.media.environment
    if ($mediaEnvironment.MEDIA_CORE_SERVICE_PUBLIC_KEY -ne $env:CORE_MEDIA_CONTROL_PUBLIC_KEY -or
        $mediaEnvironment.MEDIA_CORE_SERVICE_KEY_ID -ne $env:CORE_MEDIA_CONTROL_KEY_ID -or
        $mediaEnvironment.MEDIA_CORE_SERVICE_AUTH_ENABLED -ne 'true' -or
        ($mediaEnvironment | ConvertTo-Json -Compress).Contains($env:CORE_MEDIA_CONTROL_PRIVATE_KEY) -or
        $model.services.core.environment.CORE_MEDIA_CONTROL_PRIVATE_KEY -ne $env:CORE_MEDIA_CONTROL_PRIVATE_KEY -or
        $model.services.media.ports) {
        throw 'M07 Compose service-key separation failed'
    }
    $results.composePublicKeyOnly = 'PASS'
    & docker compose -p $project -f $compose up -d --wait *> $null
    if ($LASTEXITCODE -ne 0) { throw 'M07 ingress fixture failed to start' }
    & docker compose -p $project -f $compose exec -T nginx nginx -t *> $null
    if ($LASTEXITCODE -ne 0) { throw 'M07 Nginx configuration failed validation' }
    $address = (& docker compose -p $project -f $compose port nginx 80) -join ''
    if ($LASTEXITCODE -ne 0 -or -not $address.StartsWith('127.0.0.1:')) { throw 'M07 fixture must bind loopback' }
    $baseline = Invoke-WebRequest "http://$address/api/media/unprotected-probe" -SkipHttpErrorCheck -TimeoutSec 5
    if ($baseline.StatusCode -ne 418) { throw 'M07 upstream control probe did not reach the stub' }
    $count = 0
    foreach ($method in @('GET', 'PUT', 'POST', 'DELETE')) {
        foreach ($path in @('/api/media/internal/v1/uploads/11111111-1111-1111-1111-111111111111',
                '/api/media/%69nternal/v1/uploads/11111111-1111-1111-1111-111111111111',
                '/api/media//internal/v1/uploads/11111111-1111-1111-1111-111111111111')) {
            $response = Invoke-WebRequest "http://$address$path`?canary=m07-query-canary" -Method $method `
                -Headers @{Authorization = 'Bearer m07-header-canary'} -SkipHttpErrorCheck -TimeoutSec 5
            if ($response.StatusCode -ne 404) { throw 'M07 private Media route reached a public upstream' }
            $count++
        }
    }
    $logs = (& docker compose -p $project -f $compose logs --no-color) -join "`n"
    if ($logs.Contains('m07-query-canary') -or $logs.Contains('m07-header-canary')) { throw 'M07 ingress logged credentials' }
    $results.nginxPrivateRouteDenials = $count
    $results.ingressCredentialCanariesAbsent = 'PASS'
    $results.boundary = 'Real Nginx; explicit HTTP upstream stubs. No upload workflow or production TLS claim.'
    New-Item -ItemType Directory -Force (Split-Path $report) | Out-Null
    $results | ConvertTo-Json | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output 'MEDIA_M07_INGRESS_PASS'
} finally {
    & docker compose -p $project -f $compose down -v --remove-orphans *> $null
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Pop-Location
}
