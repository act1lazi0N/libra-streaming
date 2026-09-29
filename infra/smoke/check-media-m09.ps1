#requires -Version 7.0
param([string]$Python = 'tmp/milestone8-tools/Scripts/python.exe', [ValidateSet('M09','M10')][string]$Scenario = 'M09')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'libra-' + $Scenario.ToLowerInvariant() + '-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$composeFile = Join-Path $PSScriptRoot 'compose.media-m09.yaml'
$compose = @('compose', '--project-name', $project, '--file', $composeFile)
$evidence = Join-Path $repository ('target/verification/media-' + $Scenario.ToLowerInvariant() + '.json')
$logDirectory = Join-Path $repository ('target/verification/' + $Scenario.ToLowerInvariant() + '-processes')
$coreProcess = $null
$mediaProcess = $null
$started = $false
$names = @(
    'CORE_DB_URL','CORE_DB_USERNAME','CORE_DB_PASSWORD','CORE_PORT','CORE_JWT_KEY',
    'CORE_MAIL_ENCRYPTION_KEY','CORE_LOCAL_DEVELOPMENT','CORE_COOKIE_SECURE','CORE_PUBLIC_BASE_URL',
    'CORE_BOOTSTRAP_ADMIN_EMAIL','CORE_BOOTSTRAP_ADMIN_PASSWORD','CORE_MAIL_WORKER_ENABLED',
    'CORE_MEDIA_LISTENER_ENABLED','CORE_OUTBOX_PUBLISHER_ENABLED','CORE_DLT_LISTENER_ENABLED',
    'CORE_PLAYBACK_PRIVATE_KEY','CORE_PLAYBACK_PUBLIC_KEY','CORE_PLAYBACK_KEY_ID',
    'CORE_MEDIA_CONTROL_ENABLED','CORE_MEDIA_CONTROL_BASE_URL','CORE_MEDIA_CONTROL_PRIVATE_KEY',
    'CORE_MEDIA_CONTROL_PUBLIC_KEY','CORE_MEDIA_CONTROL_KEY_ID','CORE_MEDIA_SERVICE_AUTH_ENABLED',
    'CORE_MEDIA_SERVICE_PUBLIC_KEY','CORE_MEDIA_SERVICE_KEY_ID',
    'MEDIA_DB_URL','MEDIA_DB_USERNAME','MEDIA_DB_PASSWORD','MEDIA_PORT','MEDIA_STORAGE_ENABLED',
    'MEDIA_CORE_SERVICE_AUTH_ENABLED','MEDIA_CORE_SERVICE_PUBLIC_KEY','MEDIA_CORE_SERVICE_KEY_ID',
    'MEDIA_CORE_BINDING_ENABLED','MEDIA_CORE_BINDING_BASE_URL','MEDIA_CORE_BINDING_PRIVATE_KEY',
    'MEDIA_CORE_BINDING_PUBLIC_KEY','MEDIA_CORE_BINDING_KEY_ID','MEDIA_CORE_BINDING_ALLOW_HTTP',
    'LIBRA_M09_ADMIN_PASSWORD','LIBRA_M09_POSTGRES_PASSWORD'
)
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
function Set-Smoke([string]$name, [string]$value) { [Environment]::SetEnvironmentVariable($name, $value) }
function Random-Key { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
function Pair {
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        return @(
            [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()),
            [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
        )
    } finally { $rsa.Dispose() }
}
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
Push-Location $repository
try {
    if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $coreJar = Join-Path $repository 'services/core/target/core-service-0.1.0-SNAPSHOT.jar'
    $mediaJar = Join-Path $repository 'services/media/target/media-service-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $coreJar) -or -not (Test-Path -LiteralPath $mediaJar)) {
        throw 'Build both service jars before the M09 smoke check.'
    }
    $java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'JDK 21 runtime is required.' }
    Set-Smoke 'LIBRA_M09_ADMIN_PASSWORD' (Random-Key)
    Set-Smoke 'LIBRA_M09_POSTGRES_PASSWORD' (Random-Key)
    $started = $true
    & docker @compose up -d --wait --wait-timeout 90
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL did not start.' }
    $databasePort = ((& docker @compose port postgres 5432 | Select-Object -Last 1).Trim() -split ':')[-1]
    if ($LASTEXITCODE -ne 0 -or $databasePort -notmatch '^[0-9]+$') { throw 'Could not resolve disposable PostgreSQL port.' }
    $corePort = Free-Port
    $mediaPort = Free-Port
    $proxyPort = Free-Port
    if ((@($corePort, $mediaPort, $proxyPort) | Select-Object -Unique).Count -ne 3) { throw 'Smoke service ports collided.' }
    $playback = Pair
    $control = Pair
    $binding = Pair
    Set-Smoke 'CORE_DB_URL' "jdbc:postgresql://127.0.0.1:$databasePort/libra_core"
    Set-Smoke 'CORE_DB_USERNAME' 'libra_core'
    Set-Smoke 'CORE_DB_PASSWORD' 'core_local'
    Set-Smoke 'CORE_PORT' "$corePort"
    Set-Smoke 'CORE_JWT_KEY' (Random-Key)
    Set-Smoke 'CORE_MAIL_ENCRYPTION_KEY' (Random-Key)
    Set-Smoke 'CORE_LOCAL_DEVELOPMENT' 'true'
    Set-Smoke 'CORE_COOKIE_SECURE' 'false'
    Set-Smoke 'CORE_PUBLIC_BASE_URL' "http://127.0.0.1:$corePort"
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_EMAIL' 'admin@m09.example.test'
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_PASSWORD' $env:LIBRA_M09_ADMIN_PASSWORD
    Set-Smoke 'CORE_MAIL_WORKER_ENABLED' 'false'
    Set-Smoke 'CORE_MEDIA_LISTENER_ENABLED' 'false'
    Set-Smoke 'CORE_OUTBOX_PUBLISHER_ENABLED' 'false'
    Set-Smoke 'CORE_DLT_LISTENER_ENABLED' 'false'
    Set-Smoke 'CORE_PLAYBACK_PRIVATE_KEY' $playback[0]
    Set-Smoke 'CORE_PLAYBACK_PUBLIC_KEY' $playback[1]
    Set-Smoke 'CORE_PLAYBACK_KEY_ID' 'm09-playback'
    Set-Smoke 'CORE_MEDIA_CONTROL_ENABLED' 'true'
    if ($Scenario -eq 'M10') { Set-Smoke 'CORE_MEDIA_CONTROL_BASE_URL' "http://127.0.0.1:$proxyPort" }
    else { Set-Smoke 'CORE_MEDIA_CONTROL_BASE_URL' "http://127.0.0.1:$mediaPort" }
    Set-Smoke 'CORE_MEDIA_CONTROL_PRIVATE_KEY' $control[0]
    Set-Smoke 'CORE_MEDIA_CONTROL_PUBLIC_KEY' $control[1]
    Set-Smoke 'CORE_MEDIA_CONTROL_KEY_ID' 'm09-core-control'
    Set-Smoke 'CORE_MEDIA_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'CORE_MEDIA_SERVICE_PUBLIC_KEY' $binding[1]
    Set-Smoke 'CORE_MEDIA_SERVICE_KEY_ID' 'm09-media-binding'
    Set-Smoke 'MEDIA_DB_URL' "jdbc:postgresql://127.0.0.1:$databasePort/libra_media"
    Set-Smoke 'MEDIA_DB_USERNAME' 'libra_media'
    Set-Smoke 'MEDIA_DB_PASSWORD' 'media_local'
    Set-Smoke 'MEDIA_PORT' "$mediaPort"
    Set-Smoke 'MEDIA_STORAGE_ENABLED' 'false'
    Set-Smoke 'MEDIA_CORE_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_SERVICE_PUBLIC_KEY' $control[1]
    Set-Smoke 'MEDIA_CORE_SERVICE_KEY_ID' 'm09-core-control'
    Set-Smoke 'MEDIA_CORE_BINDING_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_BINDING_BASE_URL' "http://127.0.0.1:$corePort"
    Set-Smoke 'MEDIA_CORE_BINDING_PRIVATE_KEY' $binding[0]
    Set-Smoke 'MEDIA_CORE_BINDING_PUBLIC_KEY' $binding[1]
    Set-Smoke 'MEDIA_CORE_BINDING_KEY_ID' 'm09-media-binding'
    Set-Smoke 'MEDIA_CORE_BINDING_ALLOW_HTTP' 'true'
    $coreArguments = '-Djdk.net.unixdomain.tmpdir="' + $logDirectory + '" -jar "' + $coreJar + '"'
    $mediaArguments = '-Djdk.net.unixdomain.tmpdir="' + $logDirectory + '" -jar "' + $mediaJar + '"'
    $coreProcess = Start-Process -FilePath $java -ArgumentList $coreArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'core.out.log') -RedirectStandardError (Join-Path $logDirectory 'core.err.log')
    $mediaProcess = Start-Process -FilePath $java -ArgumentList $mediaArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'media.out.log') -RedirectStandardError (Join-Path $logDirectory 'media.err.log')
    if ($Scenario -eq 'M10') {
        & $Python infra/smoke/media_m10_http.py --core-url "http://127.0.0.1:$corePort" --media-url "http://127.0.0.1:$mediaPort" --proxy-port $proxyPort --project $project --compose-file $composeFile
    } else {
        & $Python infra/smoke/media_m09_http.py --core-url "http://127.0.0.1:$corePort" --media-url "http://127.0.0.1:$mediaPort" --project $project --compose-file $composeFile
    }
    if ($LASTEXITCODE -ne 0) { throw "$Scenario HTTP/database smoke failed." }
} catch {
    if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
    throw
} finally {
    if ($mediaProcess) { Stop-Process -Id $mediaProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($coreProcess) { Stop-Process -Id $coreProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($started) { & docker @compose down --volumes --remove-orphans --timeout 15 }
    $cleanupFailed = $started -and $LASTEXITCODE -ne 0
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
    Pop-Location
    if ($cleanupFailed) {
        if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence }
        throw "Smoke cleanup failed for project $project."
    }
}
