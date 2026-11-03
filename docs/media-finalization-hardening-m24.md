# Media: Finalization and partial-output consistency (Milestone 24)

Verification date: 2026-10-11.
Status: VERIFIED_LOCAL (local READY and outbox; nothing is published to Kafka).

## Scope

M24 hardens the M23 finalizer. It adds one Flyway migration (`V5__guard_media_ready_selection.sql`) and no API, wire
contract, failure code, job stage or setting. The worker is still disabled by default, and READY is still a committed
`PENDING` outbox row that the M25 publisher will deliver.

## Gate

No incomplete asset is READY and no READY commit lacks its event. A completed job is replay-safe; a selected source
and output generation stay immutable.

## Findings and repairs

Each finding was reproduced by a test on the M23 code before the repair (`tmp/m24-baseline-unit.log`: four failures).

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| **A URI inside a master-playlist tag passed the consistency check.** `requireConsistent` only compared the master's URI lines with `rendition.m3u8`. `#EXT-X-SESSION-DATA`, `#EXT-X-MEDIA`, `#EXT-X-I-FRAME-STREAM-INF` and `#EXT-X-SESSION-KEY` lines with an external or traversal `URI="..."` were stored as part of READY. | The seal records the exact master text in `HlsOutput.masterPlaylist`; `requireConsistent` compares both playlists byte for byte (`MASTER_CHANGED`, `VARIANT_CHANGED`). An expected master that is not the service's shape (a second URI line, any `URI=`, no header) is `INVENTORY`. | `aReferenceSmuggledIntoTheMasterThroughATagIsCaught`, `anInventoryThatIsNotThisPipelinesShapeIsNeverConsistent`, `aVariantThatOmitsAnEntryIsCaught`. |
| **A segment rewritten at the same size after sealing became READY.** The finalizer hashed the workspace again after the check and compared the readback with that fresh hash, so a change between seal and upload (same name, same length, whole packets) was stored, verified against itself and selected. | The seal hashes every segment in the same streamed read that checks it and records `Segment.sha256`. The finalizer never hashes the workspace: the expected digests are the sealed segment digests and the SHA-256 of the exact playlist texts. A file that changes at any point after the seal is still uploaded, but its readback cannot match, so it is a retryable `PROCESSING_FAILED` and nothing commits. | `aSegmentRewrittenAfterSealingIsNeverSelectedEvenAtTheSameSize`, `aPlaylistChangedWhileSegmentsAreStoredIsNeverSelected`, `theSealRecordsTheDigestOfEverySegmentAndBothPlaylists`. |
| **A segment with a torn packet in the middle was sealed.** M22 checked the length (whole 188-byte packets) and the first sync byte only. | The sealing read now checks the sync byte of every packet, and that the file did not grow after its size was taken. Every real-ffmpeg suite (M21/M22/M23 fixtures) passed with the stricter check. | `aSegmentWithATornPacketInsideIsRejected`. |
| **The database accepted any rewrite of a selected generation and READY without its event.** Every M23 guarantee lived in application code (fenced `UPDATE ... WHERE output_prefix IS NULL` and the commit order). | `V5`: a `BEFORE UPDATE` trigger refuses changing `selected_source_key` once set, changing `output_prefix`, `master_manifest_key` or `duration_seconds` once an output is selected, and leaving READY. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks at commit that a READY asset has the READY event of its current `aggregate_version` carrying its `durationSeconds`. Both raise SQLSTATE `23000` (Spring `DataIntegrityViolationException`). | `theDatabaseRefusesToChangeASelectedGenerationOrLeaveReady` (seven rewrites, including a version bump without an event), `theDatabaseRefusesReadyWithoutTheEventOfItsVersionAndDuration` (no event, wrong version, wrong state, wrong duration, missing duration); the production commit passes the same check. |

The application paths are unchanged by V5: source selection writes `selected_source_key` from `NULL`, the READY commit
writes the output from `NULL` and appends its event in the same transaction, and no code moves an asset out of
READY. The M04 persistence test that wrote READY and its event as two autocommit statements now writes them in one
transaction and asserts that READY alone is refused.

## Crash and overtaking matrix

`FinalizationConsistencyIntegrationTest` (real PostgreSQL 18.6, SeaweedFS 4.46, ffprobe/ffmpeg 9.0.2). A crash is
simulated in-process: the stage throws an `Error` that nothing maps to an outcome, and the lease is left to run out
as a killed process leaves it. Every scenario ends with exact event and aggregate counts (`[PROCESSING, READY]`,
aggregate version 2, the READY event at the current version with the asset's duration) and key-plus-SHA-256
snapshots of the generations.

| Point | Result |
| --- | --- |
| After every object is stored and read back, before the commit | No READY; job left `FINALIZING`. After expiry attempt 2 claims (no second PROCESSING), finalizes its own prefix; attempt 1's complete generation is unchanged, unreferenced and can be neither finalized nor selected by its old lease. |
| During upload (segments stored, process dies before the first playlist) | Attempt 1 holds segments only, never a playlist; attempt 2 is READY; the orphan is unchanged. |
| A stored segment changes in storage before readback (same length) | Readback mismatch, retryable `PROCESSING_FAILED`, no READY; the retry is READY. |
| An expired worker carries on after its successor committed READY | The stale claim had passed `FINALIZING`, stalled, and then stored and verified its whole generation; its fenced commit changed nothing. Asset row, outbox rows, job row and the selected generation are byte-identical to the moment of the successor's commit; the handler settles the stale claim and its retry and fail are refused. |
| The commit reaches PostgreSQL but its acknowledgement is lost | The real `BoundedMediaWorker` maps the exception to a retry, which the spent lease refuses: no report, attempt 1, no failure code, one READY event. |
| A finished job is replayed (spent lease, `beginFinalizing`, `select`, renew/retry/fail/release, a restarted worker, a fresh claim) | Nothing changes: asset, outbox, job and generation snapshots are identical; no workspace remains. |
| Outbox insert refused | M23's `aFailedOutboxWriteRollsBackReadyAndItsGeneration` (unchanged): full rollback; the retry selects a new prefix and the old one stays an orphan. |

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `HlsPackageTest` | Temp directories | 23 (4 new) |
| `OutputFinalizerTest` | In-memory ports, mocked transcoder | 12 (2 new) |
| `FinalizationConsistencyIntegrationTest` | **Real PostgreSQL 18.6, SeaweedFS 4.46, ffprobe and ffmpeg 9.0.2**, the real worker | 7 (new) |
| `OutputFinalizationIntegrationTest` | Same; now shares `FinalizationFixture` (one Spring context) | 7 (unchanged) |
| `MediaPersistenceIntegrationTest` | Real PostgreSQL 18.6 | 7 (one test adjusted for V5) |

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 12m12s). The report gate confirmed 69
suites and 530 tests with zero failures, errors or skips: Core 186, Media 342 (169 Surefire, 173 Failsafe),
recommendation and analytics 2. M24 added 13 tests. All 18 contracts passed and `git diff --check` was clean. Raw logs:
`tmp/m24-baseline-unit.log`, `tmp/m24-unit-1.log`, `tmp/m24-focused-it-*.log`, `tmp/m24-reactor-verify-1.log`
(ignored).

Runs that failed and are not counted: the baseline run against the M23 code (four expected failures, the findings
above); a first compile with a malformed test string literal; and the first focused integration run, where one case
of the new database test cleared `duration_seconds` together with the state and was refused by the output rule
instead of the terminal-READY rule it targeted (a correct refusal for the wrong reason; the case now changes the
state only).

## Limits

- Crashes are simulated in the test JVM at exact points; no separate process was killed during finalization (the M16
  packaged-process test kills a real worker JVM, but around claims, not storage).
- Storage has no object lock: readback proves an object once, just before the commit, and a deletion between readback
  and commit is not detected (only Media's private identity can write the HLS bucket). The database guards protect
  the selection, not the objects.
- Orphaned attempt prefixes accumulate until the M31 scoped cleanup.
- The outbox row itself is not guarded against later deletion; M25-M26 own delivery state and replay.
- Kafka publication and Core projection (M25), protected delivery (M27-M30), upload-expiry FAILED events, the packaged
  image running the finalizer, the full Compose stack with the worker on, multi-host, remote CI and deployment were
  not exercised. GitNexus was not used.
