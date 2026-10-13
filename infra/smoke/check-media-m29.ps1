#requires -Version 7.0
# M29 end-to-end playback through the repository ingress: the REAL infra/compose.yaml (--profile full) and
# infra/nginx/nginx.conf, the Core and Media JARs from clean verify, Core's own playback signer, Media's worker and
# outbox, Kafka, PostgreSQL and SeaweedFS. Every key is generated here and lives only in this process's environment.
# No workstation .env or exported Compose variable takes part: Compose reads an empty env file, and every variable the
# Compose files interpolate is cleared before the smoke sets its own. The disposable projects' containers, volumes,
# networks and built images are removed afterwards, pass or fail.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'libra-m29-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$verification = Join-Path $repository 'target/verification'
$emptyEnv = Join-Path $verification 'm29-empty.env'
$compose = @('compose', '--project-name', $project, '--profile', 'full', '--env-file', $emptyEnv,
    '--file', (Join-Path $repository 'infra/compose.yaml'), '--file', (Join-Path $PSScriptRoot 'compose.media-m29.yaml'))
$ingressCompose = @('compose', '--project-name', ($project + '-ingress'), '--env-file', $emptyEnv,
    '--file', (Join-Path $PSScriptRoot 'compose.ingress-m29.yaml'))
$evidence = @((Join-Path $verification 'ingress-m29.json'), (Join-Path $verification 'media-m29.json'))
# Every variable the Compose files interpolate, plus the smoke's own.
$names = @('LIBRA_M29_ADMIN_PASSWORD', 'LIBRA_M29_ENV_FILE') + @(
    Get-Content -Raw (Join-Path $repository 'infra/compose.yaml'), (Join-Path $PSScriptRoot 'compose.media-m29.yaml') |
        Select-String -AllMatches '\$\{([A-Z0-9_]+)' | ForEach-Object { $_.Matches } |
        ForEach-Object { $_.Groups[1].Value }) | Sort-Object -Unique
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
$started = $false
function Set-Smoke([string]$name, [string]$value) { [Environment]::SetEnvironmentVariable($name, $value) }
function Random-Key { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
function Pair {
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        return @([Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()),
                 [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()))
    } finally { $rsa.Dispose() }
}
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Invoke-Compose {
    & docker @compose @args
    if ($LASTEXITCODE -ne 0) { throw 'M29 Compose operation failed. Inspect the local Docker environment.' }
}
function Remove-Evidence { foreach ($file in $evidence) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file } } }
Push-Location $repository
try {
    Remove-Evidence
    New-Item -ItemType Directory -Force $verification, (Join-Path $repository 'tmp') | Out-Null
    Set-Content -LiteralPath $emptyEnv -Value '' -NoNewline
    foreach ($name in $names) { Set-Smoke $name $null }
    Set-Smoke 'LIBRA_M29_ENV_FILE' $emptyEnv
    foreach ($jar in 'services/core/target/core-service-0.1.0-SNAPSHOT.jar',
                     'services/media/target/media-service-0.1.0-SNAPSHOT.jar') {
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Run root Maven clean verify before the M29 smoke.' }
    }
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is required; the M29 smoke cannot be skipped.' }

    # Phase 1: the ingress's stream location and limits against a slow stand-in for Media.
    $ingressFailed = $false
    try {
        & docker @ingressCompose up -d --wait --wait-timeout 60
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress check environment did not start.' }
        & docker @ingressCompose exec -T nginx nginx -t
        if ($LASTEXITCODE -ne 0) { throw 'Repository Nginx configuration is invalid.' }
        $address = (& docker @ingressCompose port nginx 80 | Select-Object -Last 1).Trim()
        & $Python infra/smoke/ingress_m29_limits.py --base-url "http://$address" --evidence $evidence[0]
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress limit check failed.' }
        & docker @ingressCompose stop media-stub
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the Media stand-in.' }
        & $Python infra/smoke/ingress_m29_limits.py --base-url "http://$address" --upstream-down
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress check with Media down failed.' }
    } finally {
        & docker @ingressCompose down --volumes --rmi local --remove-orphans --timeout 5
        $ingressFailed = $LASTEXITCODE -ne 0
    }
    if ($ingressFailed) { throw "M29 ingress check cleanup failed for project $project-ingress." }

    # Phase 2: the whole slice through the same ingress.
    $playback = Pair
    $control = Pair
    $binding = Pair
    Set-Smoke 'SEAWEEDFS_ACCESS_KEY' ('m29' + [guid]::NewGuid().ToString('N'))
    Set-Smoke 'SEAWEEDFS_SECRET_KEY' ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
    $s3Port = Free-Port
    Set-Smoke 'LIBRA_M29_S3_PORT' "$s3Port"
    Set-Smoke 'MEDIA_S3_BROWSER_ENDPOINT' "http://127.0.0.1:$s3Port"
    Set-Smoke 'MEDIA_STORAGE_INITIALIZE_BUCKETS' 'true'
    Set-Smoke 'LIBRA_M29_ADMIN_PASSWORD' (Random-Key)
    Set-Smoke 'CORE_JWT_KEY' (Random-Key)
    Set-Smoke 'CORE_MAIL_ENCRYPTION_KEY' (Random-Key)
    # Everything not set below keeps its Compose default, including CORE_COOKIE_SECURE=true, so the __Secure- ticket
    # cookie is what is tested. Plain HTTP to Media on the internal network needs the loopback development mode.
    Set-Smoke 'CORE_LOCAL_DEVELOPMENT' 'true'
    Set-Smoke 'CORE_PUBLIC_BASE_URL' 'http://localhost'
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_EMAIL' 'admin@m29.example.test'
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_PASSWORD' $env:LIBRA_M29_ADMIN_PASSWORD
    Set-Smoke 'CORE_PLAYBACK_PRIVATE_KEY' $playback[0]
    Set-Smoke 'CORE_PLAYBACK_PUBLIC_KEY' $playback[1]
    Set-Smoke 'CORE_PLAYBACK_KEY_ID' 'm29-playback'
    Set-Smoke 'CORE_MEDIA_CONTROL_ENABLED' 'true'
    Set-Smoke 'CORE_MEDIA_CONTROL_PRIVATE_KEY' $control[0]
    Set-Smoke 'CORE_MEDIA_CONTROL_PUBLIC_KEY' $control[1]
    Set-Smoke 'CORE_MEDIA_CONTROL_KEY_ID' 'm29-core-control'
    Set-Smoke 'CORE_MEDIA_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'CORE_MEDIA_SERVICE_PUBLIC_KEY' $binding[1]
    Set-Smoke 'CORE_MEDIA_SERVICE_KEY_ID' 'm29-media-binding'
    Set-Smoke 'MEDIA_CORE_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'MEDIA_PLAYBACK_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_BINDING_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_BINDING_PRIVATE_KEY' $binding[0]
    Set-Smoke 'MEDIA_CORE_BINDING_PUBLIC_KEY' $binding[1]
    Set-Smoke 'MEDIA_CORE_BINDING_KEY_ID' 'm29-media-binding'
    Invoke-Compose config --quiet
    $started = $true
    Invoke-Compose up -d --build --wait --wait-timeout 240 postgres kafka mailpit seaweedfs s3-gateway core media nginx
    Invoke-Compose exec -T nginx nginx -t
    $ingress = (Invoke-Compose port nginx 80 | Select-Object -Last 1).Trim()
    $mail = (Invoke-Compose port mailpit 8025 | Select-Object -Last 1).Trim()
    & $Python infra/smoke/media_m29_http.py --base-url "http://$ingress" --mail-url "http://$mail" --project $project
    if ($LASTEXITCODE -ne 0) { throw 'M29 end-to-end playback smoke failed.' }
} catch {
    Remove-Evidence
    if ($started) {
        # Local, git-ignored diagnostics. Spring can echo a rejected property value, so treat this file as secret
        # material for the (already discarded) keys of this run.
        & docker @compose logs --no-color core media 2>&1 | Out-File -Encoding utf8 (Join-Path $repository 'tmp/m29-smoke-services.log')
    }
    throw
} finally {
    $cleanupFailed = $false
    if ($started) {
        & docker @compose down --volumes --rmi local --remove-orphans --timeout 15
        $cleanupFailed = $LASTEXITCODE -ne 0
    }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
    if (Test-Path -LiteralPath $emptyEnv) { Remove-Item -LiteralPath $emptyEnv }
    Pop-Location
    if ($cleanupFailed) {
        Remove-Evidence
        throw "M29 smoke cleanup failed for project $project; remove that project explicitly."
    }
}
