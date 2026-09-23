#requires -Version 7.0
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'libra-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$compose = @('compose', '--project-name', $project, '--file', (Join-Path $PSScriptRoot 'compose.core.yaml'))
$secretNames = @('LIBRA_SMOKE_JWT_KEY', 'LIBRA_SMOKE_MAIL_KEY', 'LIBRA_SMOKE_PRIVATE_KEY', 'LIBRA_SMOKE_PUBLIC_KEY', 'LIBRA_SMOKE_ADMIN_PASSWORD')
$previous = @{}
$started = $false
$evidence = Join-Path $repository 'target/verification/smoke.json'
foreach ($name in $secretNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
function Invoke-Compose {
    & docker @compose @args
    if ($LASTEXITCODE -ne 0) { throw 'Core smoke Compose operation failed. Inspect the local Docker environment.' }
}
function Random-Key { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
Push-Location $repository
try {
    if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
    if (-not (Test-Path 'services/core/target/core-service-0.1.0-SNAPSHOT.jar')) {
        throw 'Run root Maven clean verify before this smoke check.'
    }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker is required; smoke checks cannot be skipped.' }
    $env:LIBRA_SMOKE_JWT_KEY = Random-Key
    $env:LIBRA_SMOKE_MAIL_KEY = Random-Key
    $env:LIBRA_SMOKE_ADMIN_PASSWORD = Random-Key
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        $env:LIBRA_SMOKE_PRIVATE_KEY = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey())
        $env:LIBRA_SMOKE_PUBLIC_KEY = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
    } finally { $rsa.Dispose() }
    Invoke-Compose config --quiet
    $started = $true
    Invoke-Compose up -d --build --wait --wait-timeout 180
    Invoke-Compose exec -T nginx nginx -t
    $apiAddress = (Invoke-Compose port nginx 80 | Select-Object -Last 1).Trim()
    $mailAddress = (Invoke-Compose port mailpit 8025 | Select-Object -Last 1).Trim()
    & $Python infra/smoke/core_http.py --base-url "http://$apiAddress" --mail-url "http://$mailAddress" --project $project
    if ($LASTEXITCODE -ne 0) { throw 'Core HTTP/infrastructure smoke failed.' }
} catch {
    if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
    throw
} finally {
    # Only this invocation's randomly named project is removed, including its
    # disposable PostgreSQL anonymous volume. No workstation stack is touched.
    $cleanupFailed = $false
    if ($started) {
        & docker @compose down --volumes --remove-orphans --timeout 15
        $cleanupFailed = $LASTEXITCODE -ne 0
    }
    foreach ($name in $secretNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
    Pop-Location
    if ($cleanupFailed) {
        if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
        throw "Smoke cleanup failed for project $project; remove that project explicitly."
    }
}
