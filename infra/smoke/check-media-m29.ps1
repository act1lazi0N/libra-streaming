#requires -Version 7.0
# M29 end-to-end playback through the repository ingress: the REAL infra/compose.yaml (--profile full) and
# infra/nginx/nginx.conf, the Core and Media JARs from clean verify, Core's own playback signer, Media's worker and
# outbox, Kafka, PostgreSQL and SeaweedFS. Every key is generated here and lives only in this process's environment.
# No workstation .env or exported Compose variable takes part: Compose reads an empty env file, and every variable the
# Compose files interpolate is cleared before the smoke sets its own. The disposable projects' containers, volumes,
# networks and built images are removed afterwards, pass or fail.
# -Milestone 30 adds the M30 path-abuse matrix to phase 1 and runs media_m30_http.py (the M29 slice, then M30's abuse,
# cache and expiry checks) in phase 2; check-media-m30.ps1 passes it.
# -Milestone 33 skips phase 1 and drives the slice from a real browser instead (check-media-m33.ps1 passes it): the
# ingress on a loopback port fixed before startup so Core's public URL and Media's storage CORS origin name the page's
# exact origin, a static stand-in for the web upstream serving web/e2e/m33/harness with the pinned hls.js, and
# web/e2e/m33-vertical-slice.spec.ts run by Playwright in the installed Google Chrome.
# -Milestone 34 skips phase 1 and runs media_m34_faults.py (check-media-m34.ps1 passes it): the fault matrix stops,
# kills, pauses and restarts this project's own services while it drives the slice through the ingress.
param([string]$Python = 'python', [ValidateSet('29', '30', '33', '34')][string]$Milestone = '29')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = "libra-m$Milestone-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$verification = Join-Path $repository 'target/verification'
$emptyEnv = Join-Path $verification 'm29-empty.env'
$compose = @('compose', '--project-name', $project, '--profile', 'full', '--env-file', $emptyEnv,
    '--file', (Join-Path $repository 'infra/compose.yaml'), '--file', (Join-Path $PSScriptRoot 'compose.media-m29.yaml'))
$browser = $Milestone -eq '33'
if ($browser) { $compose += @('--file', (Join-Path $PSScriptRoot 'compose.browser-m33.yaml')) }
$harness = Join-Path $verification 'm33-harness'
$ingressCompose = @('compose', '--project-name', ($project + '-ingress'), '--env-file', $emptyEnv,
    '--file', (Join-Path $PSScriptRoot 'compose.ingress-m29.yaml'))
$evidence = @((Join-Path $verification 'ingress-m29.json'), (Join-Path $verification "media-m$Milestone.json"))
if ($Milestone -eq '30') { $evidence += Join-Path $verification 'ingress-m30.json' }
if ($browser) { $evidence = @(Join-Path $verification 'media-m33.json') }
$faults = $Milestone -eq '34'
if ($faults) { $evidence = @(Join-Path $verification 'media-m34.json') }
# Every variable the Compose files interpolate, plus the smoke's own.
# Playwright's debug switches would print evaluated arguments and cookies; they are cleared like the rest.
$names = @('LIBRA_M29_ADMIN_PASSWORD', 'LIBRA_M29_ENV_FILE', 'LIBRA_M33_BASE_URL', 'LIBRA_M33_MAIL_URL',
    'LIBRA_M33_STORAGE_ORIGIN', 'LIBRA_M33_EVIDENCE', 'LIBRA_M33_WORK', 'LIBRA_M33_ADMIN_EMAIL',
    'LIBRA_M33_ADMIN_PASSWORD', 'DEBUG', 'DEBUG_FILE', 'PWDEBUG', 'PW_TEST_HTML_REPORT_OPEN',
    'PLAYWRIGHT_BROWSERS_PATH') + @(
    Get-Content -Raw (Join-Path $repository 'infra/compose.yaml'), (Join-Path $PSScriptRoot 'compose.media-m29.yaml'),
        (Join-Path $PSScriptRoot 'compose.browser-m33.yaml') |
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

    if ($browser) {
        # The harness, the pinned player build and the installed Chrome must all be present; none may be skipped.
        $player = Get-Content -Raw (Join-Path $repository 'web/node_modules/hls.js/package.json') -ErrorAction SilentlyContinue |
            ConvertFrom-Json -ErrorAction SilentlyContinue
        $pinned = (Get-Content -Raw (Join-Path $repository 'web/package.json') | ConvertFrom-Json).devDependencies.'hls.js'
        if ($null -eq $player -or $player.version -ne $pinned) { throw 'Run pnpm install in web/ before the M33 smoke.' }
        if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'ffmpeg must be on PATH for the M33 clip.' }
        if (Test-Path -LiteralPath $harness) { Remove-Item -LiteralPath $harness -Recurse -Force }
        New-Item -ItemType Directory -Force (Join-Path $harness 'm33') | Out-Null
        Copy-Item (Join-Path $repository 'web/e2e/m33/harness/*') (Join-Path $harness 'm33')
        Copy-Item (Join-Path $repository 'web/node_modules/hls.js/dist/hls.min.js') (Join-Path $harness 'm33')
        Set-Smoke 'LIBRA_M33_HARNESS' $harness
    }

    # Phase 1: the ingress's stream location and limits against a slow stand-in for Media.
    $ingressFailed = $false
    if (-not ($browser -or $faults)) { try {
        & docker @ingressCompose up -d --wait --wait-timeout 60
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress check environment did not start.' }
        & docker @ingressCompose exec -T nginx nginx -t
        if ($LASTEXITCODE -ne 0) { throw 'Repository Nginx configuration is invalid.' }
        $address = (& docker @ingressCompose port nginx 80 | Select-Object -Last 1).Trim()
        & $Python infra/smoke/ingress_m29_limits.py --base-url "http://$address" --evidence $evidence[0]
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress limit check failed.' }
        if ($Milestone -eq '30') {
            & $Python infra/smoke/ingress_m30_paths.py --base-url "http://$address" --evidence $evidence[2]
            if ($LASTEXITCODE -ne 0) { throw 'M30 ingress path check failed.' }
        }
        & docker @ingressCompose stop media-stub
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the Media stand-in.' }
        & $Python infra/smoke/ingress_m29_limits.py --base-url "http://$address" --upstream-down
        if ($LASTEXITCODE -ne 0) { throw 'M29 ingress check with Media down failed.' }
    } finally {
        & docker @ingressCompose down --volumes --rmi local --remove-orphans --timeout 5
        $ingressFailed = $LASTEXITCODE -ne 0
    } }
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
    if ($browser) {
        # The page's origin: Core's links and Media's storage CORS rule name it exactly.
        $ingressPort = Free-Port
        Set-Smoke 'LIBRA_M33_INGRESS_PORT' "$ingressPort"
        Set-Smoke 'CORE_PUBLIC_BASE_URL' "http://127.0.0.1:$ingressPort"
        Set-Smoke 'MEDIA_BROWSER_ORIGIN' "http://127.0.0.1:$ingressPort"
    }
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
    $services = @('postgres', 'kafka', 'mailpit', 'seaweedfs', 's3-gateway', 'core', 'media', 'nginx')
    if ($browser) { $services += 'web' }
    Invoke-Compose up -d --build --wait --wait-timeout 240 @services
    Invoke-Compose exec -T nginx nginx -t
    $ingress = (Invoke-Compose port nginx 80 | Select-Object -Last 1).Trim()
    $mail = (Invoke-Compose port mailpit 8025 | Select-Object -Last 1).Trim()
    if ($browser) {
        & $Python infra/smoke/media_m33_ready.py --base-url "http://$ingress"
        if ($LASTEXITCODE -ne 0) { throw 'M33 services did not become ready through the ingress.' }
        Set-Smoke 'LIBRA_M33_BASE_URL' "http://$ingress"
        Set-Smoke 'LIBRA_M33_MAIL_URL' "http://$mail"
        Set-Smoke 'LIBRA_M33_STORAGE_ORIGIN' $env:MEDIA_S3_BROWSER_ENDPOINT
        Set-Smoke 'LIBRA_M33_EVIDENCE' $evidence[0]
        Set-Smoke 'LIBRA_M33_WORK' (Join-Path $verification 'm33')
        Set-Smoke 'LIBRA_M33_ADMIN_EMAIL' $env:CORE_BOOTSTRAP_ADMIN_EMAIL
        Set-Smoke 'LIBRA_M33_ADMIN_PASSWORD' $env:LIBRA_M29_ADMIN_PASSWORD
        # Node, Playwright and Chrome get only what the spec reads: every other smoke variable, the generated private
        # keys among them, is withheld for the run and restored for Compose's own cleanup.
        $withheld = @{}
        foreach ($name in $names) {
            if ($name -notlike 'LIBRA_M33_*') {
                $withheld[$name] = [Environment]::GetEnvironmentVariable($name)
                Set-Smoke $name $null
            }
        }
        $pnpm = if ($IsWindows) { 'pnpm.cmd' } else { 'pnpm' }
        Push-Location (Join-Path $repository 'web')
        try { & $pnpm exec playwright test } finally {
            Pop-Location
            foreach ($name in $withheld.Keys) { Set-Smoke $name $withheld[$name] }
        }
        if ($LASTEXITCODE -ne 0) { throw 'M33 browser vertical slice failed.' }
    } else {
        $driver = if ($faults) { 'infra/smoke/media_m34_faults.py' } else { "infra/smoke/media_m$($Milestone)_http.py" }
        & $Python $driver --base-url "http://$ingress" --mail-url "http://$mail" --project $project
        if ($LASTEXITCODE -ne 0) { throw "M$Milestone end-to-end playback smoke failed." }
    }
} catch {
    Remove-Evidence
    if ($started) {
        # Local, git-ignored diagnostics. Spring can echo a rejected property value, so treat this file as secret
        # material for the (already discarded) keys of this run.
        & docker @compose logs --no-color core media 2>&1 |
            Out-File -Encoding utf8 (Join-Path $repository "tmp/m$Milestone-smoke-services.log")
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
    if (Test-Path -LiteralPath $harness) {
        Remove-Item -LiteralPath $harness -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $harness) { $cleanupFailed = $true }
    }
    if ($cleanupFailed) {
        Remove-Evidence
        throw "M29 smoke cleanup failed for project $project; remove that project explicitly."
    }
}
