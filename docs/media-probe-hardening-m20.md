# Media: Untrusted parsing and process-boundary hardening (Milestone 20)

Verification date: 2026-10-06.
Status: VERIFIED_LOCAL (stage only; see Scope).

## Scope

M20 hardens the M19 probe stage against hostile and malformed input. It adds no schema, setting, API, event, failure
code or job stage, and still installs no production job handler. A probed source is never READY or playable.

Every fixture is generated locally by ffmpeg at test time (test patterns, tones and flat colour, plus byte edits of
those files). No third-party or phone media was used and nothing was fetched from a network.

## Gate

Hostile or malformed input ends in a controlled failure: one permanent verdict, no retry loop, no job or event
behind it, no file or network access outside the frozen copy, no process left running and no READY.

## Findings and repairs

Each was found by running the real tool, and the brand spoofing only in the packaged image. Each came with a fixture
or probe that showed the problem before the repair.

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| **Brand spoofing.** The policy read `major_brand` from ffprobe's tag list. The packaged ffprobe 8.0.1 lets a file's own metadata tag replace the real brand, so a QuickTime (`qt  `) file claiming `isom` was `ACCEPTED`. ffprobe 9.0.2 appends the tag instead (`qt  ;isom`), so the host suite did not show it. | `Ftyp` reads the brand from the file's leading `ftyp` box (16 bytes) and the tag is never used. A file that does not start with a well-formed `ftyp` has no brand and is rejected. | Packaged matrix before: `invalid-brand-spoofed-tag.mp4  ACCEPTED`; after: `REJECTED UNSUPPORTED_MEDIA BRAND` (`tmp/m20-packaged-matrix-before-ftyp.txt`). `FtypTest` (2), fixture test asserts the brand is `qt  ` on both builds. |
| **Headers that hide a long clip.** Every duration header (`mvhd`, `tkhd`, `mdhd`) rewritten to one second over a sample table of 601 frames. ffprobe reports `duration=1.0`, `nb_frames=601`, so the 600 s limit and the frame-rate rule both saw a 1 s clip. (Patching `mvhd` alone changes nothing: ffprobe takes the longest stream.) | `SourcePolicy` compares the video `nb_frames` with the container duration at the declared rate (10% plus 3 frames of slack) and rejects a clip whose sample table does not fit as `CORRUPT_INPUT` / `FRAME_COUNT`. | The M19 policy, fed by the same real report, returned `Accepted` for `invalid-lying-headers.mp4`. Now rejected on the host, in the DB-backed stage and in the packaged image. |
| **Tracks of different lengths.** A 1 s picture over 30 s of tone: the container is as long as the longest track and the policy accepted it. | Each track's own duration must be within max(2 s, 10%) of the container's, else `UNSUPPORTED_MEDIA` / `TRACK_DURATION`. | M19 policy: `Accepted`. Now rejected, real file. |
| **A local playlist led the tool to another file.** With only `-protocol_whitelist file`, a `.m3u8` naming a local MP4 was followed and probed (`format_name=hls`, exit 0). The policy rejected the result, but the tool had opened a file the upload never contained, and the verdict was an existence oracle. | The command forces `-f mov`: only the MP4/MOV demuxer reads input, so playlists, concat scripts and descriptors are never interpreted. A Matroska file therefore ends as unreadable (`CORRUPT_INPUT`) instead of `UNSUPPORTED_MEDIA`. | `theForcedDemuxerClosesLocalFileIndirectionThatTheWhitelistCannot`: the M19 argument list exits 0 on the playlist, the M20 command is unreadable whether or not the target exists. |
| **A 650 KB file made the tool allocate 544 MB.** A header declaring 15000x15000 pixels makes ffprobe decode its first frame before any policy sees the dimensions. A 64 MiB container killed it (exit 137). | `-max_alloc 33554432` (32 MiB per allocation, 4x the probe size and about 10x the largest accepted frame). The header facts are still printed, so the policy rejects the size normally. Output of all 33 earlier fixtures is byte-identical with and without the cap. | `tool-alone.txt`: uncapped 569,753,600 B peak (killed at 64 MiB); capped 16,343,040 B and exit 0, verdict `UNSUPPORTED_MEDIA` / `RESOLUTION`. |
| **Contradictory rotations.** The first `rotation` entry won, and a legacy `rotate` tag could silently replace or disagree with the display matrix. | Rotations that disagree (modulo a full turn) give no trustworthy orientation: the report carries NaN and the policy rejects it as `ROTATION`. Agreeing entries keep the first. | `FfprobeJsonTest` (unit). No real file reproduced this: ffmpeg 9 writes the matrix only, so the case is covered by synthetic JSON, not a real muxer. |
| **A scratch file survived a probe cut off for output.** The Java process deleted the frozen copy while Windows still held it for a moment after the tool was killed, and the failed delete was swallowed (reproduced on two runs). | `StagedCopy.close` retries the delete four times (25, 50, 100, 200 ms) before leaving the file for the M18 startup reclaim. | `hostileInputsEndInOneControlledFailureWithoutFloodOrReadiness` failed on `invalid-huge-metadata.mp4` with a leftover `.part`; passes after. Observed on the Windows host only. |

Unchanged on purpose: the explicit `-protocol_whitelist file`, the empty environment, bounded stdout, the deadline and
cancellation kill, and the 64-track parser cap (a 70-stream file keeps 64 tracks and is still rejected for its extra
audio). The probe budgets are now explicit (`-probesize 8 MiB`, `-analyzeduration 10 s`) instead of inherited.

Observations that were not defects: ffprobe 9.0.2 already restricts a local input to `file,crypto,data`, so the explicit
whitelist no longer depends on that default; a spoofed brand tag does not defeat the 9.0.2 check; JSON nested 5000
deep or carrying 200 KB strings is unreadable or null, not a crash.

## Failure matrix

Real ffprobe inside the packaged image (`libra-media-m20:local`: `ffprobe 8.0.1-3ubuntu2`, JRE 21.0.12, user `libra`),
running the shipped classes unpacked from the image's jar against the 34 generated fixtures
(`infra/ci/media_probe_packaged`, output `tmp/m20-packaged/matrix.txt`). The container ran with `--network none
--read-only --cap-drop ALL --pids-limit 64 --memory 256m --cpus 1`. The host suite (ffprobe 9.0.2) gives the same
verdicts.

| Input | Verdict | Wall time |
| --- | --- | --- |
| Empty file, `ftyp` only, cut off at 1000 bytes, cut off mid-file, random bytes | `UNREADABLE` (`CORRUPT_INPUT`) | 65-105 ms |
| Well-formed Matroska | `UNREADABLE` | 89 ms |
| 300 KB comment tag (description above the 256 KiB output budget) | `UNREADABLE` | 120 ms |
| 601 s clip | `UNSUPPORTED_MEDIA` / `DURATION_RANGE` | 86 ms |
| Headers claiming 1 s over 601 frames | `CORRUPT_INPUT` / `FRAME_COUNT` | 106 ms |
| 1 s picture with 30 s of audio | `UNSUPPORTED_MEDIA` / `TRACK_DURATION` | 129 ms |
| HEVC, 10-bit, HDR, MP3 audio | `UNSUPPORTED_MEDIA` / `VIDEO_CODEC`, `VIDEO_PROFILE`, `HDR`, `AUDIO_CODEC` | 104-3264 ms |
| 4K, 8K, 15000x15000 | `UNSUPPORTED_MEDIA` / `RESOLUTION` | 105-217 ms |
| 60 fps | `UNSUPPORTED_MEDIA` / `FRAME_RATE_RANGE` | 119 ms |
| Two audio tracks, cover art, subtitles, 70 audio tracks | `UNSUPPORTED_MEDIA` / `EXTRA_AUDIO`, `STREAM_KIND` | 84-232 ms |
| QuickTime brand; QuickTime brand with `major_brand=isom` tag | `UNSUPPORTED_MEDIA` / `BRAND` | 87-91 ms |
| 45 degree rotation | `UNSUPPORTED_MEDIA` / `ROTATION` | 107 ms |
| 180 and 270 degree rotation, 90 degree phone clip, anamorphic, NTSC rate, 600 s clip, 18000-frame clip, 1080p AAC, silent 160x96 | `ACCEPTED` | 73-136 ms |

The database-backed stage (`SourceProbeIntegrationTest`, real PostgreSQL 18.6, SeaweedFS 4.46 and ffprobe 9.0.2) runs ten
of the hostile files end to end. For each: one permanent rejection with the expected code, the number of jobs unchanged,
the asset still `PROCESSING` until the worker records the failure, no metadata row, scratch unchanged; after the loop,
zero outbox events and zero `READY` assets.

## Process boundary

| Case | Evidence | Result |
| --- | --- | --- |
| Hung tool (deadline) | Packaged image, real ffprobe frozen with SIGSTOP the moment it starts, 300 ms deadline (`deadline.txt`) | `UNAVAILABLE` (retryable) after 315 ms; no JVM descendants, no process named ffprobe left |
| Cancelled worker | Same, cancelled 120 ms in (`cancel.txt`) | `InterruptedException` after 171 ms; nothing left |
| Output bomb | Real file whose description is above the budget; a good file against a 1 KiB budget; the 300 KB-tag file against 256 KiB | `UNREADABLE`, never truncated |
| Abnormal exit, malformed JSON, absent executable, startup | M19 scripted stand-ins (`FfprobeMediaProberProcessTest`) | Unchanged; exceptions carry no output, path or cause |
| Killed by the kernel (budget exhausted) | Packaged ffprobe alone at a 6 MiB limit exits 137 | Seen by the prober as a non-zero exit: permanent `UNREADABLE`. Not run through the Java stage. |
| Hostile filename or option text | `nothingButTheFinalOperandDependsOnTheFile`; M19 file named `-i http;&echo x $(y) 'z'.mp4` | Only the last operand varies, and it is prefixed `file:` after `-i` |
| Network | Loopback listener counting connections. Control: concat with `-safe 0` and http whitelisted on purpose connects. Concat with only `-protocol_whitelist file`: refused, 0 connections. Production command on a concat file and on an http playlist: unreadable, 0 connections. | The whitelist is proven on its own, not through the demuxer's safe mode |
| Leftover processes | Real ffprobe over seven hostile files, process list compared before and after; control proves the detector sees a live ffmpeg | None left |

## Resource observations

Packaged image, cgroup v2 counters (`tmp/m20-packaged/*.txt`).

| Run | Observation |
| --- | --- |
| Whole matrix, JVM and tool, 256 MiB, 1 CPU | peak memory 148,942,848 B, 17 processes, 13 throttle events; every verdict above |
| ffprobe alone on the 18000-frame clip, capped | 16,445,440 B at a 64 MiB limit |
| ffprobe alone on the 300 KB-tag file | 16,449,536 B |
| ffprobe alone on the 8K file | 96,821,248 B at a 128 MiB limit (it decodes a 50 MB frame; an uncapped run at 64 MiB was killed) |
| ffprobe alone on the 15000x15000 file | capped 16,343,040 B; uncapped 569,753,600 B, and killed at 64 MiB |
| ffprobe alone on the 18000-frame clip at 6 MiB | killed (exit 137) |

Time is bounded by the deadline plus a 5 s termination grace; output by the budget (default 256 KiB, at most 4 MiB);
a single allocation by 32 MiB; tool memory in aggregate only by the container.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `FfprobeFixtureIntegrationTest` | Real ffprobe 9.0.2 on generated fixtures | 18 (8 new) |
| `FfprobeBoundaryIntegrationTest` | Real ffprobe, loopback listener, process list | 5 |
| `SourceProbeIntegrationTest` | Real PostgreSQL, SeaweedFS, ffprobe | 7 (1 new) |
| `FfprobeMediaProberProcessTest` | Scripted stand-ins | 13 (1 new, 1 extended) |
| `SourcePolicyTest`, `FfprobeJsonTest`, `FtypTest` | Synthetic reports and JSON, temp files | 15 (3 new), 10 (3 new), 2 |
| Packaged runtime | Image built from `services/media/Dockerfile`, `PackagedProbe` against the unpacked jar | script `infra/ci/media_probe_packaged/run.ps1` |

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 8m28s). The report gate confirmed 59
suites and 408 tests with zero failures, errors or skips: Core 186, Media 220 (92 Surefire, 128 Failsafe),
recommendation and analytics 2. All 18 contracts passed and `git diff --check` was clean. M20 added 23 tests. The
host suite ran against ffprobe 9.0.2; the packaged runs used the image's ffprobe 8.0.1. Raw log:
`tmp/m20-reactor-verify.log` (ignored); packaged outputs under `tmp/m20-packaged/` (ignored).

Two focused runs failed before the final one and are not counted: the first `FfprobeJsonTest` run caught a defect in my
own rotation merge (the later of two agreeing entries replaced the first), fixed before the reactor run; the first
boundary run failed on two of its own controls (ffprobe 9 blocks http for a local input by default, and Windows exposes
no command line to match processes by), which is why the controls now whitelist http on purpose and match by image
name and process id.

## Limits

- The hang is a real ffprobe frozen from outside the process; no ordinary file makes ffprobe hang, and a
  FIFO or device never reaches it (only regular files are opened). A truly blocked read was not observed.
- The allocation cap is per block. Aggregate tool memory is bounded only by the container, and a tool killed by the
  kernel is classified as a permanent input failure (finite retry budget, and a parser crash or exhaustion is almost
  always caused by the input). A host under memory pressure could therefore fail a good file permanently.
- No CPU limit exists inside the Java process; the deadline and the container carry it. A CPU quota starves the
  supervising JVM as much as the tool: with 5% of a CPU, a 1 ms deadline did not fire because the supervisor was not
  scheduled before the tool finished (a 33 s probe). The deadline is enforced when the supervisor runs.
- The Java stage ran inside the packaged image from classes unpacked from its jar, not as the Spring application, and
  the full Compose stack with the probe enabled was not started.
- The new thresholds (2 s / 10% track slack, 10% + 3 frames) are judged against synthetic files only. Real phone or
  camera files with long edit lists or priming could differ; none was tested.
- A file whose first box is not `ftyp` has no brand and is rejected, which some QuickTime-era writers would trigger.
- Contradictory rotation is covered by synthetic JSON only, and the Windows delete race by one host; Linux was not
  affected in any run.
- ffprobe 8.0.1 (packaged) and 9.0.2 (host) are the only builds seen. Behaviour that differs between builds (brand tag,
  default whitelist) is why the repairs do not rely on either.
- Local containers only: not multi-host, remote CI or deployment. The GitNexus index is behind the working tree, so
  graph results were not used; source review and the tests are the evidence.

Run with Java 21, Docker Desktop and ffmpeg/ffprobe on PATH (or `LIBRA_FFPROBE_PATH`, `LIBRA_FFMPEG_PATH`):

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
docker build -f services/media/Dockerfile -t libra-media-m20:local .
.\infra\ci\media_probe_packaged\run.ps1
```
