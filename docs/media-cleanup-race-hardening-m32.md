# Media: Cleanup races and observability privacy (Milestone 32)

Verification date: 2026-10-19.
Status: VERIFIED_LOCAL (Media against real PostgreSQL 18.6 and SeaweedFS 4.46 through Testcontainers, and a whole
Media application started in process against them; Docker Desktop on one Windows workstation; no Compose smoke).

## Scope

M32 hardens [M31's lifecycle cleanup](media-lifecycle-cleanup-m31.md). It races cleanup with the writers M31's grace
exists for: an old worker still writing an abandoned attempt, a successor selecting and finalizing, a browser PUT
across the upload deadline, and other sweepers. It interrupts cleanup at every step and resumes it, audits what Media
publishes about failures, and checks what an operator sees when a dependency cannot answer. Each finding below was
reproduced by a test against the M31 code before it was repaired (`tmp/m32-repro-before.log`). M32 adds
`V9__record_media_storage_layout.sql` and `MediaStorageLayout`. There is no contract change.

## Gate

Cleanup is resumable, narrowly owned and safe under the tested races. Observability explains failures without
exposing sensitive payloads, and does not claim success when a dependency cannot be checked.

## Findings

| Finding | Before (M31 code) | After |
| --- | --- | --- |
| H1. A worker whose heartbeat is stuck in a renewal (a slow pool or database), or was paused along with the process, keeps writing after its lease has run out. | `cancelled` reported only a loss the heartbeat had already seen. `aStalledHeartbeatCannotKeepAnExpiredLeaseWriting` failed. | The worker keeps its own lease validity (below). |
| H2. After a change to a source or HLS prefix (or a bucket), cleanup derives every unselected key from today's layout. It finds nothing there and records the upload as cleaned while its objects remain. | Recorded clean after two passes. `aChangedStorageLayoutIsNeverRecordedAsCleaned` failed. | V9 records the layout. An instance configured differently refuses to start. A plan whose selection today's layout does not name is refused with `CLEANUP_LAYOUT_CHANGED`. |
| H3. Gauge reads had no statement timeout (an M31 limit), so a table lock or a stalled database held the sampling thread. | Timed out after 8 s under `LOCK TABLE ... ACCESS EXCLUSIVE`. | Each gauge read is a statement with a 2 s query timeout; when it times out, the gauge reports NaN. |
| H4. On the internal upload API, an exception that no handler names reached the container. The container logged its message and cause chain at ERROR, and Core got an empty 403. | `ServletException` carrying a JDBC URL and a signed-URL fragment. `anUnexpectedFailureIsASanitizedProblemNotAContainerError` failed. | A catch-all answers `503 MEDIA_UNAVAILABLE`, which every route's contract already lists. It logs one fixed line: code, the problem's correlation id, the exception type. A malformed path id stays `400 INVALID_REQUEST`. |

### Worker lease validity (H1)

A claim or renewal sets the lease to the database clock plus the lease duration. The worker measures the same duration
from just before it sends the claim or renewal, so its own validity never ends after the database's. Once that validity
has passed, `cancelled` is true and the lapse counts as a loss. The handler stops before its next storage write. The
worker records no retry, failure or release, even if a renewal stuck in the pool is confirmed later: the confirmation
is measured from when the renewal was asked, so it cannot revive the lapsed lease. The worker keeps both a monotonic
and a wall clock, and either one passing ends the validity. The monotonic clock may stand still while a host is
suspended, and the wall clock may jump.

With this, every worker write is bounded by settlement:

1. A fenced write starts before the writer's validity ends.
2. That is before its database lease ends, which is before a successor can claim the job, and so before the job turns
   terminal.
3. The request then completes or is abandoned within `MEDIA_S3_REQUEST_TIMEOUT`. That is at most 5 min, the SDK's API
   call timeout.
4. The write grace is at least 5 min.

Two gaps remain: a pause between the check and the request leaving the process, and a server that commits a fully
received body after the client gave up.

### Storage layout (H2)

Only an upload's selected source and output are stored as keys. The keys of every other attempt are named from the
buckets and prefixes, by the worker that writes them and by the cleanup that deletes them. `media_storage_layout` records
those buckets and prefixes once.

At startup, `MediaStorageLayout` inserts the configured layout if none is recorded, and otherwise compares the two. A
mismatch ends startup. The message names the differing settings (`hls_prefix`), never their values. The S3 adapter bean
depends on this check, and the worker and sweeper beans resolve it before they start. As a result, no claim or pass runs
under another layout. `anInstanceConfiguredWithAnotherLayoutNeverStartsItsWorkerOrSweeper` starts the whole application
with the worker and the sweeper enabled and a job queued: startup ends on the layout, and the job is still `QUEUED` with
no attempt.

`ObjectCleanup` adds a second line of defence. It refuses a plan whose recorded selection is not among the keys
derived for its attempts, before touching anything. The sweeper counts `CLEANUP_LAYOUT_CHANGED` and moves on.

Once any upload exists, a trigger refuses to update or delete the layout. The selections are immutable (V5), so objects
cannot be moved under another layout in place. Before the first upload, a mistyped first layout is corrected by
deleting the row.

## Races and interruptions

`CleanupRaceIntegrationTest` runs against real PostgreSQL and SeaweedFS:

- **Old worker against a finalizing successor.** For the whole test, an old attempt keeps writing new objects under its
  prefix. Meanwhile the successor selects its source, begins transcoding and finalizing, and commits READY, while a
  sweeper with no grace polls throughout.
  - Nothing is deleted before READY. Every late object of the old attempt is deleted after it.
  - The upload is recorded only once the writer stops.
  - The selected source, the selected output and an unrelated key survive.
- **Concurrent sweepers.** Three sweepers race over six settled uploads. Each upload is recorded exactly once (the
  counter grows by six), and no selected or unrelated object is deleted.
- **Interrupted passes.** A pass is interrupted in three places:
  1. after taking the upload (the attempt is recorded, nothing is deleted);
  2. by a storage failure after the first deletion;
  3. by a database that refuses the record after everything was deleted.

  An ordinary pass then records the upload once, with the selection and the unrelated key intact.
- **Browser PUT across the deadline.** A PUT through the signed URL, admitted before the deadline, keeps sending after
  the upload expired.
  - It lands, completion answers `UPLOAD_EXPIRED`, and cleanup deletes the object before recording the upload.
  - A PUT still in flight when cleanup records the upload (with a zero grace in the test) lands afterwards and stays.
    The grace must therefore outlast every browser PUT (Limits).

## Observability

An Explore agent audited Media's log calls, meters, problem responses, actuator endpoints, logging levels and persisted
failure codes, covering `services/media/src/main`, `application.yml` and the Nginx configurations. It found nothing High
or Medium; H4 was its one actionable finding. What it confirmed:

- Logs carry fixed codes only.
- Every meter label is a fixed value.
- Problem bodies carry a fixed code and a random correlation id.
- Failure codes are enums held by CHECK constraints.
- Health shows no details.

`anInterruptedPassResumesWithoutTouchingSelectedOrUnrelatedObjects` checks what a run with injected storage and
database failures leaves behind. The log carries `CLEANUP_STORAGE_UNAVAILABLE` and `CLEANUP_FAILED` and no identifier,
bucket, key or signed-URL text. No meter in the registry carries an identifier, bucket or URL. The H4 test checks the
same for the upload API's log line.

A dependency that cannot be checked shows as unknown, never as healthy:

- Gauges are NaN on a failed or timed-out read (H3).
- Readiness is `DOWN` when PostgreSQL or either bucket cannot be reached; `MediaStorageIntegrationTest` covers denied
  and offline storage (M06).
- Cleanup records nothing while storage or the database refuses.

## Findings of the review

A code-reviewer agent reviewed the full draft. It confirmed H1 to H4 at their core and raised one major finding and
several minor ones:

| Finding | Disposition |
| --- | --- |
| F1 (major). The catch-all answered 503 with no trace for the operator; the correlation id was logged nowhere. | Fixed: one ERROR line with a fixed code, the correlation id and the exception type, never the message. The H4 test asserts it. |
| F2. `RuntimeException` would also catch framework 4xx (`ResponseStatusException`, method validation), and a server-side converter defect became 400. | Fixed: `ErrorResponseException` 404 stays `NOT_FOUND` and other 4xx stay `INVALID_REQUEST`; `ConversionNotSupportedException` goes to the catch-all. |
| F3. The query timeout bounds a statement only once a connection is held. A drained pool waits for Hikari's connection timeout, and one scrape under a lock costs about 2 s per gauge. | Kept; listed under Limits. |
| F4, F5. A mistyped first layout was fixed forever, the "update the row" migration could not work (V5 freezes selections), and a plan with no selection cannot show a change. | V9 now refuses update or delete once any upload exists, and documents correction before the first upload. The plan check is described as a second line behind the startup check. |
| F6, F7, F8, F9. The worker test's "nothing recorded" checks could pass vacuously; the startup ordering was untested; the log claim was not asserted; the PUT test depended on a 3 s wall-clock deadline. | Fixed: `aLateRenewalCannotReviveALapsedLeaseAtFinalization`; the whole-application startup test; log assertions; a 30 s signature with the deadline moved in the database. |
| F11, F12, F14, F15, F16. Overlap guard now defensive, an inconsistent lapse check, prefix width, unnamed differing column, an unclosed scratch space. | Fixed. |
| F13. A renewal confirmed after the lapse leaves a lease nobody uses until it expires, which costs an attempt. | Kept as intended (the slot is released by expiry); listed under Limits. |
| F17. The layout parameter is only an implicit dependency. | Kept: the parameter documents the dependency in the bean signature. |

The first ci-gate run failed five Failsafe suites, all on one fixture rather than on M32's code. In
`valid-1080p-aac.mp4`, the `-t 1` of `MediaFixtures.pattern` landed on the tone input, so only `-shortest` ended the
1080p video. Under load, x264's buffered frames got through: three of six parallel standalone runs produced a 3.03-3.1 s
video beside a 1 s audio track, and `SourcePolicy` rejects that as `TRACK_DURATION`. The two accepted tone fixtures are
now cut by an output `-t`, as the file's 20-second fixture already was (1.000/1.000 in 16 of 16 loaded runs), and the
fixture marker moved to `.complete-m32-1` so they regenerate (`tmp/m32-fixture-fix.log`, 63 tests in the five suites).

## Evidence

**Reproduction** (`tmp/m32-repro-before.log`, M31 main code with M32's tests). The four findings failed:

- H2: recorded clean;
- H3: timed out after 8 s;
- H4: `ServletException` with the injected detail;
- H1: `cancelled` false past the lease, in the unit suite.

**Mutants** (`tmp/m32-mutation-*.log`, summary `tmp/m32-mutation-summary.txt`). Each mutant was run against its named
suite and counted as killed only on a failing assertion, not a build error:

- K1: no local validity.
- K2: a renewal does not extend the validity.
- K3: finalization ignores a lapse.
- L1: no plan check.
- L2: no layout comparison.
- L3, L4: the worker, or the sweeper, does not resolve the layout before starting.
- S1: the sweeper stops at a layout refusal.
- T1, T2: no query timeout (lifecycle, outbox).
- P1: no catch-all.
- P2: a malformed id becomes 503.
- P3: the catch-all is not logged.

All 14 were killed. L3 first **survived** against `CleanupRaceIntegrationTest`: today's bean order happens to
create the layout check before the worker, so the whole-application test cannot tell whether the worker resolves it
itself. The storage adapters reach S3 lazily, so nothing guarantees that order. `MediaWorkerConfigurationTest` and
`LifecycleConfigurationTest` now model the worst order with a lazy, failing check; against them L3 and L4 are killed.

## Verification

| Evidence | Dependency | Checks |
| --- | --- | --- |
| `CleanupRaceIntegrationTest` | PostgreSQL 18.6, SeaweedFS 4.46, the whole Media application | 7 (new) |
| `LifecycleCleanupIntegrationTest` | PostgreSQL 18.6, SeaweedFS 4.46 | 9 (helpers moved to `LifecycleFixture`) |
| `UploadControlIntegrationTest` | PostgreSQL 18.6, Media's controller and security | 16 (1 new) |
| `BoundedMediaWorkerTest` | Mocked leases, real threads and clocks | 8 (3 new) |
| `ObjectCleanupTest`, `LifecycleMetricsTest` | In-memory ports, mocked JDBC | 9 (1 new), 2 |
| `MediaWorkerConfigurationTest`, `LifecycleConfigurationTest` | Spring context runner | 3 (1 new), 1 (new) |
| Mutants K1-K3, L1-L4, S1, T1-T2, P1-P3 | The suites above | 14 killed |

A ci-gate agent ran every `.github/workflows/core-ci.yml` stage on the final tree (`tmp/ci-gate/m32-r2-*.log`). Results:

- Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 13m15s).
- The report gate confirmed 91 suites and 648 tests with zero failures, errors or skips:
  - Core 188 (45 Surefire, 143 Failsafe);
  - Media 458 (244 Surefire, 214 Failsafe);
  - recommendation and analytics 2.
- The 16 Python gate tests passed, and `check_contracts.py` passed 22 checks.
- `docker compose config --quiet` with `.env.example` passed with and without `--profile full`.
- The packaged Core smoke passed, and `git diff --check` was clean.

Against M31 that is two new suites and 14 new tests, all in Media. The first gate run (`tmp/ci-gate/m32-*.log`) failed on
the fixture flake described above; the run after the fixture fix passed. Everything ran on the uncommitted tree with
offline Maven and Docker Desktop on Windows, not on CI's runner.

Work was divided among agents. An Explore agent audited observability privacy, a code-reviewer agent reviewed the full
draft (F1-F17), and a ci-gate agent ran the final gate.

## Limits

- **Browser PUTs.** Nothing Media controls bounds a browser PUT: the S3 gateway's `client_body_timeout` bounds the pause
  between two reads, not the transfer. A PUT still in flight when its upload is recorded clean leaves its object behind
  (reproduced with a zero grace). The write grace (default 1 h) must outlast the slowest admitted upload.
- **Paused workers.** A worker paused between its validity check and the request leaving the process can still write
  once after its lease; the request timeout then bounds that write. A renewal confirmed after a lapse leaves the lease
  held until it expires, and the attempt is spent (F13).
- **Layout record.** The layout is recorded at the first M32 start, so a layout changed before that is not detected.
  A plan without a selection relies on the startup check alone.
- **Gauge reads (F3).** Gauge reads still wait for a pool connection (Hikari's connection timeout) before the statement
  timeout applies, and a network that drops the cancel request is bounded by no socket timeout. A scrape under a lock
  costs up to 2 s per gauge.
- **Silent schedule loss.** A thrown `Error` ends the worker's, relay's or sweeper's schedule without a log or a
  counter. The backlog gauges (`libra.media.cleanup.pending`, queue age) show the effect; nothing names the cause.
- **Problem `instance`.** The problem `instance` is Core's own request path, which carries the upload id Core chose. It
  is not a Media secret.
- **Unchanged from M31.** There is no retention policy for failed assets, scratch space is reclaimed only at startup,
  there is no metrics scrape endpoint, and Core and Media must be deployed together.
