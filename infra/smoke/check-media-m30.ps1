#requires -Version 7.0
# M30 protected-delivery abuse, cache and expiry checks: the M29 runner with -Milestone 30 (same disposable projects,
# generated keys, real compose.yaml and nginx.conf, packaged JARs and cleanup). Phase 1 adds the path matrix of
# ingress_m30_paths.py; phase 2 runs the whole M29 slice and then media_m30_http.py's checks, which wait out the
# five-minute ticket Core issued.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone '30'
