#requires -Version 7.0
# RA04 browser acceptance of the administrator statistics dashboard: the M29 runner with -Milestone RA04. It is the
# RA03 run (same disposable stack, seed, keys and cleanup; e2e/ra03 still runs first and writes web-ra03.json), then
# e2e/ra04, which reads the qualified view the RA03 player produced and writes web-ra04.json. Requires root clean
# verify (all three JARs), pnpm install in web/ and ffmpeg.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone 'RA04'
