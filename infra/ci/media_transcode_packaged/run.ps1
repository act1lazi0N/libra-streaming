# M21/M22 packaged-runtime evidence: runs the shipped Media jar's transcode classes against the generated fixtures with
# the image's own ffmpeg and ffprobe, under container resource limits. Needs Docker, a JDK 21 javac on PATH (or
# JAVA_HOME), the fixtures from a Media test run (services/media/target/media-fixtures) and an image built from
# services/media/Dockerfile. Output goes to tmp/<OutputName>/*.txt (ignored by git).
param(
    [string]$Image = 'libra-media-m22:local',
    [string]$Fixtures = 'services/media/target/media-fixtures',
    [string]$OutputName = 'm22-packaged'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$work = Join-Path $root "tmp/$OutputName"
New-Item -ItemType Directory -Force $work | Out-Null
Remove-Item -Recurse -Force (Join-Path $work 'x'), (Join-Path $work 'h'), (Join-Path $work 'app.jar') -ErrorAction SilentlyContinue

$container = docker create $Image
docker cp "${container}:/app/app.jar" (Join-Path $work 'app.jar')
docker rm $container | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $work 'app.jar'), (Join-Path $work 'x'))
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
& $javac -cp "$work/x/BOOT-INF/classes;$work/x/BOOT-INF/lib/*" -d (Join-Path $work 'h') `
    (Join-Path $root 'infra/ci/media_transcode_packaged/PackagedTranscode.java')

$fx = (Resolve-Path (Join-Path $root $Fixtures)).Path
$mounts = @('-v', "$work/x:/x:ro", '-v', "$work/h:/h:ro", '-v', "${fx}:/fx:ro")
# The same posture as the Compose service: no network, read-only root, no capabilities, scratch on a bounded tmpfs.
$sandbox = @('--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,nosuid,noexec,size=805306368,mode=1777',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pids-limit', '128')
$classpath = '/h:/x/BOOT-INF/classes:/x/BOOT-INF/lib/*'
$harness = 'com.libra.streaming.media.processing.infrastructure.PackagedTranscode'
$limits = @('--memory', '768m', '--memory-swap', '768m', '--cpus', '2')

function Run-Harness([string[]]$Arguments, [string]$Name, [string[]]$Extra = @()) {
    docker run @sandbox @limits @Extra @mounts --entrypoint java $Image -cp $classpath $harness @Arguments |
        Tee-Object -FilePath (Join-Path $work $Name)
}

$clip = '/fx/valid-720p-20s-aac.mp4'

Write-Host '== matrix: every accepted fixture, 768 MiB / 2 CPUs'
Run-Harness @('matrix', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', '/fx', '/tmp/out') 'matrix.txt'

Write-Host '== deadline: a 20 s clip against a 300 ms deadline'
Run-Harness @('limits', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', $clip, '/tmp/out', '300') 'deadline.txt'

Write-Host '== cancellation: the worker is cancelled 400 ms into a 20 s clip'
Run-Harness @('cancel', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', $clip, '/tmp/out', '400') 'cancel.txt'

Write-Host '== damaged tails: probe verdict and encode outcome'
Run-Harness @('rejects', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', '/fx', '/tmp/out') 'rejects.txt'

Write-Host '== signals: SIGTERM and SIGKILL to the encoder after two segments'
Run-Harness @('signal', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', $clip, '/tmp/out', 'TERM') 'signal-term.txt'
Run-Harness @('signal', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', $clip, '/tmp/out', 'KILL') 'signal-kill.txt'

Write-Host '== full disk: the workspace on a 2 MiB file system'
Run-Harness @('full', '/usr/bin/ffmpeg', '/usr/bin/ffprobe', $clip, '/w') 'full.txt' `
    @('--tmpfs', '/w:rw,nosuid,noexec,size=2097152,mode=1777')
