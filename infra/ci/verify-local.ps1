#requires -Version 7.0
# The -Python interpreter must already have infra/ci/requirements.txt installed.
param([string]$Python = 'python', [switch]$Offline)
$ErrorActionPreference = 'Stop'
# Native exits are checked explicitly, including Maven whose raw output stays local.
$PSNativeCommandUseErrorActionPreference = $false
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$previousRunId = [Environment]::GetEnvironmentVariable('LIBRA_VERIFY_RUN_ID', 'Process')
$env:LIBRA_VERIFY_RUN_ID = [guid]::NewGuid().ToString('N')
Push-Location $repository
try {
    & docker info | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is required; local verification cannot be skipped.' }
    & docker compose version
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is required; local verification cannot be skipped.' }

    & $Python -m unittest discover -s (Join-Path $repository 'infra/ci') -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'Verification-gate unit tests failed.' }

    # Fail fast on a missing tool; clean deletes this output, which is written again after Maven.
    & $Python (Join-Path $PSScriptRoot 'record_toolchain.py')
    if ($LASTEXITCODE -ne 0) { throw 'Required toolchain preflight failed.' }

    $maven = Join-Path $repository $(if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' })
    $mavenArguments = @('-B', '-ntp', 'clean', 'verify')
    if ($Offline) { $mavenArguments += '-o' }
    $logDirectory = Join-Path $repository 'tmp'
    New-Item -ItemType Directory -Force $logDirectory | Out-Null
    $mavenLog = Join-Path $logDirectory 'verify-local-maven.log'
    $mavenEnvironment = @{}
    $retainedNames = @('LIBRA_FFMPEG_PATH', 'LIBRA_FFPROBE_PATH', 'LIBRA_VERIFY_RUN_ID')
    $mavenExitCode = 1
    try {
        foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
            if ($entry.Key -match '^(CORE_|MEDIA_|SPRING_|LIBRA_|KAFKA_|RECOMMENDATION_)' -and $entry.Key -notin $retainedNames) {
                $mavenEnvironment[$entry.Key] = $entry.Value
                [Environment]::SetEnvironmentVariable($entry.Key, $null, 'Process')
            }
        }
        & $maven @mavenArguments *> $mavenLog
        $mavenExitCode = $LASTEXITCODE
    } catch {
        $mavenExitCode = 1
    } finally {
        foreach ($name in $mavenEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $mavenEnvironment[$name], 'Process')
        }
    }
    if ($mavenExitCode -ne 0) {
        try {
            & $Python (Join-Path $PSScriptRoot 'check_test_reports.py')
        } finally {
            $testsEvidence = Join-Path $repository 'target/verification/tests.json'
            if (Test-Path -LiteralPath $testsEvidence) { Remove-Item -LiteralPath $testsEvidence -Force }
        }
        throw "Maven verification failed; raw output is withheld. Inspect $mavenLog locally."
    }
    & $Python (Join-Path $PSScriptRoot 'record_toolchain.py')
    if ($LASTEXITCODE -ne 0) { throw 'Required toolchain recording failed.' }
    & $Python (Join-Path $PSScriptRoot 'check_test_reports.py')
    if ($LASTEXITCODE -ne 0) { throw 'Maven test report verification failed.' }
    & $Python (Join-Path $PSScriptRoot 'check_contracts.py')
    if ($LASTEXITCODE -ne 0) { throw 'Contract verification failed.' }

    $keyNames = @('SEAWEEDFS_ACCESS_KEY', 'SEAWEEDFS_SECRET_KEY')
    $previous = @{}
    foreach ($name in $keyNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
    try {
        foreach ($name in $keyNames) {
            $key = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
            [Environment]::SetEnvironmentVariable($name, $key)
        }
        & docker compose --env-file (Join-Path $repository '.env.example') -f (Join-Path $repository 'infra/compose.yaml') config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Workstation Compose template verification failed.' }
    } finally {
        foreach ($name in $keyNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
    }

    & (Join-Path $repository 'infra/smoke/check-core.ps1') -Python $Python
    if ($LASTEXITCODE -ne 0) { throw 'Packaged Core smoke failed.' }
    & (Join-Path $repository 'infra/smoke/check-media.ps1') -Python $Python
    if ($LASTEXITCODE -ne 0) { throw 'Packaged Media smoke failed.' }
    & $Python (Join-Path $PSScriptRoot 'check_smoke_evidence.py')
    if ($LASTEXITCODE -ne 0) { throw 'Sanitized smoke evidence verification failed.' }
} finally {
    [Environment]::SetEnvironmentVariable('LIBRA_VERIFY_RUN_ID', $previousRunId, 'Process')
    Pop-Location
}
Write-Output 'PASS: LIBRA local verification reproduced CI order'
