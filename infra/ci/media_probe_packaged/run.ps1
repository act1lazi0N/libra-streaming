# M20 packaged-runtime evidence: runs the shipped Media jar's probe classes against the generated fixtures with the
# image's own ffprobe, under container resource limits. Needs Docker, a JDK 21 javac on PATH (or JAVA_HOME), the
# fixtures from a Media test run (services/media/target/media-fixtures) and an image built from services/media/Dockerfile.
# Output goes to tmp/m20-packaged/*.txt (ignored by git).
param(
    [string]$Image = 'libra-media-m20:local',
    [string]$Fixtures = 'services/media/target/media-fixtures'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$work = Join-Path $root 'tmp/m20-packaged'
New-Item -ItemType Directory -Force $work | Out-Null
Remove-Item -Recurse -Force (Join-Path $work 'x'), (Join-Path $work 'h'), (Join-Path $work 'app.jar') -ErrorAction SilentlyContinue

$container = docker create $Image
docker cp "${container}:/app/app.jar" (Join-Path $work 'app.jar')
docker rm $container | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $work 'app.jar'), (Join-Path $work 'x'))
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
& $javac -cp "$work/x/BOOT-INF/classes;$work/x/BOOT-INF/lib/*" -d (Join-Path $work 'h') `
    (Join-Path $root 'infra/ci/media_probe_packaged/PackagedProbe.java')

$fx = (Resolve-Path (Join-Path $root $Fixtures)).Path
$mounts = @('-v', "$work/x:/x:ro", '-v', "$work/h:/h:ro", '-v', "${fx}:/fx:ro")
$sandbox = @('--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL',
    '--security-opt', 'no-new-privileges', '--pids-limit', '64')
$classpath = '/h:/x/BOOT-INF/classes:/x/BOOT-INF/lib/*'
$probe = 'com.libra.streaming.media.processing.infrastructure.PackagedProbe'

function Run-Probe([string[]]$Limits, [string[]]$Arguments, [string]$Name) {
    docker run @sandbox @Limits @mounts --entrypoint java $Image -cp $classpath $probe @Arguments |
        Tee-Object -FilePath (Join-Path $work $Name)
}

Write-Host '== failure matrix, 256 MiB / 1 CPU'
Run-Probe @('--memory', '256m', '--memory-swap', '256m', '--cpus', '1') `
    @('matrix', '/usr/bin/ffprobe', '/fx', '20', '262144') 'matrix.txt'

# A real hang: a background loop in the container freezes (SIGSTOP) every process named ffprobe as soon as it
# appears (once the harness has warmed up and created /tmp/freeze), so the tool neither exits nor uses CPU, the way a process blocked on I/O would. Afterwards no process
# named ffprobe may exist, which is read from /proc, and the JVM must have no descendants left.
function Run-Stalled([string]$Mode, [string[]]$Arguments, [string]$Name) {
    $java = "java -cp '$classpath' $probe $Mode " + ($Arguments -join ' ')
    $script = @'
(while :; do [ -e /tmp/freeze ] && for p in $(grep -l ffprobe /proc/[0-9]*/comm 2>/dev/null | cut -d/ -f3); do kill -STOP $p 2>/dev/null; done; sleep 0.05; done) &
stopper=$!
JAVA_COMMAND
echo FFPROBE_PROCESSES_AFTER=$(grep -l ffprobe /proc/[0-9]*/comm 2>/dev/null | wc -l)
kill $stopper
'@.Replace('JAVA_COMMAND', $java).Replace("`r", '')
    docker run @sandbox --memory 256m --memory-swap 256m --cpus 1 @mounts --entrypoint sh $Image -c $script |
        Tee-Object -FilePath (Join-Path $work $Name)
}

Write-Host '== deadline: a frozen real ffprobe against a 300 ms deadline'
Run-Stalled 'limits' @('/usr/bin/ffprobe', '/fx/valid-dense-600s.mp4', '300', '262144') 'deadline.txt'

Write-Host '== cancellation: the worker is cancelled 120 ms into a frozen probe'
Run-Stalled 'cancel' @('/usr/bin/ffprobe', '/fx/valid-dense-600s.mp4', '120') 'cancel.txt'

Write-Host '== output budget: a good file whose description exceeds 1 KiB'
Run-Probe @('--memory', '256m', '--memory-swap', '256m', '--cpus', '1') `
    @('limits', '/usr/bin/ffprobe', '/fx/valid-1080p-aac.mp4', '20000', '1024') 'output-budget.txt'

# ffprobe alone (no JVM), exactly the prober's argument list, to see how little memory it needs and what an
# exhausted budget looks like. $tool is the list built by FfprobeMediaProber.command; $uncapped is the same list
# without -max_alloc, kept only to show what the cap prevents.
$tool = '/usr/bin/ffprobe -v error -hide_banner -f mov -protocol_whitelist file -probesize 8388608 -analyzeduration 10000000 -max_alloc 33554432 -print_format json -show_format -show_streams -i file:'
$uncapped = $tool.Replace(' -max_alloc 33554432', '')
$cases = @(
    @('valid-dense-600s.mp4', '64m', $tool), @('invalid-huge-metadata.mp4', '64m', $tool),
    @('invalid-8k.mp4', '128m', $tool), @('invalid-15k.mp4', '64m', $tool),
    @('invalid-15k.mp4', '64m', $uncapped), @('invalid-15k.mp4', '1g', $uncapped),
    @('valid-dense-600s.mp4', '6m', $tool))
Remove-Item (Join-Path $work 'tool-alone.txt') -ErrorAction SilentlyContinue
foreach ($case in $cases) {
    $name = $case[0]; $memory = $case[1]; $label = if ($case[2] -eq $tool) { 'capped' } else { 'UNCAPPED' }
    Write-Host "== ffprobe alone ($label): $name at $memory"
    $script = "$($case[2])/fx/$name > /dev/null; echo exit=`$?; echo peak_bytes=`$(cat /sys/fs/cgroup/memory.peak)"
    "$label $name limit=$memory" | Tee-Object -FilePath (Join-Path $work 'tool-alone.txt') -Append
    docker run @sandbox --memory $memory --memory-swap $memory --cpus 2 -v "${fx}:/fx:ro" --entrypoint sh $Image -c $script |
        Tee-Object -FilePath (Join-Path $work 'tool-alone.txt') -Append
}
