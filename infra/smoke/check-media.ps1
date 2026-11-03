#requires -Version 7.0
# Stable Media accepted-flow smoke entry point used by CI (.github/workflows/core-ci.yml) and the handoff.
# Reuses the M29 runner: disposable project, generated keys, empty env file, cleanup of its own project only.
# Requires root clean verify JARs and Docker.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not (Get-Command $Python -ErrorAction SilentlyContinue)) { throw 'The -Python interpreter must resolve to an executable.' }
& $Python --version
if ($LASTEXITCODE -ne 0) { throw 'The -Python interpreter --version failed.' }
foreach ($tool in 'ffmpeg', 'ffprobe') {
    $configured = [Environment]::GetEnvironmentVariable("LIBRA_$($tool.ToUpperInvariant())_PATH")
    if ($null -ne $configured) {
        if (-not $configured -or -not (Test-Path -LiteralPath $configured -PathType Leaf)) { throw "Configured $tool path must be an existing file." }
        $executable = $configured
    } else {
        $command = Get-Command $tool -ErrorAction SilentlyContinue
        if (-not $command) { throw "$tool must resolve on PATH." }
        $executable = $command.Source
    }
    & $executable -version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$tool -version failed." }
}
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone '29'
