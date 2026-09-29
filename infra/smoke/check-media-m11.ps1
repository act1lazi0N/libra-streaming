#requires -Version 7.0
param([string]$Python = 'tmp/milestone8-tools/Scripts/python.exe',
      [ValidateSet('M11', 'M12', 'M13')][string]$Scenario = 'M11')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'libra-m11-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$composeFile = Join-Path $PSScriptRoot 'compose.media-m11.yaml'
$compose = @('compose', '--project-name', $project, '--file', $composeFile)
$evidence = Join-Path $repository ('target/verification/media-' + $Scenario.ToLowerInvariant() + '.json')
$logDirectory = Join-Path $repository 'target/verification/m11-processes'
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
    'LIBRA_M11_ADMIN_PASSWORD','LIBRA_M11_POSTGRES_PASSWORD',
    'LIBRA_M11_S3_ACCESS_KEY','LIBRA_M11_S3_SECRET_KEY',
    'MEDIA_S3_INTERNAL_ENDPOINT','MEDIA_S3_BROWSER_ENDPOINT','MEDIA_BROWSER_ORIGIN',
    'MEDIA_S3_REGION','MEDIA_S3_ACCESS_KEY','MEDIA_S3_SECRET_KEY','MEDIA_SOURCE_BUCKET',
    'MEDIA_HLS_BUCKET','MEDIA_STAGING_PREFIX','MEDIA_FROZEN_PREFIX','MEDIA_HLS_PREFIX',
    'MEDIA_S3_CONNECT_TIMEOUT','MEDIA_S3_REQUEST_TIMEOUT','MEDIA_MAX_OBJECT_BYTES',
    'MEDIA_SCRATCH_DIRECTORY','MEDIA_S3_ALLOW_HTTP','MEDIA_STORAGE_INITIALIZE_BUCKETS'
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
        throw 'Build both service jars before the M11 smoke check.'
    }
    $java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'JDK 21 runtime is required.' }
    Set-Smoke 'LIBRA_M11_ADMIN_PASSWORD' (Random-Key)
    Set-Smoke 'LIBRA_M11_POSTGRES_PASSWORD' (Random-Key)
    Set-Smoke 'LIBRA_M11_S3_ACCESS_KEY' ('m11-' + [guid]::NewGuid().ToString('N'))
    Set-Smoke 'LIBRA_M11_S3_SECRET_KEY' (Random-Key)
    $started = $true
    & docker @compose up -d --wait --wait-timeout 90
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL did not start.' }
    $databasePort = ((& docker @compose port postgres 5432 | Select-Object -Last 1).Trim() -split ':')[-1]
    if ($LASTEXITCODE -ne 0 -or $databasePort -notmatch '^[0-9]+$') { throw 'Could not resolve disposable PostgreSQL port.' }
    $corePort = Free-Port
    $mediaPort = Free-Port
    $browserPort = Free-Port
    $internalGatewayPort = ((& docker @compose port s3-gateway-internal 8333 | Select-Object -Last 1).Trim() -split ':')[-1]
    $browserGatewayPort = ((& docker @compose port s3-gateway-browser 8333 | Select-Object -Last 1).Trim() -split ':')[-1]
    if ($internalGatewayPort -eq $browserGatewayPort) { throw 'Expected two distinct S3 gateway ports.' }
    if ((@($corePort, $mediaPort, $browserPort) | Select-Object -Unique).Count -ne 3) { throw 'Smoke service ports collided.' }
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
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_EMAIL' 'admin@m11.example.test'
    Set-Smoke 'CORE_BOOTSTRAP_ADMIN_PASSWORD' $env:LIBRA_M11_ADMIN_PASSWORD
    Set-Smoke 'CORE_MAIL_WORKER_ENABLED' 'false'
    Set-Smoke 'CORE_MEDIA_LISTENER_ENABLED' 'false'
    Set-Smoke 'CORE_OUTBOX_PUBLISHER_ENABLED' 'false'
    Set-Smoke 'CORE_DLT_LISTENER_ENABLED' 'false'
    Set-Smoke 'CORE_PLAYBACK_PRIVATE_KEY' $playback[0]
    Set-Smoke 'CORE_PLAYBACK_PUBLIC_KEY' $playback[1]
    Set-Smoke 'CORE_PLAYBACK_KEY_ID' 'm11-playback'
    Set-Smoke 'CORE_MEDIA_CONTROL_ENABLED' 'true'
    Set-Smoke 'CORE_MEDIA_CONTROL_BASE_URL' "http://127.0.0.1:$mediaPort"
    Set-Smoke 'CORE_MEDIA_CONTROL_PRIVATE_KEY' $control[0]
    Set-Smoke 'CORE_MEDIA_CONTROL_PUBLIC_KEY' $control[1]
    Set-Smoke 'CORE_MEDIA_CONTROL_KEY_ID' 'm11-core-control'
    Set-Smoke 'CORE_MEDIA_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'CORE_MEDIA_SERVICE_PUBLIC_KEY' $binding[1]
    Set-Smoke 'CORE_MEDIA_SERVICE_KEY_ID' 'm11-media-binding'
    Set-Smoke 'MEDIA_DB_URL' "jdbc:postgresql://127.0.0.1:$databasePort/libra_media"
    Set-Smoke 'MEDIA_DB_USERNAME' 'libra_media'
    Set-Smoke 'MEDIA_DB_PASSWORD' 'media_local'
    Set-Smoke 'MEDIA_PORT' "$mediaPort"
    Set-Smoke 'MEDIA_STORAGE_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_SERVICE_AUTH_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_SERVICE_PUBLIC_KEY' $control[1]
    Set-Smoke 'MEDIA_CORE_SERVICE_KEY_ID' 'm11-core-control'
    Set-Smoke 'MEDIA_CORE_BINDING_ENABLED' 'true'
    Set-Smoke 'MEDIA_CORE_BINDING_BASE_URL' "http://127.0.0.1:$corePort"
    Set-Smoke 'MEDIA_CORE_BINDING_PRIVATE_KEY' $binding[0]
    Set-Smoke 'MEDIA_CORE_BINDING_PUBLIC_KEY' $binding[1]
    Set-Smoke 'MEDIA_CORE_BINDING_KEY_ID' 'm11-media-binding'
    Set-Smoke 'MEDIA_CORE_BINDING_ALLOW_HTTP' 'true'
    Set-Smoke 'MEDIA_S3_INTERNAL_ENDPOINT' "http://127.0.0.1:$internalGatewayPort"
    Set-Smoke 'MEDIA_S3_BROWSER_ENDPOINT' "http://127.0.0.1:$browserGatewayPort"
    Set-Smoke 'MEDIA_BROWSER_ORIGIN' "http://127.0.0.1:$browserPort"
    Set-Smoke 'MEDIA_S3_REGION' 'us-east-1'
    Set-Smoke 'MEDIA_S3_ACCESS_KEY' $env:LIBRA_M11_S3_ACCESS_KEY
    Set-Smoke 'MEDIA_S3_SECRET_KEY' $env:LIBRA_M11_S3_SECRET_KEY
    Set-Smoke 'MEDIA_SOURCE_BUCKET' 'libra-source'
    Set-Smoke 'MEDIA_HLS_BUCKET' 'libra-hls'
    Set-Smoke 'MEDIA_STAGING_PREFIX' 'staging/'
    Set-Smoke 'MEDIA_FROZEN_PREFIX' 'sources/'
    Set-Smoke 'MEDIA_HLS_PREFIX' 'hls/'
    Set-Smoke 'MEDIA_S3_CONNECT_TIMEOUT' '3s'
    Set-Smoke 'MEDIA_S3_REQUEST_TIMEOUT' '30s'
    Set-Smoke 'MEDIA_MAX_OBJECT_BYTES' '268435456'
    Set-Smoke 'MEDIA_SCRATCH_DIRECTORY' (Join-Path $logDirectory 'scratch')
    Set-Smoke 'MEDIA_S3_ALLOW_HTTP' 'true'
    Set-Smoke 'MEDIA_STORAGE_INITIALIZE_BUCKETS' 'true'
    $coreArguments = '-Djdk.net.unixdomain.tmpdir="' + $logDirectory + '" -jar "' + $coreJar + '"'
    $mediaArguments = '-Djdk.net.unixdomain.tmpdir="' + $logDirectory + '" -jar "' + $mediaJar + '"'
    $coreProcess = Start-Process -FilePath $java -ArgumentList $coreArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'core.out.log') -RedirectStandardError (Join-Path $logDirectory 'core.err.log')
    $mediaProcess = Start-Process -FilePath $java -ArgumentList $mediaArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'media.out.log') -RedirectStandardError (Join-Path $logDirectory 'media.err.log')
    $smokeOutput = & $Python infra/smoke/media_m11_http.py --scenario $Scenario --core-url "http://127.0.0.1:$corePort" --media-url "http://127.0.0.1:$mediaPort" --browser-port $browserPort --project $project --compose-file $composeFile
    if ($LASTEXITCODE -ne 0) { throw "$Scenario HTTP/browser/database smoke failed." }
    if ($Scenario -eq 'M13') {
        $identity = @($smokeOutput | Where-Object { $_ -match '^M13_UPLOAD_ID=[0-9a-f-]{36}$' })
        if ($identity.Count -ne 1) { throw 'M13 did not report one upload identity.' }
        Stop-Process -Id $mediaProcess.Id -Force
        Wait-Process -Id $mediaProcess.Id -ErrorAction SilentlyContinue
        $mediaProcess = Start-Process -FilePath $java -ArgumentList $mediaArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'media-restarted.out.log') -RedirectStandardError (Join-Path $logDirectory 'media-restarted.err.log')
        & $Python infra/smoke/media_m13_restart.py --core-url "http://127.0.0.1:$corePort" --media-url "http://127.0.0.1:$mediaPort" --upload-id $identity[0].Substring(14) --project $project --compose-file $composeFile
        if ($LASTEXITCODE -ne 0) { throw 'M13 restart persistence check failed.' }
    }
    $smokeOutput | Where-Object { $_ -notmatch '^M13_UPLOAD_ID=' } | Write-Output
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
