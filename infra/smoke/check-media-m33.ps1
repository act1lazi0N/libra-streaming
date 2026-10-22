#requires -Version 7.0
# M33 browser vertical slice: the M29 runner with -Milestone 33 (same disposable project, generated keys, real
# compose.yaml and nginx.conf, packaged JARs and cleanup). A headless Google Chrome driven by Playwright uploads the
# clip from a file input directly to storage, publishes it through Core, and plays it with the pinned hls.js through
# the ingress with the browser's own ticket cookie. Requires root clean verify and pnpm install in web/.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone '33'
