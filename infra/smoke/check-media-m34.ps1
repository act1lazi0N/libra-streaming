#requires -Version 7.0
# M34 fault matrix: the M29 runner with -Milestone 34 (same disposable project, generated keys, real compose.yaml and
# nginx.conf, packaged JARs and cleanup). media_m34_faults.py stops, kills, pauses and restarts Core, Media, Kafka,
# storage and PostgreSQL of that project only, and checks what each interrupted operation converges to.
# Requires root clean verify and ffmpeg on PATH.
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-media-m29.ps1') -Python $Python -Milestone '34'
