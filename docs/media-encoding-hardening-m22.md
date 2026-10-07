# Media: Encoding correctness and resource exhaustion (Milestone 22)

Verification date: 2026-10-07.
Status: VERIFIED_LOCAL (stage only; see Scope).

## Scope

M22 hardens the M21 transcode stage. It adds no table, migration, API, event, failure code, job stage or setting,
and still installs no production job handler: admitted jobs stay `QUEUED` and an encoded workspace is never ready or
playable. Output upload, verification under an immutable prefix and the READY transition remain M23.

Every fixture is generated locally by ffmpeg at test time (test patterns, tones, flat colour, a flash and a beep, plus
byte edits of those files). No third-party, phone or camera media was used and nothing was fetched from a network.

## Gate

Success requires a decodable rendition consistent with the probed source, not an exit status of zero. A failure
releases the workspace, leaves no ffmpeg process and no publishable output, and blames the input only when the
decoder said the input was invalid.

## Findings and repairs

Each was found by running the real tool before the repair, and each repair has a regression test on the real tool.

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| **A damaged tail produced a short, well-formed rendition with exit 0.** The probe reads only the index, which a fast-start file keeps whole at its front. A 20 s clip cut at 60 % or 95 % of its bytes, or with its last 30 % overwritten, was accepted by the probe; ffmpeg then logged decode errors, wrote a playlist with `#EXT-X-ENDLIST` covering 12.0 s, 18.9 s and 14.1 s, and exited 0. M21 would have sealed it. | `-xerror` makes any decode error fatal; the tool then exits with the "invalid data" code and the stage reports permanent `CORRUPT_INPUT`. Independently, `HlsPackage.seal` now requires the rendition to last as long as the probed source, within the policy's own track slack (2 s, or 10 % above 20 s). | `aDamagedTailIsAPermanentVerdictEvenThoughTheToolFinalizesAShortPlaylist`: all three probe as 20 s and end `Undecodable`; for the 60 % cut the leftover playlist, sealed on its own, is rejected `DURATION`. The 95 % cut (18.9 s) is inside the slack: there `-xerror` is the only guard. |
| **ffmpeg exits 255 after catching SIGTERM or SIGINT, and still finalizes a short playlist.** In the packaged image a TERM or INT two segments into a 20 s clip gave exit 255 and a playlist with an end tag. M21 read every non-signal status as a decode verdict, so a valid upload would have been rejected for good. | Exit classification inverted: only AVERROR_INVALIDDATA (`-1094995529` on Windows, its low byte `183` on Linux) is `Undecodable`; every other non-zero status is `Unavailable` (retryable, bounded by the three attempts). The duration check rejects the short playlist even if the status were lost. | Packaged `signal-term.txt`; stand-in `everyOtherFailureStatusSaysNothingAboutTheInputAndIsRetryable` (1, 2, 127, 228, 244, 254, 255 and the Windows negatives). Every undecodable input tried (truncated, garbage, empty, ftyp only, disguised playlist, three damaged tails), by hand and in the real-tool suite, exits with exactly that code. |
| **An encoder killed from outside on Windows reads as corrupt input** (open item from M21). Windows reports exit 1 for a terminated process. | Covered by the same rule: 1 is `Unavailable`. | `anEncoderKilledFromOutsideMidOutputIsRetryableAndItsPartialWorkspaceIsNotARendition` kills the real ffmpeg (not through the runner) after its second segment opens: `Unavailable`, no master, the leftover is rejected by `seal`, no process left. |
| **A full disk exits 0 and leaves a cut segment.** With the workspace on a 2 MiB file system, ffmpeg (with and without `-xerror`) exited 0 with an empty playlist, one whole segment, one segment cut mid-packet (1,024,000 bytes run by hand, 1,028,096 through the Java runner) and three empty ones. | `seal` already rejected the empty playlist (`HEADER`, retryable). It now also requires every segment to be whole 188-byte transport packets starting with the sync byte, so a playlist that survived the cut cannot vouch for a truncated segment. | Packaged `full.txt`; `aSegmentThatIsNotWholeTransportPacketsIsRejected`, `aMissingOrEmptyPlaylistOrSegmentIsNotARendition`. |
| **A clip shorter than half a second could never be encoded.** For a one-frame (33 ms) or 100 ms clip, both accepted by the probe, ffmpeg writes `#EXT-X-TARGETDURATION:0`. M21 rejected that as `INCOMPLETE`, a retryable fault, so the job would have spent its three attempts and ended `RETRY_EXHAUSTED`. | `seal` accepts ffmpeg's target of 0 when every rounded segment duration fits it, and the variant playlist is now written by the service from the checked inventory, with a target duration of at least 1 and durations to the millisecond (never `0.000`). | `theShortestClipsBecomeOneSegmentWithATargetDurationOfOne` (real ffmpeg: one frame decoded; three frames and stereo AAC), `aClipShorterThanHalfASecondGetsATargetDurationOfOne`. |
| **A variable-rate source was padded to its peak rate.** A clip with 60 fps timestamps for one second and 15 fps after it averages 300/11 (27.3 fps, accepted). ffmpeg's default for HLS made the output constant at the stream's nominal 60 fps: 237 frames instead of 105, 1280x720 at 60 fps (twice the macroblock rate level 3.1 allows), and a master playlist saying `FRAME-RATE=27.273`. | `-fps_mode cfr -r <validated average>`: the output is constant at the rate the policy accepted and the master states. | `aVariableRateSourceIsDeliveredAtItsAverageRateAndNeverAboveTheLevelLimit`: `r_frame_rate=300/11`, 108 frames, smallest frame gap 36.7 ms, level 31. CFR sources are unchanged (600 of 600 frames for the 20 s clip, 18000 for the dense clip). |

The variant playlist rewrite has a second effect: neither playlist that leaves the workspace contains a byte the encoder
chose. Both are generated from the closed inventory, and every real-tool encode asserts that their only references
are inventory names, every `EXTINF` is finite and positive, and the target duration covers each segment.

## Checks that found no defect

| Check | Evidence |
| --- | --- |
| No upscaling, aspect ratio, even sizes at the policy's size limits | 16x16 stays 16x16; 1920x16 becomes 1280x10 and 16x1920 becomes 10x1280 (level 3.1, decodes); an odd display width reachable only through the pixel aspect ratio (98x64 with 3:2 pixels, 147x64) becomes 146x64 with square pixels. The plan never exceeds the display size. |
| Orientation | M21's pixel-level comparison (three rotations) still passes with the new command. |
| Audio/video synchronization | A white flash and a 1 kHz beep both start at 2.0 s. Output: beep minus flash within 40 ms, both for an aligned source and for one whose audio track starts 0.5 s after the video (padding keeps the timeline). Measured by hand with the final command on the host: +5 ms and +15 ms, the same offsets the sources themselves have (AAC frame granularity). An audio track 1.5 s longer than the video (inside the track slack) is accepted and the rendition follows the video (6.0 s of 7.5 s). |
| Lease lost during the encode | `aLeaseTakenOverMidEncodeStopsTheRealEncoderThroughTheWorkerHeartbeat`: the real `BoundedMediaWorker` (200 ms renewal) runs the stage against real PostgreSQL, SeaweedFS and tools. After the encoder's second segment opens, the claim is expired and a successor claims attempt 2. The failed renewal cancels the stage, ffmpeg is killed, no outcome is recorded, no workspace or process survives, nothing is in the HLS bucket, and the successor encodes from the frozen copy attempt 1 committed. |
| Output budget | M21's budget test (`OutputTooLarge` at 1 MiB, no process left) passes with the new command. |
| Duration limits | The 600 s and 18000-frame clips still yield 150 segments and 18000 decoded frames. |

## Process boundary (revised)

| Condition | Result |
| --- | --- |
| Exit with AVERROR_INVALIDDATA (`-1094995529`, or `183` as an eight-bit status) | `Undecodable`, permanent `CORRUPT_INPUT` |
| Any other non-zero exit: a caught signal (255), a process terminated from outside (Windows 1, Linux 137), a write or memory errno, a crash | `Unavailable`, retryable `PROCESSING_FAILED` |
| Deadline, cancellation, unusable source or workspace | Unchanged from M21 |
| Output over budget | Unchanged: `OutputTooLarge`, permanent `UNSUPPORTED_MEDIA` |
| Exit 0 with an empty, unfinished, foreign, cut or too-short workspace | `HlsPackage.Malformed`, retryable `PROCESSING_FAILED` |

A clean exit with a rendition shorter than the source is retried rather than called corrupt. None of the observed
causes reaches that check with exit 0 any more (a caught signal now exits 255 and is retried, a damaged tail exits with
the invalid-data code), so it stays as a backstop whose cause could as well be the infrastructure. Three attempts bound
a deterministic case.

## Fixtures

Thirteen fixtures are added to the M19-M21 set, all generated by ffmpeg at test time: `valid-one-frame`, `valid-100ms-aac`,
`valid-16x16`, `valid-strip-1920x16`, `valid-strip-16x1920`, `valid-odd-sar-147x64`, `valid-vfr-27fps`,
`valid-sync-flash-beep`, `valid-sync-late-audio`, `valid-audio-longer`, and three damaged tails of one 20 s 720p AAC
clip with its index at the front: `corrupt-tail-cut-60`, `corrupt-tail-cut-95` and `corrupt-tail-garbage` (last 30 %
replaced by seeded random bytes). Tools under test: host `ffmpeg 9.0.2-full_build` and packaged `ffmpeg 8.0.1-3ubuntu2`.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `HlsPackageTest` | Temp directories | 17 (6 new; existing cases now use transport packets and a source duration) |
| `SourceTranscoderTest` | In-memory ports | 11 (1 new) |
| `FfmpegMediaTranscoderProcessTest` | Real processes, scripted stand-ins | 16 (2 new, 2 rewritten for the new classification, 1 extended) |
| `FfmpegTranscodeIntegrationTest` | **Real ffmpeg 9.0.2 and ffprobe**, generated fixtures | 21 (6 new; every encode now also checks both playlists) |
| `SourceTranscodeIntegrationTest` | Real PostgreSQL 18.6, SeaweedFS 4.46, ffprobe, ffmpeg and the real worker | 10 (1 new) |

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 10m18s). The report gate confirmed 66
suites and 497 tests with zero failures, errors or skips: Core 186, Media 309 (150 Surefire, 159 Failsafe),
recommendation and analytics 2. All 18 contracts passed and `git diff --check` was clean. M22 added 16 tests. Raw log:
`tmp/m22-reactor-verify.log` (ignored).

Focused runs that failed before the final ones and are not counted: the first real-tool run failed one new test on my own
helper (ffprobe's csv rows can end with an empty column, `1.473333,`), and the first stage run of the worker test timed out
because the storage fixture caps objects at 1 MiB and the 20 s clip is 3.9 MB (the freezer rightly reported
`SIZE_MISMATCH`); that test class now allows 8 MiB.

### Packaged runtime

Image `libra-media-m22:local` was built from `services/media/Dockerfile`; `infra/ci/media_transcode_packaged/run.ps1`
runs the shipped classes (unpacked from the image's jar) with the image's ffmpeg and ffprobe 8.0.1, as the unprivileged
user with `--network none --read-only --cap-drop ALL --memory 768m --cpus 2`, a bounded tmpfs and a 128 process limit.
Outputs are in `tmp/m22-packaged/*.txt` (ignored).

| Observation | Result |
| --- | --- |
| 24 accepted fixtures encoded, sealed and decoded (`matrix.txt`) | all `decode=CLEAN`, `DESCENDANTS_AFTER 0`. One frame: 33 ms, 1 frame. VFR: `300/11`, 108 frames. Strips: 1280x10 and 10x1280. 20 s clip: 6.7 s wall, 5 segments, peak 2.13 Mb/s, as in M21. |
| Whole matrix, JVM and tools | peak memory 407 MB in the final run (701 MB in an earlier run of the same matrix; the counter includes every output kept on the tmpfs and the page cache, so it varies); 71 processes |
| Damaged tails (`rejects.txt`) | all three `PROBE-ACCEPTED 20000ms` then `UNDECODABLE`; each leftover playlist exists (ffmpeg finalized it) |
| SIGTERM after two segments (`signal-term.txt`) | `UNAVAILABLE`; ffmpeg wrote a playlist with an end tag over four segments; `seal` rejects it `DURATION` |
| SIGKILL after two segments (`signal-kill.txt`) | `UNAVAILABLE`; no playlist; `seal` rejects `PLAYLIST_MISSING` |
| Workspace on a 2 MiB tmpfs (`full.txt`) | exit 0; empty playlist, one whole segment, one cut at 1,028,096 bytes (not whole packets), three empty; `seal` rejects `HEADER` |
| 300 ms deadline, cancellation 400 ms in | `UNAVAILABLE` after 343 ms, `CANCELLED` after 462 ms, no descendants |

## Limits

- `-xerror` is strict by design: a source with any decode error is rejected permanently, including files a lenient
  player would show with a glitch. Only synthetic damage was tried; real phone or camera files with recoverable errors
  (an open-GOP start, a damaged last frame) were not, and could now be rejected.
- The duration backstop allows the policy's track slack (2 s, or 10 %). It catches a large truncation on its own (the
  60 % cut), not a small one (the 95 % cut, 1.1 s short): there the decoder's status is the guard.
- The transport-packet check is structural. A full disk that cuts a segment exactly on a packet boundary while the
  playlist survives would pass it; the observed cuts did not land on one, and the playlist was empty.
- Statuses are classified by number. A future ffmpeg that reports a decode failure with another code would make it
  retryable (three attempts, then `RETRY_EXHAUSTED`), never a wrong permanent verdict.
- Constant output rate duplicates or drops frames of a variable-rate source to its average rate; motion in a burst is
  thinned. Only one synthetic variable-rate clip was tried.
- Synchronization was measured on two synthetic flash/beep clips; edit lists, priming and long real recordings were
  not tested.
- Disk exhaustion and signals ran in the packaged image only (a small tmpfs, `ProcessHandle.destroy`); on the host
  the external kill was tested, not a full disk.
- The packaged evidence runs the Java stage from classes unpacked from the image's jar, not as the Spring application,
  and the full Compose stack with the stage enabled was not started. Local containers and loopback only: not
  multi-host, remote CI or deployment. GitNexus was not used.

Run with Java 21, Docker Desktop and ffmpeg/ffprobe on PATH (or `LIBRA_FFPROBE_PATH`, `LIBRA_FFMPEG_PATH`):

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
docker build -f services/media/Dockerfile -t libra-media-m22:local .
.\infra\ci\media_transcode_packaged\run.ps1
```
