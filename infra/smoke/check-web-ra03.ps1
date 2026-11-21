#requires -Version 7.0
# RA03 browser acceptance of the real web product: the M29 runner with -Milestone RA03 (same disposable project,
# generated keys, real compose.yaml and nginx.conf, packaged JARs and cleanup). The real `web` image, Core, Media and
# Analytics run behind the repository ingress; Playwright drives the product UI in the installed Google Chrome against
# real HLS. Requires root clean verify (all three JARs), pnpm install in web/ and ffmpeg.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone 'RA03'
