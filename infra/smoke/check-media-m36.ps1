#requires -Version 7.0
# M36 retained-state restart: the M29 runner with -Milestone 36 (same disposable project, generated keys, real
# compose.yaml and nginx.conf, packaged JARs and cleanup). media_m36_restart.py takes the whole stack down without
# volumes, then up again, and checks retained state, playback bytes and database CONNECT isolation.
# Requires root clean verify and ffmpeg on PATH.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone '36'
