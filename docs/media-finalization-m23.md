# Media: Output verification and transactional READY (Milestone 23)

Verification date: 2026-10-08.
Status: VERIFIED_LOCAL (see Verification and Limits).

## Scope

M23 completes phase D of the first slice: a claimed job is frozen, probed, encoded, stored, verified and committed
READY under its lease. It adds no migration, API, wire contract, failure code, stage or setting. The columns and job
stages it writes (`output_prefix`, `master_manifest_key`, `duration_seconds`, `aggregate_version`, `FINALIZING`,
`SUCCEEDED`, `media_outbox_events`) have existed since V1/V2.

Nothing is published to Kafka: READY, PROCESSING and FAILED events are committed `PENDING` outbox rows that the
M25 publisher will deliver. Core therefore still sees no Media readiness, and protected HLS delivery remains M27-M30.

## What happens after the encode

`OutputFinalizer` is the last stage and the production `MediaJobHandler`. It is wired only when the transcode stage is
configured (`libra.media.transcode.executable`), and runs only when the worker is separately enabled
(`libra.media.worker.enabled`, `false` by default; Compose enables it since [M29](media-protected-delivery-ingress-m29.md)).

1. `SourceTranscoder` hands over a sealed workspace (M21/M22) and the frozen source key it encoded.
2. `HlsPackage.requireConsistent` re-reads the workspace: the variant playlist must equal the one written for the
   inventory, the master's only reference must be `rendition.m3u8`, each segment must have its sealed size, and no
   other file may exist. A changed workspace never leaves it.
3. Every file is hashed locally (SHA-256, streamed).
4. `beginFinalizing` moves the job `TRANSCODING -> FINALIZING` under the lease; a lost lease stops here with nothing
   stored.
5. Objects are written under the claim's own prefix `<hls-prefix><uploadId>/attempt-<n>/`: segments first, then the
   variant, then the master, so no stored playlist precedes an object it names. Content types are
   `video/mp2t` and `application/vnd.apple.mpegurl`.
6. Every object is read back from storage with bounded streaming and compared by length and SHA-256. A missing,
   short or different object is a retryable `PROCESSING_FAILED`.
7. `select` commits, in one `REQUIRES_NEW` transaction (5 s timeout) fenced by the job/upload/attempt/token/asset
   tuple and a database-time expiry check:
   - asset `PROCESSING -> READY` with `output_prefix`, `master_manifest_key` and `duration_seconds`, only if no output
     is selected yet and the selected source is the one that was encoded;
   - `aggregate_version + 1` and one `MediaAssetStateChanged` READY row in `media_outbox_events`;
   - job `FINALIZING -> SUCCEEDED`, lease cleared.

   Any failed statement, including the outbox insert, rolls all of it back. An asset that already has an output or a
   different source is an invariant violation and throws instead of committing.
8. The handler returns `Outcome.settled()`: the worker records nothing further for a READY job (a write after the
   lease was cleared could only be refused).

Storage and PostgreSQL are not atomic together. A failed or overtaken attempt leaves its objects unreferenced under
its own attempt prefix for the M31 scoped cleanup; the unique `output_prefix` and the attempt-specific key mean such
objects can never become, or overwrite, a selected generation.

`duration_seconds` is the rendition duration rounded to the nearest second, at least 1 and at most 600: the source
policy proved the source is at most 600 s, and an encoder may overhang it by part of a frame.

## Asset events

The event state machine of `docs/media-first-slice-contracts.md` is now recorded, and only real transitions produce an
event. Each one bumps `aggregate_version` and inserts its outbox row in the same transaction as the state change.

| Transition | Where | Event |
| --- | --- | --- |
| First claim, asset `QUEUED -> PROCESSING` | `JdbcJobLeases.claim` | PROCESSING |
| Retry claim of a `PROCESSING` asset, transient requeue | `JdbcJobLeases` | none |
| Source selection, probe metadata, stage changes | `JdbcJobSources`, `JdbcSourceMetadata`, stages | none |
| Verified output selected, asset `PROCESSING -> READY` | `JdbcOutputSelections.select` | READY with `durationSeconds` |
| Permanent rejection or exhaustion, asset `-> FAILED` | `JdbcJobLeases` terminal | FAILED, no `durationSeconds` |

The row carries the contract payload (`contentId`, `bindingId`, `assetId`, `assetVersion`, `state`, and
`durationSeconds` for READY only); the envelope is in its columns: `event_id` (random, preserved on replay), type,
schema version, aggregate ID (`asset_id`) and version, `created_at` as `occurredAt`, and the upload's immutable
`request_id` as the correlation ID. A normal job emits `PROCESSING` (version 1) then `READY` or `FAILED` (version 2).
Upload expiry (`UPLOADING -> FAILED`) is still not implemented and emits nothing.

## Failure handling

| Condition | Result |
| --- | --- |
| Workspace changed after sealing, local read error | `PROCESSING_FAILED` (retry), nothing stored |
| Lease lost before `FINALIZING` | `LeaseLost`, nothing stored, nothing recorded |
| Storage write or read fault | `PROCESSING_FAILED` (retry); stored objects stay unreferenced |
| Object missing, short or different on readback | `PROCESSING_FAILED` (retry), no READY |
| Lease lost between readback and commit | `LeaseLost`: the fenced commit changes nothing |
| Outbox (or any) statement fails in the commit | Exception; the transaction rolls back; the worker records a retry under the still-current lease |
| Asset already has an output, or another source | Invariant exception (`Selected output is immutable`); rollback |
| Cancellation | `InterruptedException`, no further object written; the workspace is deleted |

The next attempt re-encodes from the same frozen source into its own prefix. The three-attempt budget still bounds a
deterministic fault.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `OutputFinalizerTest` | In-memory ports, mocked transcoder handing over a sealed synthetic workspace | 10 (new) |
| `HlsPackageTest` | Temp directories | 19 (2 new: consistency after sealing) |
| `BoundedMediaWorkerTest` | Mocked lease port | 5 (1 new: a settled outcome records nothing) |
| `OutputFinalizationIntegrationTest` | **Real PostgreSQL 18.6, SeaweedFS 4.46, ffprobe and ffmpeg 9.0.2**, the real worker | 7 (new) |
| Seven existing PostgreSQL/SeaweedFS suites | Same real dependencies | Assertions updated: a claim now has its PROCESSING event; terminal failures their FAILED event; HLS counts scoped per upload |

`OutputFinalizationIntegrationTest` covers: a 1080p AAC fixture to READY with the exact asset row, job SUCCEEDED,
`[PROCESSING, READY]` at aggregate versions 1 and 2, the READY payload's exact keys and values, correlation ID,
`PENDING` delivery, the stored closed inventory and content types, a spent lease; an acknowledged but lost segment
(no READY, job left FINALIZING, the retry claim emits no second PROCESSING and attempt 2 commits its own prefix while
attempt 1's objects stay unreferenced); a PostgreSQL trigger refusing the READY outbox insert (state, generation,
duration, version and job success all roll back, the lease stays current, the next attempt succeeds); a lease taken
over between readback and commit (stale attempt can neither finalize nor select its own complete generation); the
adapter's fences (no finalizing before transcoding, impostor token, a foreign source key throws and commits nothing,
double select); a rejected HEVC source ending `[PROCESSING, FAILED]` without `durationSeconds`; and the real
`BoundedMediaWorker` running the production handler to READY with nothing recorded after it.

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 11m45s). The report gate confirmed 68
suites and 517 tests with zero failures, errors or skips: Core 186, Media 329 (163 Surefire, 166 Failsafe),
recommendation and analytics 2. M23 added 20 tests. All 18 contracts passed and `git diff --check` was clean. Raw
logs: `tmp/m23-reactor-verify-2.log`, `tmp/m23-focused-it*.log` (ignored).

Runs that failed before the final ones and are not counted: the first focused integration run had five failures, all
in tests: one asserted `IllegalStateException` where the repository's exception translation delivers
`InvalidDataAccessApiUsageException` (same message, same rollback), and four M21 assertions counted every object in the
HLS bucket, which test containers share across classes and which M23 now legitimately fills; they now count their own
upload's prefix. The first root `clean verify` failed one existing test,
`ScratchSpaceTest.aKilledProcessReleasesItsLockAndTheNextInstanceReclaimsItsPartialCopy`, which M23 does not touch
(neither the test nor `ScratchSpace` changed): a forcibly killed process's file was still present
when the next instance reclaimed. It passed three isolated reruns and the second root run; it is a Windows timing
flake under reactor load and was not changed.

## Limits

- READY is committed locally only. No outbox publisher exists yet (M25), so Core's candidate does not become READY and
  publication remains blocked; nothing here is playable.
- Readback proves an object was durable and readable once, through the same storage identity, just before the
  commit. It does not prove later availability, and there is no storage-side immutability (object lock); the
  application never writes an attempt prefix twice.
- Unreferenced attempt objects accumulate until M31 cleanup. A storage outage after the write but before readback can
  also leave them.
- The commit transaction does not re-check storage (no I/O inside transactions); a deletion of a verified object
  between readback and commit is not detected. Only Media's private identity can write or delete in the HLS bucket.
- Upload expiry does not yet emit FAILED.
- M24 hardens finalization: crash points around storage and the commit, worker acknowledgement after commit, traversal
  injection into stored playlists, and event/aggregate counts under interruption.
- Local containers and loopback only; the full Compose stack with the worker enabled, multi-host, remote CI and
  deployment were not exercised. GitNexus was not used.
