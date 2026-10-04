# Media: ffprobe validation and metadata policy (Milestone 19)

Verification date: 2026-10-05.
Status: VERIFIED_LOCAL (stage only; see Scope).

## Scope

M19 adds the probe stage that runs after source freezing. It reads the frozen private copy once, asks one trusted
ffprobe executable what the file really contains, judges that against the first-slice policy, and records only
the validated facts under the current lease. It adds one table (`V4__add_media_source_metadata.sql`), three
settings and no API, event, failure code or job stage.

The stage is a Spring bean (`SourceProber`), present only when `libra.media.probe.executable` is set. **No
production job handler is installed.** The shipped runtime still has no `MediaJobHandler`, so enabling the worker
still fails startup and admitted jobs stay `QUEUED`. FFmpeg transcoding, HLS, READY and playback remain later
milestones. An accepted source is "probed", never READY or playable: the asset stays `PROCESSING`, the aggregate
version does not move and no outbox event is written.

## Policy

The policy judges stream facts only. Filename, MIME type and the declared duration are never inputs.

| Fact | Accepted | Otherwise |
| --- | --- | --- |
| Container | `format_name` contains `mov` and `mp4`, major brand in `isom iso2 iso4 iso5 iso6 mp41 mp42 avc1` | `UNSUPPORTED_MEDIA` (QuickTime `qt`, Matroska, anything else) |
| Duration | From the container, greater than 0 and at most 600.000 s, stored as milliseconds | missing, zero, negative, non-finite: `CORRUPT_INPUT`; longer: `UNSUPPORTED_MEDIA` |
| Streams | Exactly one video track, at most one audio track, nothing else | extra video, extra audio, cover art, subtitles, timecode or data tracks: `UNSUPPORTED_MEDIA`, whatever their order |
| Video | H.264; profile Baseline, Constrained Baseline, Main or High; `yuv420p` | other codec, High 10/4:2:2/4:4:4, other pixel formats: `UNSUPPORTED_MEDIA` |
| SDR | transfer is not `smpte2084`/`arib-std-b67` and primaries are not `bt2020`; untagged colour is SDR | `UNSUPPORTED_MEDIA` (HDR) |
| Size | Coded and display rectangles each have a long side of at most 1920, a short side of at most 1080 and at least 16 | `UNSUPPORTED_MEDIA`; missing or non-positive dimensions: `CORRUPT_INPUT` |
| Frame rate | `avg_frame_rate`, exactly 1 to 30 fps (30000/1001 passes, 30001/1000 does not) | outside: `UNSUPPORTED_MEDIA`; `0/0`, `N/A`, zero denominator, absent: `CORRUPT_INPUT` |
| Audio | none, or AAC with 1 to 8 channels and 8 to 96 kHz | other codec or parameters: `UNSUPPORTED_MEDIA`; absent parameters: `CORRUPT_INPUT` |

Rotation and aspect ratio are normalized once, so later sizing never reads raw stream data. A display-matrix
rotation (or the legacy `rotate` tag) must be a whole quarter turn and is reduced to 0, 90, 180 or 270;
anything else is `UNSUPPORTED_MEDIA`. The pixel aspect ratio widens the coded width (an unspecified `0:1`,
`N/A` or missing ratio is square, a malformed one is `CORRUPT_INPUT`), and a quarter turn then swaps the axes.
The result is the stored `display_width` and `display_height`. A stored row is already a statement that the policy
passed: the table repeats the same bounds as `CHECK` constraints as a last line of defence.

Every reason above is the same two stable codes the worker already persists, `UNSUPPORTED_MEDIA` and
`CORRUPT_INPUT`. A finer internal reason exists for diagnosis in tests and is never stored or sent.

## Behavior

1. `SourceFreezer.freeze` runs first, so the bytes are proven and the committed key is selected.
2. A retry after a committed probe returns the recorded facts without reading the object again.
3. Otherwise the frozen object is streamed into scratch (`SourceReader`, source area only, never staging),
   re-measured against the declared length and SHA-256, and handed to ffprobe. A different length or hash is
   `SIZE_MISMATCH` or `CHECKSUM_MISMATCH` and the probe never runs.
4. ffprobe's JSON is read into a neutral report, the policy decides, and `JdbcSourceMetadata.record` writes the
   row under the same lease fence as selection (`LeaseFence.lockCurrent`), bound to the selected source key.
   Recording the same facts again is idempotent; different facts or a non-selected key are an error.
5. A rejected source leaves its frozen copy in place (it was proven, then judged); a retry reaches the same verdict
   from that copy alone.

The probe runs outside any database transaction. Selection and recording are separate short transactions.

## Process boundary

`FfprobeMediaProber` runs one executable with a fixed argument list: `-v error -hide_banner -protocol_whitelist
file -print_format json -show_format -show_streams -i file:<absolute scratch path>`. The path is generated
by the service, so a hostile filename is never part of the command (the test names a file like `-i http;&echo x $(y)`).
The whitelist and `file:` prefix mean a container cannot send the tool to another URL. The environment is cleared
(Windows keeps `SystemRoot` only), stdin is closed, stderr is discarded and stdout is bounded
(`max-output-bytes`, default 256 KiB). A wall-clock timeout (`timeout`, default 20 s) and the worker's cancellation flag
both kill the process and its descendants.

Failures cross the boundary as two exceptions only, with no process output, path or parser message:

| Condition | Result |
| --- | --- |
| Executable cannot start, timeout, missing scratch file, pipe held open | `Unavailable`, retryable `PROCESSING_FAILED` |
| Non-zero exit, output over the limit, JSON that is not a probe document | `Unreadable`, permanent `CORRUPT_INPUT` |
| Cancellation | `InterruptedException` after the process tree is killed |

A configured path that is not an absolute executable file fails startup. The startup log records the first line
of `ffprobe -version`.

Packaging: `services/media/Dockerfile` installs the distribution `ffmpeg` package and `infra/compose.yaml` passes
`MEDIA_FFPROBE_PATH=/usr/bin/ffprobe`. Nothing is looked up on `PATH`.

## Findings and repairs

| Finding | Repair | Evidence |
| --- | --- | --- |
| A real SDR fixture carried no `color_transfer` or `color_primaries`, and `Set.of(...).contains(null)` throws. Every synthetic report in the unit tests had colour set, so only the real tool found it. | Untagged colour is tested for null before the lookup and is SDR. | Eight of ten real-ffprobe tests failed with a `NullPointerException`, then passed; a unit case now covers untagged colour. |
| A recording against a not-yet-selected asset read a NULL `selected_source_key` through `stream().findFirst()`, which rejects null elements. | Read through `queryForList`. | `metadataMustDescribeTheSelectedSourceNotAnotherKeyOrNone` failed with a `NullPointerException`, then passed. `JdbcJobSources.selectedKey` has the same shape but is only reached after a selection update touched no row; it is unchanged and not exercised by M19. |
| The HDR fixture had only `colorspace=bt2020nc`; the transfer and primaries flags were dropped by ffmpeg. | The generator adds `setparams` for all three. | `invalid-hdr.mp4` was accepted, then rejected as `HDR`. |
| Two generator commands never ended (cover-art and subtitle inputs with no output limit) and one wrote a 155 MB file. | Both outputs are limited to one second. | Fixture sizes below. |
| Under an empty environment a stand-in script could not find `ping`/`sleep`. | Stand-ins name their helpers absolutely; production passes an absolute executable. | `FfprobeMediaProberProcessTest`. |
| A child's working directory kept the test's temp directory locked on Windows and made one test flaky. | The probe process no longer sets a working directory; the input is an absolute path. | Four consecutive clean unit runs. |

## Fixtures

Nineteen synthetic files (about 0.9 MB) are generated into `services/media/target/media-fixtures` by `MediaFixtures`
(test code) the first time a probe suite needs them, from `testsrc2`, `sine` and `color` patterns, so no third-party
media is involved and nothing is committed. ffmpeg comes from `LIBRA_FFMPEG_PATH`, else from the directory of the
ffprobe in use; a missing tool fails the build. The directory carries a completion marker, so an interrupted run is
regenerated, and `clean` removes it. Accepted: 1080p30 with AAC, 160x96 silent, a 90-degree display-matrix clip,
1440x1080 with 4:3 pixels, 30000/1001 fps, and a 600 s clip. Rejected: HEVC, 10-bit, HDR, 4K, 60 fps, 601 s, two
audio tracks, MP3 audio, Matroska, cover art, a subtitle track. Corrupt: a file cut in half (no `moov`) and 4 KiB of
random bytes.

Tool under test: `ffprobe version 9.0.2-full_build-www.gyan.dev` (Gyan.FFmpeg 9.0.2 from winget, with libx264),
the same release that generated the fixtures. The Docker image uses the Ubuntu `ffmpeg` package instead; its
version is recorded under Verification.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `SourcePolicyTest` | Synthetic reports | 12 |
| `FfprobeJsonTest` | Hand-written ffprobe-shaped JSON | 7 |
| `SourceProberTest` | In-memory ports | 9 |
| `FfprobeMediaProberProcessTest` | Real processes, scripted stand-ins for the executable | 12 |
| `FfprobeFixtureIntegrationTest` | **Real ffprobe 9.0.2**, generated fixtures | 10 |
| `SourceMetadataIntegrationTest` | Real PostgreSQL 18.6 | 8 |
| `SourceProbeIntegrationTest` | Real PostgreSQL, SeaweedFS 4.46 and ffprobe | 6 |

M19 added 64 tests. The suites that do not name a real tool prove the contract (policy table, process limits,
fencing); only the last three observe real ffprobe output.

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 10m39s) with ffprobe 9.0.2 supplied
through `LIBRA_FFPROBE_PATH`. The report gate confirmed 57 suites and 385 tests with zero failures, errors or
skips: Core 186, Media 197 (83 Surefire, 114 Failsafe), recommendation and analytics 2. All 18 contracts passed and
`git diff --check` was clean. Raw log: `tmp/m19-reactor-verify.log` (ignored).

Packaged binary: the Media image was built from the changed Dockerfile (`libra-media-m19:local`, built outside
Compose) and contains `ffprobe version 8.0.1-3ubuntu2` (`ffmpeg` package `7:8.0.1-3ubuntu2`) at `/usr/bin/ffprobe`,
owned by root and run as the unprivileged `libra` user. The policy-relevant facts it prints (container and brand,
duration, and per stream codec, profile, size, pixel format, aspect ratio, frame rate, colour, rotation, cover-art
flag, channels, sample rate) were compared with ffprobe 9.0.2 on all 19 fixtures using the exact argument list: all
19 were identical, and both tools failed on the two corrupt files. That compares the two binaries' output; the
Java stage was not run inside the container, and the full Compose stack with `MEDIA_FFPROBE_PATH` was not started.

## Limits

- Hostile and malformed input matrices, a probe that hangs on a real file, output bombs from the real binary,
  and resource budgets of the packaged runtime belong to M20. M19 proves the process boundary with scripted
  stand-ins and a handful of corrupt real files only.
- The whitelist test shows a playlist referencing `http://` and an ffconcat file referencing a local path are
  not followed, but the ffconcat case is also stopped by that demuxer's own safe mode, so it is not an
  isolated proof of the whitelist.
- Real fixtures cover one encoder (libx264 in ffmpeg 9.0.2). Files from phones, cameras or other muxers can set
  fields differently; the policy reads what ffprobe prints, and untagged colour, a missing `avg_frame_rate` and a
  legacy `rotate` tag are handled, but no such third-party file was tested.
- A BT.2020 matrix with SDR transfer and primaries is accepted (judged on transfer and primaries only).
- `yuvj420p` (full-range JPEG colour) is rejected with other pixel formats; whether to allow it is a later choice.
- Variable frame rate is judged by `avg_frame_rate` only; contradictory metadata between tracks and the container
  is M20.
- The probe measures the frozen object after a second download, so a large object is read three times in total
  across freezing (hash, readback) and probing; retries after a committed probe read nothing.
- Local containers and loopback only: not multi-host, not remote CI or deployment. GitNexus was not used; the
  change is almost entirely new code beside two small edits.

Run with Java 21, Docker Desktop, an ffprobe (`LIBRA_FFPROBE_PATH` or on `PATH`) and the matching ffmpeg beside it
(or `LIBRA_FFMPEG_PATH`). The full-reactor result above was recorded with committed fixtures; fixtures are now
generated at test time, and only the two suites that use them (`FfprobeFixtureIntegrationTest`,
`SourceProbeIntegrationTest`, 16 tests) were rerun afterwards, passing twice:

```powershell
$env:LIBRA_FFPROBE_PATH = "C:\path\to\ffprobe.exe"
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
```
