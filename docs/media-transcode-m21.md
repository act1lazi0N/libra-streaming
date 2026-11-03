# Media: single-rendition FFmpeg pipeline (Milestone 21)

Verification date: 2026-10-06.
Status: VERIFIED_LOCAL (stage only; see Scope).

## Scope

M21 adds the transcode stage that runs after probing. It takes the frozen, probed source, encodes it with one bounded
ffmpeg process into one H.264 (+ optional AAC) HLS rendition, and leaves the result in an attempt-specific local
workspace together with a checked inventory. It adds no table, no migration, no API, no event, no failure code and no
job stage (`TRANSCODING` already existed in the schema), and it uploads nothing.

The stage is a Spring bean (`SourceTranscoder`), present only when `libra.media.transcode.executable` is set; it also
needs `libra.media.probe.executable`, and startup fails with that name if it is missing. **No production job handler is
installed.** The shipped runtime still has no `MediaJobHandler`, so enabling the worker still fails startup and
admitted jobs stay `QUEUED`. Output verification, storage under an immutable prefix and the READY transition are M23;
until then an encoded workspace is "transcoded", never ready or playable: the asset stays `PROCESSING`, the aggregate
version does not move, no outbox event is written and the HLS bucket is untouched.

## Media policy

| Aspect | Rule |
| --- | --- |
| Input | Only the frozen copy, re-measured (length and SHA-256) on the fresh download, then passed to ffmpeg as `file:<generated absolute path>` through the forced MP4/MOV demuxer and `-protocol_whitelist file`, exactly as the probe is. Staging is never read. |
| Size | The source's display rectangle (pixel aspect ratio and rotation already applied by the probe) is fitted inside a box that follows its orientation, keeping aspect ratio and never enlarged: **1280 x 720** for a landscape or square clip, **720 x 1280** for a portrait one. Both sides are then rounded **down** to even numbers (4:2:0 needs them). The picture is scaled to that size and output pixels are square. A 1080 x 1920 phone clip becomes 720 x 1280, a 720 x 1280 one is unchanged, a 1080 x 1080 one becomes 720 x 720. Both boxes hold 3600 macroblocks, the H.264 level 3.1 maximum, so one level covers both. |
| Rotation | Applied to the pixels by ffmpeg's own automatic rotation before the scale filter; no display matrix survives into the output. |
| Video | libx264, Main profile, level 3.1, `yuv420p`, `-preset veryfast`, CRF 23 capped at 2800 kb/s with a 5600 kb VBV buffer, no scene-cut keyframes, source frame rate kept (1 to 30 fps, so 30000/1001 stays 30000/1001), at most the configured thread count. |
| Segments | MPEG-TS, `independent_segments`, a keyframe forced every 4 seconds so the cut is the encoder's choice, not the source's, VOD playlist, no byte ranges, no map, no keys. |
| Audio | None when the source has none (it stays silent). Otherwise AAC-LC at 128 kb/s, 48 kHz, at most two channels (mono stays mono, 5.1 is mixed to stereo), resampled with `aresample=async=1:first_pts=0`. |
| Dropped | Metadata, chapters, subtitle and data tracks. |
| Names | `master.m3u8`, `rendition.m3u8`, `segment-00000.ts`, `segment-00001.ts`, and so on. All relative, none derived from the upload. |
| Master playlist | Written by the service, not by ffmpeg: one `EXT-X-STREAM-INF` with `BANDWIDTH` (peak segment rate), `AVERAGE-BANDWIDTH`, `RESOLUTION`, `FRAME-RATE` and `CODECS` (`avc1.4d401f`, plus `mp4a.40.2` when audio exists), whose only reference is `rendition.m3u8`. The numbers come from the files that were actually produced. |

## Behavior

1. `SourceProber.probe` runs first (freeze, then probe), so a rejected or unreadable source ends there: the job never reaches
   `TRANSCODING`, and no workspace or ffmpeg process exists.
2. The job moves `SOURCE_SELECTED` to `TRANSCODING` under the lease fence (`JdbcJobStages`, one short transaction; a stale
   token, an expired lease or a job with no selected source gets `false`). A retry reclaims the job as `CLAIMED` and
   passes through the same steps.
3. The frozen object is streamed into scratch again, re-measured, and an empty workspace is created in the same
   locked scratch root, named for the attempt (`hls-attempt-<n>-…`). The disk must have room for the output budget.
4. `FfmpegMediaTranscoder` runs the one argument list. It watches the clock, the cancellation flag, the workspace's
   byte total and its file count, and always kills the process tree.
5. `HlsPackage.seal` reads the variant playlist, accepts only the tags and names this pipeline asks for, requires every
   listed segment to exist and be non-empty, forbids any other file, bounds segment count (300) and duration, and
   writes the master. The result is a closed inventory (`HlsOutput`).
6. The lease is checked once more; an attempt that lost it returns nothing. Otherwise the caller receives a
   `Transcoded` result that owns the workspace and deletes it when closed.

Encoding runs outside any database transaction. The exit status is not trusted (see Findings): `seal` is the
judge.

## Process boundary

M22 revised this table: only the "invalid data" status is now a decode verdict, every other non-zero exit is
retryable, and the command adds `-xerror` and a constant output rate. See
[Encoding hardening](media-encoding-hardening-m22.md#process-boundary-revised).

| Condition | Result |
| --- | --- |
| Executable cannot start, wall-clock timeout (default 15 min), unusable source or workspace, status that means killed or crashed | `Unavailable`, retryable `PROCESSING_FAILED` |
| Any other non-zero exit | `Undecodable`, permanent `CORRUPT_INPUT` (the probe already accepted the bytes, so the tool could parse them and not decode them) |
| Output over `max-output-bytes` (default 300 MiB) or more than 308 files | `OutputTooLarge`, permanent `UNSUPPORTED_MEDIA` |
| Zero exit but an incomplete or foreign workspace | `HlsPackage.Malformed`, retryable `PROCESSING_FAILED` (bounded by the three attempts) |
| Cancellation | `InterruptedException` after the process tree is killed |

"Killed or crashed" is a POSIX signal status (134, 137, 139, 143) or a Windows NTSTATUS failure (`0xC0000000`
to `0xCFFFFFFF`). Nothing else about the status is read, and stderr, stdout, paths and messages never leave the class.

Settings: `libra.media.transcode.executable` (`MEDIA_FFMPEG_PATH`), `timeout` (`MEDIA_FFMPEG_TIMEOUT`, up to 1 h),
`threads` (`MEDIA_FFMPEG_THREADS`, 1 to 16, default 2) and `max-output-bytes` (`MEDIA_FFMPEG_MAX_OUTPUT_BYTES`, 1 MiB to
1 GiB). A configured path that is not an absolute executable file fails startup, and the startup log records the first
line of `ffmpeg -version`. `infra/compose.yaml` passes `MEDIA_FFMPEG_PATH=/usr/bin/ffmpeg` (the Dockerfile already
installs the distribution `ffmpeg` package) and raises the Media scratch tmpfs from 512 MiB to 768 MiB so a 256 MiB
source and a worst-case output can coexist.

The output budget is a measured bound, not a guess: a 1080p pure-noise clip (the worst case for an encoder; its source is
43.8 MB for ten seconds) came out at 3.44 Mb/s on its densest segment, so 610 seconds would be about 262 MB under the
300 MiB budget.

## Findings and repairs

Every item below was found by running a real tool, not by a mock.

| Finding | Repair | Evidence |
| --- | --- | --- |
| **ffmpeg's exit status is the error code, not 1.** An undecodable file exits 183 on a POSIX shell and with the full negative AVERROR value on Windows (`-1094995529`). My first rule treated every negative status as "crashed", so corrupt input was reported as a retryable fault. | Only signal statuses and NTSTATUS failures mean "killed or crashed"; any other non-zero exit is the permanent decode verdict. | `anUndecodableSourceIsAPermanentVerdictAndLeavesNoRendition` (truncated, garbage and empty files) and the disguised-playlist test failed with `Unavailable`, then passed; the stand-in test now runs the Windows negative values. |
| **A zero exit proves nothing.** With an output directory that does not exist, ffmpeg 9 prints "Failed to open file", then "failed to rename file", and exits 0 with no playlist. | Success is only what `HlsPackage.seal` accepts; a clean exit with an incomplete workspace is retried, not trusted and not a verdict on the input (a full disk behaves the same way). | Reproduced by hand on the host tool. The stage's behaviour is covered with scripted stand-ins (`SourceTranscoderTest`), not automated against the real muxer. |
| **A fixture of mine was rejected by the M20 policy.** `-t` placed between two inputs applies to the next input, so the 20 s clip had 22.5 s of video and 20 s of audio. | The generator states `-t` as an output option; the clip is now consistent. | `TRACK_DURATION` rejection before; accepted after. This is also a small confirmation that the M20 check works on a real file. |
| **Dimensions do not prove orientation.** The first rotation assertion compared only the output size, which the scale filter forces whatever the rotation. | The test now encodes each rotated fixture and compares the output's first frame with the source run through an explicit transform with ffmpeg's automatic rotation switched off. | See Orientation below. |
| **A reactor run failed on an M20 test, not on an assertion.** `FfprobeFixtureIntegrationTest` could not delete its JUnit temp directory because Windows still held `invalid-huge-metadata.mp4` for a moment after the probe was killed for output (the same race M20 met in `StagedCopy.close`). The earlier full run passed; only this one failed, so it is a flake. | The two real-tool suites that kill processes on purpose (`FfprobeFixtureIntegrationTest`, `FfmpegTranscodeIntegrationTest`) now clean up through `ScratchCleanup`, which retries for two seconds and still fails on a real leak. | Failed run: `tmp/m21-reactor-verify-final.log` (ignored); the next full run above passed. The stand-in process suites were not changed. |
| ffmpeg 9 moved or renamed the automatic-rotation switch (`-noautorotate` still works as an input option, `-autorotate` is rejected in the position I tried). | The command relies on the default (rotation on) and never mentions the option, and both builds below are checked at pixel level. | Host 9.0.2 and packaged 8.0.1 give the same result. |

### Orientation

The reference transform for a display rotation of 90 degrees (counterclockwise) is `transpose=2`, for 270 it is
`transpose=1`, and for 180 it is `hflip,vflip`; the correct reference must agree and the three others must not.
Mean luma PSNR of the output's first frame against each reference, in the packaged image (ffmpeg 8.0.1):

| Fixture | none | transpose=1 | transpose=2 | hflip,vflip |
| --- | --- | --- | --- | --- |
| rotated 90 | 8.9 | 6.0 | **45.2** | 8.9 |
| rotated 270 | 8.9 | **45.5** | 6.0 | 8.9 |
| rotated 180 | 6.0 | 8.9 | 8.9 | **49.5** |

The host suite asserts above 30 dB for the declared transform and below 20 dB for every other one.

## Fixtures

Fixtures are generated at test time by ffmpeg from synthetic patterns, tones and noise (no third-party media). M21
adds five to the M19/M20 set: `valid-720p-20s-aac` (1280 x 720, 20 s, stereo AAC, **one** source keyframe), `valid-portrait-1080x1920`,
`valid-mono-44100`, `valid-surround-6ch` and `valid-noise-1080p-10s` (44 MB). The existing rotated, silent, 1080p,
anamorphic, NTSC-rate, 600 s and 18000-frame fixtures are encoded too. Tool under test: host `ffmpeg 9.0.2-full_build`
(libx264); packaged `ffmpeg 8.0.1-3ubuntu2` from the image.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `RenditionPlanTest` | Pure | 8 |
| `HlsPackageTest` | Temp directories | 11 |
| `SourceTranscoderTest` | In-memory ports | 10 |
| `FfmpegMediaTranscoderProcessTest` | Real processes, scripted stand-ins for the executable | 14 |
| `SourceTranscodeConfigurationTest` | Spring context runner | 5 |
| `FfmpegTranscodeIntegrationTest` | **Real ffmpeg 9.0.2 and ffprobe**, generated fixtures | 15 |
| `SourceTranscodeIntegrationTest` | Real PostgreSQL 18.6, SeaweedFS 4.46, ffprobe and ffmpeg | 9 |

The real-tool suites do not stop at "exit 0". Every output is decoded end to end through its master playlist with
`ffmpeg -xerror` (empty stderr required), measured by ffprobe (codec, profile, level, pixel format, size, frame rate,
decoded frame count, audio codec, channels, sample rate), and every segment's first video packet must be a keyframe.
Examples: the 1080p clip becomes `h264 Main 3.1 1280x720 30/1` with `aac LC 2 48000`; the 160x96 silent clip stays 160x96
with no audio stream and no `mp4a` in the master; 30000/1001 stays 30000/1001; the 20 s single-keyframe clip yields five
segments of 4 s; the 600 s clip yields 150 segments and 600 s; the 18000-frame clip decodes to 18000 frames.

Beyond the matrix: an output budget below what a clip needs, a 1 ms deadline and a cancellation each leave no ffmpeg process
that was not there before; a file called `-i http;&echo x $(y) 'z'.mp4` encodes normally and produces only the generated names;
a text playlist disguised as `.mp4` that names a loopback listener is not followed (0 connections, `Undecodable`);
truncated, empty and random inputs are permanent verdicts with no master written.

The database stage test proves, with real PostgreSQL, SeaweedFS and tools: an accepted source is frozen, probed and encoded
into a `hls-attempt-1-…` workspace under the lease with the job at `TRANSCODING`, the asset `PROCESSING`, no
`output_prefix`/`master_manifest_key`, no outbox event, no object in the HLS bucket, and the workspace gone after
close; five hostile or unsupported sources never create a workspace; a retry encodes again from the frozen copy with
staging deleted, in `hls-attempt-2-…`; a lease lost before or during the encode returns nothing and leaves no workspace; a
worker killed while `TRANSCODING` is reclaimed (`CLAIMED`, attempt 2) and the stale owner can neither advance nor encode.

### Packaged runtime

Image `libra-media-m21:local` was built from `services/media/Dockerfile`; `infra/ci/media_transcode_packaged/run.ps1` runs
the shipped classes (unpacked from the image's jar) against the generated fixtures with the image's own ffmpeg 8.0.1 and
ffprobe, as the unprivileged user with `--network none --read-only --cap-drop ALL --memory 768m --cpus 2`, a bounded
tmpfs and a 128 process limit. Outputs are in `tmp/m21-packaged/*.txt` (ignored).

| Observation | Result |
| --- | --- |
| 14 accepted fixtures encoded, sealed and decoded | all `decode=CLEAN`, same sizes and codecs as on the host, `DESCENDANTS_AFTER 0` |
| 20 s 1280 x 720 AAC clip | 7.6 s wall, 5 segments, 5.2 MB, peak segment 2.13 Mb/s |
| 1080p noise clip | 8.5 s wall, 3 segments, 3.88 MB, peak segment 3.44 Mb/s |
| Whole matrix, JVM and tools | peak memory 409 MB (this counts the tmpfs outputs and file cache), 71 processes |
| 300 ms deadline on the 20 s clip | `UNAVAILABLE` after 356 ms, no descendants |
| Cancellation 400 ms into the 20 s clip | `CANCELLED` after 434 ms, no descendants |
| Pixel orientation, three rotations | see the table above |

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 10m21s). The report gate confirmed 66 suites and
480 tests with zero failures, errors or skips: Core 186, Media 292 (140 Surefire, 152 Failsafe), recommendation and analytics 2.
All 18 contracts passed and `git diff --check` was clean. M21 added the 72 tests in the table above. Raw logs: `tmp/m21-reactor-verify-final2.log` (ignored); the intermediate runs are listed under Findings.

## Limits

- No production handler exists; nothing uploads, selects, publishes or reports readiness. Output verification, storage
  under an immutable prefix and the READY transition are M23. A first attempt reads the object four times (staging read, freeze
  readback, probe, transcode); a retry after a committed probe reads it once, as M19 noted for the earlier stages.
- The classification of ffmpeg exit statuses is a heuristic. A process killed from outside on Windows exits with 1
  and would read as `Undecodable`; M22 ("kill FFmpeg mid-output") must settle that. A clean exit on a damaged tail (decoder
  errors are not fatal without `-xerror`) was not exercised: M22 covers corrupt tails.
- Only synthetic x264 sources and one muxer (ffmpeg) were encoded. No phone or camera file was tested; variable frame rate,
  edit lists and priming are untested. The bitrate bound was measured on a synthetic noise clip, not on adversarial real content.
- Durations are checked only as finite, positive and within the accepted maximum plus ten seconds; the closer
  audio/video synchronization and frame-count tolerances belong to M22.
- The packaged evidence ran the Java stage from classes unpacked from the image's jar, not as the Spring application, and the full
  Compose stack with the transcode setting was not started. Two ffmpeg builds (9.0.2 and 8.0.1) are the only ones seen.
- CPU is bounded by the thread setting and the container, not by the Java process; a starved supervisor notices its deadline late.
- Local containers and loopback only: not multi-host, not remote CI or deployment. GitNexus was not used: the change is
  almost entirely new code beside two small edits (`FfprobeMediaProber` now shares `ToolProcesses`, and the scratch classes
  gained a directory factory).

Run with Java 21, Docker Desktop and ffmpeg/ffprobe on PATH (or `LIBRA_FFPROBE_PATH`, `LIBRA_FFMPEG_PATH`):

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
docker build -f services/media/Dockerfile -t libra-media-m21:local .
.\infra\ci\media_transcode_packaged\run.ps1
```
