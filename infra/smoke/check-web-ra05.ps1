#requires -Version 7.0
# RA05 browser acceptance of the recommendation lists: the M29 runner with -Milestone RA05. It is the RA04 run (same
# disposable stack; e2e/ra03 then e2e/ra04, writing web-ra03.json and web-ra04.json), then e2e/ra05, which compares
# every list the page shows with Core's answer and writes web-ra05.json. Requires root clean verify (all three JARs),
# pnpm install in web/ and ffmpeg.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone 'RA05'
