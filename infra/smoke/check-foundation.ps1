$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to an installed JDK 21 before running this smoke check.'
}
$javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw 'JAVA_HOME does not contain bin/java.exe.'
}

Push-Location $repository
try {
    & docker compose -f infra/compose.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }

    & docker compose -f infra/compose.yaml exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:9092
    if ($LASTEXITCODE -ne 0) { throw 'Kafka internal listener failed.' }
    Write-Output 'PASS: Kafka internal listener'

    $ownership = & docker compose -f infra/compose.yaml exec -T postgres psql -U postgres -d postgres -Atc "SELECT datname || ':' || pg_get_userbyid(datdba) FROM pg_database WHERE datname IN ('libra_core', 'libra_media', 'libra_recommendation') ORDER BY datname"
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL ownership query failed.' }
    $expected = @('libra_core:libra_core', 'libra_media:libra_media', 'libra_recommendation:libra_recommendation')
    if (@(Compare-Object $expected @($ownership)).Count -ne 0) { throw 'Service database ownership differs from the expected isolation.' }
    Write-Output 'PASS: separate service database owners'

    & .\mvnw.cmd -q -pl services/core dependency:build-classpath '-Dmdep.outputFile=target/smoke-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve Kafka smoke classpath.' }
    $classpath = (Get-Content -LiteralPath services/core/target/smoke-classpath.txt -Raw).Trim()
    $socketDirectory = (Resolve-Path services/core/target).Path
    & $javaExecutable "-Djdk.net.unixdomain.tmpdir=$socketDirectory" --class-path $classpath infra/smoke/KafkaSmoke.java
    if ($LASTEXITCODE -ne 0) { throw 'Kafka host smoke failed.' }

    $subject = 'LIBRA foundation smoke ' + [guid]::NewGuid().ToString()
    $smtp = New-Object System.Net.Mail.SmtpClient('localhost', 1025)
    $smtp.Timeout = 5000
    $message = New-Object System.Net.Mail.MailMessage('libra@example.test', 'viewer@example.test', $subject, 'Synthetic local infrastructure check.')
    try { $smtp.Send($message) } finally { $message.Dispose(); $smtp.Dispose() }
    $captured = $false
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $messages = Invoke-RestMethod -Uri 'http://localhost:8025/api/v1/messages' -TimeoutSec 5
        if (@($messages.messages | Where-Object { $_.Subject -eq $subject }).Count -gt 0) {
            $captured = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $captured) { throw 'Mailpit did not capture the synthetic SMTP message.' }
    Write-Output 'PASS: SMTP captured in local Mailpit (not real inbox delivery)'
} finally {
    Pop-Location
}
